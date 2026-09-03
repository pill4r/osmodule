package dev.konraditurbe.osmosis.panorama.render

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** Factory calibration embedded in the first `djmd` sample of an Osmo 360 LRF/OSV. */
data class PanoramaCalibration(val lenses: List<LensCalibration>) {
    init { require(lenses.size == 2) }
}

data class LensCalibration(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    /** DJI stores this mount quaternion as [x, y, z, w], rotating body coordinates into lens space. */
    val quaternion: FloatArray,
    /** Five Kannala-Brandt radial coefficients (k1...k5). */
    val distortion: FloatArray,
    val radialLutX: FloatArray,
    val radialLutY: FloatArray,
    /** Brown-Conrady tangential coefficients [p1, p2]. */
    val tangential: FloatArray = floatArrayOf(0f, 0f),
) {
    init {
        require(quaternion.size >= 4)
        require(distortion.size >= 4)
        require(tangential.size >= 2)
        require(width > 0f && height > 0f && fx > 0f && fy > 0f)
    }

    /**
     * The calibration coordinates are normally 3840x3840 even when the decoded LRF lens is
     * 1024x1024. Keeping all values normalized makes the GPU projection resolution-independent.
     */
    val shaderValues: LensShaderValues by lazy {
        val quaternionNorm = sqrt(quaternion.take(4).sumOf { (it * it).toDouble() })
            .takeIf { it > 0.000001 }
            ?: 1.0
        LensShaderValues(
            // DJMD and GLSL both use quaternion vector first and scalar last.
            quaternionXyzw = floatArrayOf(
                (quaternion[0] / quaternionNorm).toFloat(),
                (quaternion[1] / quaternionNorm).toFloat(),
                (quaternion[2] / quaternionNorm).toFloat(),
                (quaternion[3] / quaternionNorm).toFloat(),
            ),
            lens = floatArrayOf(
                cx / width,
                cy / height,
                fx / width,
                fy / height,
            ),
            k5 = distortion.getOrElse(4) { 0f },
            tangential = tangential.copyOf(2),
        )
    }
}

data class LensShaderValues(
    val quaternionXyzw: FloatArray,
    /** Normalized [cx, cy, fx, fy]. */
    val lens: FloatArray,
    val k5: Float,
    val tangential: FloatArray,
)

/**
 * Compact, primitive-only representation used to hand factory calibration between Base and the
 * external remote-control APK. A FloatArray survives Bundle/Binder without either process having to
 * deserialize the other's Kotlin classes.
 */
object PanoramaCalibrationCodec {
    private const val MAGIC = 36_001f
    private const val VERSION = 2f
    private const val MAX_ARRAY_VALUES = 256

    fun encode(calibration: PanoramaCalibration): FloatArray = buildList {
        add(MAGIC)
        add(VERSION)
        add(calibration.lenses.size.toFloat())
        calibration.lenses.forEach { lens ->
            add(lens.fx)
            add(lens.fy)
            add(lens.cx)
            add(lens.cy)
            add(lens.width)
            add(lens.height)
            addArray(lens.quaternion)
            addArray(lens.distortion)
            addArray(lens.radialLutX)
            addArray(lens.radialLutY)
            addArray(lens.tangential)
        }
    }.toFloatArray()

    fun decode(values: FloatArray?): PanoramaCalibration? = runCatching {
        if (values == null) return null
        val reader = FloatReader(values)
        if (reader.next() != MAGIC || reader.next() != VERSION || reader.count() != 2) return null
        val lenses = List(2) {
            LensCalibration(
                fx = reader.nextFinite(),
                fy = reader.nextFinite(),
                cx = reader.nextFinite(),
                cy = reader.nextFinite(),
                width = reader.nextFinite(),
                height = reader.nextFinite(),
                quaternion = reader.array(),
                distortion = reader.array(),
                radialLutX = reader.array(),
                radialLutY = reader.array(),
                tangential = reader.array(),
            )
        }
        if (!reader.exhausted()) return null
        PanoramaCalibration(lenses)
    }.getOrNull()

    private fun MutableList<Float>.addArray(values: FloatArray) {
        add(values.size.toFloat())
        values.forEach { add(it) }
    }

    private class FloatReader(private val values: FloatArray) {
        private var position = 0

        fun next(): Float = values.getOrElse(position++) { error("Truncated calibration") }

        fun nextFinite(): Float = next().also { require(it.isFinite()) }

        fun count(): Int {
            val value = nextFinite()
            val count = value.toInt()
            require(value == count.toFloat() && count in 0..MAX_ARRAY_VALUES)
            return count
        }

        fun array(): FloatArray = FloatArray(count()) { nextFinite() }

        fun exhausted(): Boolean = position == values.size
    }
}

/** Minimal protobuf-wire decoder for the small, unencrypted `dvtm_oq101` calibration payload. */
object DjmdCalibrationParser {
    fun parseFileHeader(bytes: ByteArray): PanoramaCalibration? = runCatching {
        val payloadOffset = firstMdatPayload(bytes) ?: return null
        val top = fields(bytes.copyOfRange(payloadOffset, bytes.size))
        val cameraBlock = top[2]?.firstOrNull { it.bytes != null }?.bytes ?: return null
        val calibrationBlock = fields(cameraBlock)[6]?.firstOrNull { it.bytes != null }?.bytes ?: return null
        val lensContainer = fields(calibrationBlock)
        val lenses = lensContainer.toSortedMap().values
            .flatten()
            .mapNotNull { it.bytes?.let(::parseLens) }
            .take(2)
        if (lenses.size == 2) PanoramaCalibration(lenses) else null
    }.getOrNull()

    private fun parseLens(bytes: ByteArray): LensCalibration? {
        val lens = fields(bytes)
        fun scalar(index: Int): Float? = lens[index]?.firstNotNullOfOrNull { field ->
            field.bytes?.takeIf { field.wireType == 5 && it.size == 4 }?.floatLe()
        }
        fun packed(index: Int): FloatArray = lens[index]
            ?.firstNotNullOfOrNull { it.bytes?.takeIf { data -> it.wireType == 2 && data.size % 4 == 0 } }
            ?.floatsLe()
            ?: FloatArray(0)

        val fx = scalar(1) ?: return null
        val fy = scalar(2) ?: return null
        val cx = scalar(3) ?: return null
        val cy = scalar(4) ?: return null
        val width = scalar(10) ?: return null
        val height = scalar(11) ?: return null
        val quaternion = packed(21)
        val distortion = floatArrayOf(
            scalar(5) ?: return null,
            scalar(6) ?: return null,
            scalar(7) ?: return null,
            scalar(8) ?: return null,
            scalar(15) ?: 0f,
        )
        val tangential = packed(20).takeIf { it.size >= 2 } ?: floatArrayOf(0f, 0f)
        if (quaternion.size < 4) return null
        return LensCalibration(
            fx, fy, cx, cy, width, height,
            quaternion, distortion, packed(22), packed(23), tangential,
        )
    }

    private fun firstMdatPayload(bytes: ByteArray): Int? {
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val size32 = bytes.uint32Be(offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            var headerSize = 8
            val size = when (size32) {
                0L -> bytes.size.toLong() - offset
                1L -> {
                    if (offset + 16 > bytes.size) return null
                    headerSize = 16
                    bytes.uint64Be(offset + 8)
                }
                else -> size32
            }
            if (type == "mdat") return (offset + headerSize).takeIf { it < bytes.size }
            if (size < headerSize || size > Int.MAX_VALUE) return null
            offset += size.toInt()
        }
        return null
    }

    private data class WireField(val wireType: Int, val bytes: ByteArray? = null)

    private fun fields(bytes: ByteArray): Map<Int, List<WireField>> {
        val result = linkedMapOf<Int, MutableList<WireField>>()
        var position = 0
        while (position < bytes.size) {
            val tag = readVarint(bytes, position) ?: break
            position = tag.second
            val fieldNumber = (tag.first ushr 3).toInt()
            val wireType = (tag.first and 7).toInt()
            if (fieldNumber == 0) break
            val field = when (wireType) {
                0 -> {
                    val value = readVarint(bytes, position) ?: break
                    position = value.second
                    WireField(wireType)
                }
                1 -> {
                    if (position + 8 > bytes.size) break
                    WireField(wireType, bytes.copyOfRange(position, position + 8)).also { position += 8 }
                }
                2 -> {
                    val length = readVarint(bytes, position) ?: break
                    position = length.second
                    if (length.first < 0 || length.first > Int.MAX_VALUE || position + length.first > bytes.size) break
                    val end = position + length.first.toInt()
                    WireField(wireType, bytes.copyOfRange(position, end)).also { position = end }
                }
                5 -> {
                    if (position + 4 > bytes.size) break
                    WireField(wireType, bytes.copyOfRange(position, position + 4)).also { position += 4 }
                }
                else -> break
            }
            result.getOrPut(fieldNumber) { mutableListOf() } += field
        }
        return result
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var position = start
        while (position < bytes.size && shift <= 63) {
            val byte = bytes[position++].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return value to position
            shift += 7
        }
        return null
    }

    private fun ByteArray.floatLe(): Float = ByteBuffer.wrap(this)
        .order(ByteOrder.LITTLE_ENDIAN)
        .float

    private fun ByteArray.floatsLe(): FloatArray = ByteBuffer.wrap(this)
        .order(ByteOrder.LITTLE_ENDIAN)
        .let { buffer -> FloatArray(size / 4) { buffer.float } }

    private fun ByteArray.uint32Be(offset: Int): Long =
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)

    private fun ByteArray.uint64Be(offset: Int): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or (this[offset + it].toLong() and 0xff) }
        return value
    }
}

/** Fetches only the head of an LRF and caches its per-camera calibration without blocking playback. */
object DjmdCalibrationLoader {
    private const val HEADER_BYTES = 16 * 1024
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "osmodule-djmd-calibration").apply { isDaemon = true }
    }
    private val cache = ConcurrentHashMap<String, PanoramaCalibration>()

    fun load(candidates: List<String>, callback: (PanoramaCalibration?) -> Unit) {
        val key = candidates.firstNotNullOfOrNull(::cacheKey)
        if (key != null) cache[key]?.let { cached -> callback(cached); return }
        executor.execute {
            val calibration = candidates.firstNotNullOfOrNull { url ->
                fetchHeader(url)?.let(DjmdCalibrationParser::parseFileHeader)
            }
            if (calibration != null && key != null) cache[key] = calibration
            callback(calibration)
        }
    }

    private fun fetchHeader(url: String): ByteArray? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 2_000
            useCaches = false
            setRequestProperty("Range", "bytes=0-${HEADER_BYTES - 1}")
            setRequestProperty("Connection", "close")
        }
        try {
            if (connection.responseCode !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                return null
            }
            BufferedInputStream(connection.inputStream).use { input ->
                val output = ByteArray(HEADER_BYTES)
                var count = 0
                while (count < output.size) {
                    val read = input.read(output, count, output.size - count)
                    if (read < 0) break
                    count += read
                }
                output.copyOf(count)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun cacheKey(url: String): String? = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}:${if (uri.port >= 0) uri.port else 80}"
    }.getOrNull()
}
