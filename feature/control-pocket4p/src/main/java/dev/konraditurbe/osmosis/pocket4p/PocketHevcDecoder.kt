package dev.konraditurbe.osmosis.pocket4p

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Low-latency Surface-output decoder for the Pocket 4 / 4 Pro Annex-B HEVC viewfinder stream.
 *
 * Adapted from OpenPocketCine's Android `HevcDecoder.kt`, Copyright 2026 Erik Sutton and
 * OpenPocketCine contributors, licensed under Apache License 2.0:
 * https://github.com/erik-sutton95/OpenPocketCine
 *
 * This reduced Kotlin adaptation is HEVC-only: it collects VPS/SPS/PPS NALs (types 32/33/34),
 * waits for an IRAP picture (types 16...21), and presents decoded frames directly to a [Surface].
 */
internal class PocketHevcDecoder(
    private val listener: Listener,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
) : AutoCloseable {
    interface Listener {
        fun onFramePresented(width: Int, height: Int)
        fun onDecoderFailure(message: String)
    }

    private data class AnnexBNal(val bytes: ByteArray, val type: Int)

    private val lock = Any()
    private val parameterSets = linkedMapOf<Int, ByteArray>()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    @Volatile private var outputRunning = false
    private var awaitingIrap = true
    private var latestIrap: ByteArray? = null
    private var parameterSetRaster: PocketHevcSps.Raster? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var ptsUs = 0L
    private var failureDelivered = false

    fun attachSurface(next: Surface?) = synchronized(lock) {
        if (surface === next) return@synchronized
        releaseCodecLocked()
        surface = next
        configureIfReadyLocked()?.let(::queueBootstrapIrapLocked)
    }

    /** Keep the displayed frame and codec, but reject old-GOP pictures until the requested IDR. */
    fun awaitFreshIrap() = synchronized(lock) {
        awaitingIrap = true
        latestIrap = null
    }

    /** Returns true only when [accessUnit] (or its held bootstrap IRAP) was queued. */
    fun decode(accessUnit: ByteArray): Boolean = synchronized(lock) {
        val nals = annexBNals(accessUnit)
        if (nals.isEmpty()) return@synchronized false

        val parameterSetsChanged = rememberParameterSets(nals)
        val nextRaster = parameterSets[SPS_NAL_TYPE]?.let(PocketHevcSps::raster)
        if (nextRaster != null) parameterSetRaster = nextRaster
        val isIrap = nals.any { it.type in IRAP_TYPES }
        if (parameterSetsChanged && codec != null && shouldRebuildForRaster(
                configuredWidth,
                configuredHeight,
                nextRaster,
            )
        ) {
            releaseCodecLocked()
            latestIrap = null // An IRAP from the previous parameter-set generation is unsafe.
        }
        if (isIrap) latestIrap = accessUnit.copyOf()

        val decoder = codec ?: configureIfReadyLocked() ?: return@synchronized false
        if (awaitingIrap) {
            val bootstrap = latestIrap ?: return@synchronized false
            if (!queueBootstrapIrapLocked(decoder, bootstrap)) return@synchronized false
            if (isIrap) return@synchronized true

            // Preserve the first dependent picture that arrived while a blocked IRAP was retried.
            val currentQueued = queueLocked(decoder, accessUnit, false)
            if (!currentQueued) {
                awaitingIrap = true
                latestIrap = null
            }
            return@synchronized currentQueued
        }
        val queued = queueLocked(decoder, accessUnit, isIrap)
        if (!queued) {
            // Once an inter-frame is lost, later pictures in that GOP may reference unavailable
            // data. Resume only from a fresh IRAP; retain a failed IRAP so it can be retried.
            awaitingIrap = true
            if (!isIrap) latestIrap = null
        }
        queued
    }

    private fun configureIfReadyLocked(): MediaCodec? {
        codec?.let { return it }
        val target = surface?.takeIf(Surface::isValid) ?: return null
        val sets = PARAMETER_SET_TYPES.map { parameterSets[it] ?: return null }
        val raster = parameterSetRaster
        val targetWidth = raster?.width ?: width
        val targetHeight = raster?.height ?: height
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            targetWidth,
            targetHeight,
        ).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE_HINT)
            setFloat(MediaFormat.KEY_OPERATING_RATE, OPERATING_RATE_HINT)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setByteBuffer("csd-0", ByteBuffer.wrap(join(sets)))
        }

        var created: MediaCodec? = null
        return try {
            val decoder = createDecoder()
            created = decoder
            decoder.configure(format, target, null, 0)
            decoder.start()
            codec = decoder
            configuredWidth = targetWidth
            configuredHeight = targetHeight
            awaitingIrap = true
            outputRunning = true
            startOutputThread(decoder, targetWidth, targetHeight)
            Log.i(TAG, "HEVC decoder ${decoder.name} ready at ${targetWidth}x$targetHeight")
            decoder
        } catch (error: Exception) {
            runCatching { created?.stop() }
            runCatching { created?.release() }
            codec = null
            deliverFailure("this device could not configure an HEVC decoder")
            Log.w(TAG, "HEVC decoder configure failed", error)
            null
        }
    }

    private fun queueBootstrapIrapLocked(decoder: MediaCodec): Boolean {
        val accessUnit = latestIrap ?: return false
        return queueBootstrapIrapLocked(decoder, accessUnit)
    }

    private fun queueBootstrapIrapLocked(decoder: MediaCodec, accessUnit: ByteArray): Boolean {
        val queued = queueLocked(decoder, accessUnit, keyframe = true)
        if (queued) awaitingIrap = false
        return queued
    }

    private fun queueLocked(
        decoder: MediaCodec,
        accessUnit: ByteArray,
        keyframe: Boolean,
    ): Boolean {
        return try {
            val index = decoder.dequeueInputBuffer(if (keyframe) KEYFRAME_WAIT_US else INPUT_WAIT_US)
            if (index < 0) return false
            val input = decoder.getInputBuffer(index) ?: return false
            if (input.capacity() < accessUnit.size) {
                deliverFailure("the live-view access unit exceeds the decoder input capacity")
                return false
            }
            input.clear()
            input.put(accessUnit)
            val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            ptsUs = if (nowUs <= ptsUs) ptsUs + 1 else nowUs
            decoder.queueInputBuffer(
                index,
                0,
                accessUnit.size,
                ptsUs,
                if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
            true
        } catch (error: Exception) {
            deliverFailure("the HEVC decoder stopped accepting input")
            Log.w(TAG, "HEVC decoder queue failed", error)
            false
        }
    }

    private fun startOutputThread(decoder: MediaCodec, outputWidth: Int, outputHeight: Int) {
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()
            var presentedWidth = outputWidth
            var presentedHeight = outputHeight
            while (outputRunning) {
                val index = try {
                    decoder.dequeueOutputBuffer(info, OUTPUT_WAIT_US)
                } catch (_: Exception) {
                    break
                }
                when {
                    index >= 0 -> {
                        if (runCatching { decoder.releaseOutputBuffer(index, true) }.isSuccess) {
                            listener.onFramePresented(presentedWidth, presentedHeight)
                        }
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = runCatching { decoder.outputFormat }.getOrNull()
                        outputFormat?.let { format ->
                            val raster = displayedRaster(format, outputWidth, outputHeight)
                            presentedWidth = raster.width
                            presentedHeight = raster.height
                        }
                        Log.i(TAG, "HEVC output $outputFormat")
                    }
                }
            }
        }, "osmodule.pocket4p.hevc").also { thread ->
            thread.isDaemon = true
            thread.start()
        }
    }

    /** Returns true when at least one VPS/SPS/PPS value changed. */
    private fun rememberParameterSets(nals: List<AnnexBNal>): Boolean {
        var changed = false
        nals.forEach { nal ->
            if (nal.type !in PARAMETER_SET_TYPES) return@forEach
            val previous = parameterSets[nal.type]
            if (previous == null || !previous.contentEquals(nal.bytes)) {
                parameterSets[nal.type] = nal.bytes.copyOf()
                changed = true
            }
        }
        return changed
    }

    private fun annexBNals(data: ByteArray): List<AnnexBNal> {
        data class Start(val offset: Int, val length: Int)

        val starts = ArrayList<Start>()
        var index = 0
        while (index + 3 <= data.size) {
            val length = when {
                index + 4 <= data.size && data[index] == 0.toByte() &&
                    data[index + 1] == 0.toByte() && data[index + 2] == 0.toByte() &&
                    data[index + 3] == 1.toByte() -> 4
                data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                    data[index + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (length == 0) {
                index++
            } else {
                starts += Start(index, length)
                index += length
            }
        }
        return starts.indices.mapNotNull { position ->
            val start = starts[position]
            val header = start.offset + start.length
            val end = starts.getOrNull(position + 1)?.offset ?: data.size
            if (header >= end) return@mapNotNull null
            val type = ((data[header].toInt() and 0xFF) ushr 1) and 0x3F
            if (type == DJI_MARKER_NAL_TYPE) return@mapNotNull null
            AnnexBNal(data.copyOfRange(start.offset, end), type)
        }
    }

    private fun createDecoder(): MediaCodec {
        val hardware = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
        }
        return hardware?.let { MediaCodec.createByCodecName(it.name) }
            ?: MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
    }

    private fun releaseCodecLocked() {
        outputRunning = false
        val worker = outputThread
        outputThread = null
        val active = codec
        codec = null
        configuredWidth = 0
        configuredHeight = 0
        awaitingIrap = true
        runCatching { active?.stop() }
        worker?.interrupt()
        if (worker !== Thread.currentThread()) runCatching { worker?.join(250) }
        runCatching { active?.release() }
    }

    private fun deliverFailure(message: String) {
        if (failureDelivered) return
        failureDelivered = true
        listener.onDecoderFailure(message)
    }

    override fun close() = synchronized(lock) {
        releaseCodecLocked()
        surface = null
        parameterSets.clear()
        parameterSetRaster = null
        latestIrap = null
        ptsUs = 0L
    }

    private fun join(parts: List<ByteArray>): ByteArray {
        val result = ByteArray(parts.sumOf(ByteArray::size))
        var offset = 0
        parts.forEach { part ->
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }

    companion object {
        private const val TAG = "PocketHevcDecoder"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val FRAME_RATE_HINT = 60
        private const val OPERATING_RATE_HINT = 60f
        private const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        private const val INPUT_WAIT_US = 4_000L
        private const val KEYFRAME_WAIT_US = 50_000L
        private const val OUTPUT_WAIT_US = 10_000L
        private const val DJI_MARKER_NAL_TYPE = 63
        private const val SPS_NAL_TYPE = 33
        private val PARAMETER_SET_TYPES = setOf(32, 33, 34)
        private val IRAP_TYPES = 16..21

        /** Honour decoder crop and pixel-aspect metadata, which can change across camera modes. */
        private fun displayedRaster(
            format: MediaFormat,
            fallbackWidth: Int,
            fallbackHeight: Int,
        ): PocketHevcSps.Raster {
            val codedWidth = format.integerOrNull(MediaFormat.KEY_WIDTH) ?: fallbackWidth
            val codedHeight = format.integerOrNull(MediaFormat.KEY_HEIGHT) ?: fallbackHeight
            val cropLeft = format.integerOrNull("crop-left")
            val cropRight = format.integerOrNull("crop-right")
            val cropTop = format.integerOrNull("crop-top")
            val cropBottom = format.integerOrNull("crop-bottom")
            val croppedWidth = if (cropLeft != null && cropRight != null && cropRight >= cropLeft) {
                cropRight - cropLeft + 1
            } else {
                codedWidth
            }
            val croppedHeight = if (cropTop != null && cropBottom != null && cropBottom >= cropTop) {
                cropBottom - cropTop + 1
            } else {
                codedHeight
            }
            val sarWidth = format.integerOrNull("sar-width")?.coerceAtLeast(1) ?: 1
            val sarHeight = format.integerOrNull("sar-height")?.coerceAtLeast(1) ?: 1
            return PocketHevcSps.Raster(
                width = (croppedWidth * sarWidth.toDouble() / sarHeight).roundToInt().coerceAtLeast(2),
                height = croppedHeight.coerceAtLeast(2),
            )
        }

        private fun MediaFormat.integerOrNull(key: String): Int? =
            runCatching { getInteger(key) }.getOrNull()

        /** Same-raster VPS/SPS/PPS changes stay on the live codec; only a coded-raster change rebuilds. */
        internal fun shouldRebuildForRaster(
            currentWidth: Int,
            currentHeight: Int,
            next: PocketHevcSps.Raster?,
        ): Boolean = currentWidth > 1 && currentHeight > 1 && next != null &&
            (next.width != currentWidth || next.height != currentHeight)
    }
}
