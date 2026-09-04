package dev.pillar.osmodule.modules

import android.content.Context
import android.net.Network

object PanoramaSourceKind {
    const val DUAL_FISHEYE_VIDEO = "dual-fisheye-video"
    const val EQUIRECTANGULAR_IMAGE = "equirectangular-image"

    fun isSupported(value: String): Boolean =
        value == DUAL_FISHEYE_VIDEO || value == EQUIRECTANGULAR_IMAGE
}

data class PanoramaVideoRequest(
    val title: String,
    val deviceModel: String,
    /** Absolute HTTP URLs for preview sources, in preference order. */
    val streamCandidates: List<String>,
    /** Camera network kept alive by Base and shared with the isolated plugin process. */
    val network: Network? = null,
    /** Projection/source layout; defaults to the original video-only contract for compatibility. */
    val sourceKind: String = PanoramaSourceKind.DUAL_FISHEYE_VIDEO,
)

/** Optional viewer for Osmo 360 dual-fisheye video and stitched equirectangular photos. */
interface PanoramaVideoViewerLauncher {
    fun isAvailable(context: Context): Boolean = true
    fun open(context: Context, request: PanoramaVideoRequest): Boolean
}
