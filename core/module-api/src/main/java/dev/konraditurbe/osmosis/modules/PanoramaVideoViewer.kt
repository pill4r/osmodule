package dev.konraditurbe.osmosis.modules

import android.content.Context
import android.net.Network

data class PanoramaVideoRequest(
    val title: String,
    val deviceModel: String,
    /** Absolute HTTP URLs for lightweight dual-fisheye preview proxies, in preference order. */
    val streamCandidates: List<String>,
    /** Camera network kept alive by Base and shared with the isolated plugin process. */
    val network: Network? = null,
)

/** Optional viewer for an Osmo 360 dual-fisheye preview stream. */
interface PanoramaVideoViewerLauncher {
    fun isAvailable(context: Context): Boolean = true
    fun open(context: Context, request: PanoramaVideoRequest): Boolean
}
