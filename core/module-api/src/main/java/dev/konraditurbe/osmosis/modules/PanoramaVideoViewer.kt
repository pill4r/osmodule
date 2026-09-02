package dev.konraditurbe.osmosis.modules

import android.content.Context

data class PanoramaVideoRequest(
    val title: String,
    val deviceModel: String,
    /** Absolute HTTP URLs for lightweight dual-fisheye preview proxies, in preference order. */
    val streamCandidates: List<String>,
)

/** Optional in-process viewer for an Osmo 360 dual-fisheye preview stream. */
interface PanoramaVideoViewerLauncher {
    fun open(context: Context, request: PanoramaVideoRequest): Boolean
}
