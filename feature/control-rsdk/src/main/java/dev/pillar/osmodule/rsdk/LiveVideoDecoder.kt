package dev.pillar.osmodule.rsdk

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/** Small Surface-output decoder for the camera's Annex-B H.264/H.265 viewfinder stream. */
internal class LiveVideoDecoder(private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onFramePresented(codec: String, width: Int, height: Int)
        fun onDecoderFailure(message: String)
    }

    private enum class CodecKind(val mime: String, val label: String) {
        AVC(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264"),
        HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "H.265"),
    }

    private val lock = Any()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var codecKind: CodecKind? = null
    private var outputThread: Thread? = null
    private var outputRunning = false
    private var pendingKeyframe: ByteArray? = null
    private var awaitingKeyframe = true
    private var ptsUs = 0L
    private var width = DEFAULT_WIDTH
    private var height = DEFAULT_HEIGHT
    private var firstFrameDelivered = false
    private var failureDelivered = false
    private val parameterSets = linkedMapOf<Int, ByteArray>()

    fun attachSurface(next: Surface?) = synchronized(lock) {
        if (surface === next) return@synchronized
        releaseCodecLocked()
        surface = next
        val decoder = configureIfReadyLocked()
        val bootstrap = pendingKeyframe
        if (decoder != null && bootstrap != null && queueLocked(decoder, bootstrap, true)) {
            awaitingKeyframe = false
            pendingKeyframe = null
        }
    }

    fun decode(accessUnit: ByteArray): Boolean = synchronized(lock) {
        val nals = annexBNals(accessUnit)
        if (nals.isEmpty()) return@synchronized false
        val detected = detectCodec(nals)
        if (detected != null && codecKind != null && codecKind != detected) {
            releaseCodecLocked()
            parameterSets.clear()
            pendingKeyframe = null
        }
        if (detected != null) codecKind = detected
        rememberParameterSets(nals, codecKind)
        val keyframe = isKeyframe(nals, codecKind)
        if (keyframe) pendingKeyframe = accessUnit.copyOf()

        val decoder = codec ?: configureIfReadyLocked() ?: return@synchronized false
        if (awaitingKeyframe) {
            val bootstrap = pendingKeyframe ?: return@synchronized false
            val queued = queueLocked(decoder, bootstrap, true)
            if (!queued) return@synchronized false
            awaitingKeyframe = false
            pendingKeyframe = null
            if (keyframe) return@synchronized true

            // The current inter-frame arrived while a previously blocked keyframe was retried.
            // Queue it too so the next picture cannot depend on a frame silently skipped here.
            val currentQueued = queueLocked(decoder, accessUnit, false)
            if (!currentQueued) awaitingKeyframe = true
            return@synchronized currentQueued
        }

        val queued = queueLocked(decoder, accessUnit, keyframe)
        if (queued && keyframe) pendingKeyframe = null
        if (!queued) {
            // A missing inter-frame can invalidate every dependent picture in the GOP. Stop
            // feeding dependent frames until a fresh IDR/IRAP arrives instead of presenting
            // corruption; a failed keyframe remains pending so the next call can retry it.
            awaitingKeyframe = true
            if (!keyframe) pendingKeyframe = null
        }
        queued
    }

    private fun configureIfReadyLocked(): MediaCodec? {
        codec?.let { return it }
        val target = surface?.takeIf { it.isValid } ?: return null
        val kind = codecKind ?: return null
        val format = MediaFormat.createVideoFormat(kind.mime, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE_HINT)
            setFloat(MediaFormat.KEY_OPERATING_RATE, OPERATING_RATE_HINT)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            when (kind) {
                CodecKind.HEVC -> {
                    val sets = listOfNotNull(parameterSets[32], parameterSets[33], parameterSets[34])
                    if (sets.size < 3) return null
                    setByteBuffer("csd-0", ByteBuffer.wrap(join(sets)))
                }
                CodecKind.AVC -> {
                    val sps = parameterSets[7] ?: return null
                    val pps = parameterSets[8] ?: return null
                    setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                }
            }
        }

        return try {
            val created = createDecoder(kind.mime)
            created.configure(format, target, null, 0)
            created.start()
            codec = created
            awaitingKeyframe = true
            outputRunning = true
            startOutputThread(created, kind)
            Log.i(TAG, "${kind.label} decoder ${created.name} ready")
            created
        } catch (e: Exception) {
            Log.w(TAG, "decoder configure failed", e)
            deliverFailure("设备无法解码相机实时画面")
            null
        }
    }

    private fun startOutputThread(decoder: MediaCodec, kind: CodecKind) {
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (outputRunning) {
                val index = try {
                    decoder.dequeueOutputBuffer(info, 10_000)
                } catch (_: Exception) {
                    break
                }
                when {
                    index >= 0 -> {
                        // This is a viewfinder, not timed playback: present each decoded frame as
                        // soon as it is ready. Scheduling against System.nanoTime could leave a
                        // hardware-specific compositor queue showing only occasional frames.
                        runCatching { decoder.releaseOutputBuffer(index, true) }
                        if (!firstFrameDelivered) {
                            firstFrameDelivered = true
                            listener.onFramePresented(kind.label, width, height)
                        } else {
                            listener.onFramePresented(kind.label, width, height)
                        }
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = runCatching { decoder.outputFormat }.getOrNull()
                        width = output?.integer(MediaFormat.KEY_WIDTH) ?: width
                        height = output?.integer(MediaFormat.KEY_HEIGHT) ?: height
                    }
                }
            }
        }, "osmodule.live.decoder").also { it.isDaemon = true; it.start() }
    }

    private fun queueLocked(decoder: MediaCodec, accessUnit: ByteArray, keyframe: Boolean): Boolean {
        return try {
            val index = decoder.dequeueInputBuffer(if (keyframe) KEYFRAME_WAIT_US else INPUT_WAIT_US)
            if (index < 0) return false
            val input = decoder.getInputBuffer(index) ?: return false
            if (input.capacity() < accessUnit.size) {
                deliverFailure("实时画面帧超出解码器容量")
                return false
            }
            input.clear()
            input.put(accessUnit)
            val nextPts = SystemClock.elapsedRealtimeNanos() / 1_000
            ptsUs = if (nextPts <= ptsUs) ptsUs + 1 else nextPts
            decoder.queueInputBuffer(
                index,
                0,
                accessUnit.size,
                ptsUs,
                if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "decoder queue failed", e)
            deliverFailure("实时画面解码中断")
            false
        }
    }

    private fun rememberParameterSets(nals: List<ByteArray>, kind: CodecKind?) {
        if (kind == null) return
        nals.forEach { nal ->
            val first = firstNalByte(nal) ?: return@forEach
            val type = when (kind) {
                CodecKind.HEVC -> (first ushr 1) and 0x3F
                CodecKind.AVC -> first and 0x1F
            }
            val wanted = when (kind) {
                CodecKind.HEVC -> type in 32..34
                CodecKind.AVC -> type == 7 || type == 8
            }
            if (wanted) parameterSets[type] = nal.copyOf()
        }
    }

    private fun detectCodec(nals: List<ByteArray>): CodecKind? {
        nals.forEach { nal ->
            when (firstNalByte(nal)) {
                0x40, 0x42, 0x44 -> return CodecKind.HEVC
                0x67, 0x68 -> return CodecKind.AVC
            }
        }
        return null
    }

    private fun isKeyframe(nals: List<ByteArray>, kind: CodecKind?): Boolean = nals.any { nal ->
        val first = firstNalByte(nal) ?: return@any false
        when (kind) {
            CodecKind.AVC -> (first and 0x1F) == 5
            CodecKind.HEVC -> ((first ushr 1) and 0x3F) in 16..21
            null -> false
        }
    }

    private fun annexBNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 <= data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte()) {
                starts += if (index > 0 && data[index - 1] == 0.toByte()) index - 1 else index
                index += 3
            } else {
                index++
            }
        }
        if (starts.isEmpty()) return emptyList()
        return starts.indices.mapNotNull { position ->
            val from = starts[position]
            val to = starts.getOrNull(position + 1) ?: data.size
            if (to > from) data.copyOfRange(from, to) else null
        }
    }

    private fun firstNalByte(nal: ByteArray): Int? {
        val offset = when {
            nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4
            nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte() -> 3
            else -> return null
        }
        return nal.getOrNull(offset)?.toInt()?.and(0xFF)
    }

    private fun createDecoder(mime: String): MediaCodec {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val hardware = codecs.firstOrNull { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                info.isHardwareAccelerated
        }
        return hardware?.let { MediaCodec.createByCodecName(it.name) }
            ?: MediaCodec.createDecoderByType(mime)
    }

    private fun releaseCodecLocked() {
        outputRunning = false
        val worker = outputThread
        outputThread = null
        val active = codec
        codec = null
        awaitingKeyframe = true
        runCatching { active?.stop() }
        worker?.interrupt()
        if (worker !== Thread.currentThread()) runCatching { worker?.join(250) }
        runCatching { active?.release() }
        firstFrameDelivered = false
    }

    private fun deliverFailure(message: String) {
        if (!failureDelivered) {
            failureDelivered = true
            listener.onDecoderFailure(message)
        }
    }

    override fun close() = synchronized(lock) {
        releaseCodecLocked()
        surface = null
        parameterSets.clear()
        pendingKeyframe = null
        codecKind = null
    }

    private fun MediaFormat.integer(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun join(parts: List<ByteArray>): ByteArray {
        val result = ByteArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach { part ->
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }

    private companion object {
        const val TAG = "LiveVideoDecoder"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val FRAME_RATE_HINT = 60
        const val OPERATING_RATE_HINT = 60f
        const val INPUT_WAIT_US = 4_000L
        const val KEYFRAME_WAIT_US = 50_000L
    }
}
