package dev.pillar.osmodule.panorama.render

import kotlin.math.abs

/** Geometry and decode sizing shared by Base's media routing and the isolated panorama viewer. */
object PanoramaImageGeometry {
    private const val EQUIRECTANGULAR_ASPECT = 2f
    private const val ASPECT_TOLERANCE = 0.03f

    /** A stitched spherical panorama is 360° wide by 180° high, i.e. a 2:1 image. */
    fun isEquirectangular(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && abs(width.toFloat() / height - EQUIRECTANGULAR_ASPECT) <= ASPECT_TOLERANCE

    /** Power-of-two JPEG sample size that keeps both decoded dimensions within the GL texture limit. */
    fun decodeSampleSize(width: Int, height: Int, maxTextureSize: Int): Int {
        if (width <= 0 || height <= 0 || maxTextureSize <= 0) return 1
        var sample = 1
        while (width / sample > maxTextureSize || height / sample > maxTextureSize) sample *= 2
        return sample
    }
}
