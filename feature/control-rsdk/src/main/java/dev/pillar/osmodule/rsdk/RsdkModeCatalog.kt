package dev.pillar.osmodule.rsdk

import androidx.annotation.StringRes
import dev.pillar.osmodule.feature.control.rsdk.R
import dev.pillar.osmodule.modules.CameraRemoteMode
import dev.pillar.osmodule.modules.CameraRemoteState

/** The shooting-mode surface exposed by the Osmo 360 camera UI. */
internal object RsdkModeCatalog {
    val panorama360 = listOf(
        CameraRemoteMode.PANORAMIC_PHOTO,
        CameraRemoteMode.PANORAMIC_VIDEO,
        CameraRemoteMode.PANORAMIC_SUPER_NIGHT,
        CameraRemoteMode.SELFIE,
        CameraRemoteMode.VORTEX,
        CameraRemoteMode.PANORAMIC_HYPERLAPSE,
    )

    val singleLens = listOf(
        CameraRemoteMode.PHOTO,
        CameraRemoteMode.BOOST_VIDEO,
        CameraRemoteMode.SINGLE_LENS_SUPER_NIGHT,
        CameraRemoteMode.VIDEO,
    )

    fun isSingleLens(mode: CameraRemoteMode?): Boolean = mode in singleLens

    fun isPhoto(mode: CameraRemoteMode?): Boolean = mode in setOf(
        CameraRemoteMode.PHOTO,
        CameraRemoteMode.PANORAMIC_PHOTO,
        CameraRemoteMode.SELFIE,
    )

    fun currentMode(state: CameraRemoteState): CameraRemoteMode? {
        state.status?.mode?.let { return it }
        val wireName = state.modeLabel
            ?.substringBefore('·')
            ?.trim()
            ?.uppercase()
            ?.replace(Regex("[^A-Z0-9]+"), "_")
            ?.trim('_')
            ?: return null
        return when (wireName) {
            "VIDEO" -> CameraRemoteMode.VIDEO
            "PHOTO" -> CameraRemoteMode.PHOTO
            "PANO_VIDEO", "PANORAMIC_VIDEO" -> CameraRemoteMode.PANORAMIC_VIDEO
            "PANO_HYPERLAPSE", "PANORAMIC_HYPERLAPSE" -> CameraRemoteMode.PANORAMIC_HYPERLAPSE
            "SELFIE" -> CameraRemoteMode.SELFIE
            "PANO_PHOTO", "PANORAMIC_PHOTO" -> CameraRemoteMode.PANORAMIC_PHOTO
            "BOOST_VIDEO", "ULTRA_WIDE_VIDEO" -> CameraRemoteMode.BOOST_VIDEO
            "VORTEX", "FREEZE_FRAME" -> CameraRemoteMode.VORTEX
            "360_SUPERNIGHT", "360_SUPER_NIGHT", "PANORAMIC_SUPERNIGHT", "PANORAMIC_SUPER_NIGHT" ->
                CameraRemoteMode.PANORAMIC_SUPER_NIGHT
            "SINGLE_LENS_SUPERNIGHT", "SINGLE_LENS_SUPER_NIGHT" ->
                CameraRemoteMode.SINGLE_LENS_SUPER_NIGHT
            else -> null
        }
    }

    @StringRes
    fun labelRes(mode: CameraRemoteMode): Int = when (mode) {
        CameraRemoteMode.SLOW_MOTION -> R.string.rsdk_mode_slow_motion
        CameraRemoteMode.VIDEO -> R.string.rsdk_mode_video
        CameraRemoteMode.TIMELAPSE -> R.string.rsdk_mode_timelapse
        CameraRemoteMode.PHOTO -> R.string.rsdk_mode_photo
        CameraRemoteMode.HYPERLAPSE -> R.string.rsdk_mode_hyperlapse
        CameraRemoteMode.LIVE_STREAMING -> R.string.rsdk_mode_live_streaming
        CameraRemoteMode.UVC_LIVE_STREAMING -> R.string.rsdk_mode_uvc_live_streaming
        CameraRemoteMode.SUPER_NIGHT -> R.string.rsdk_mode_super_night
        CameraRemoteMode.SUBJECT_TRACKING -> R.string.rsdk_mode_subject_tracking
        CameraRemoteMode.PANORAMIC_VIDEO -> R.string.rsdk_mode_panoramic_video
        CameraRemoteMode.PANORAMIC_HYPERLAPSE -> R.string.rsdk_mode_panoramic_hyperlapse
        CameraRemoteMode.SELFIE -> R.string.rsdk_mode_selfie
        CameraRemoteMode.PANORAMIC_PHOTO -> R.string.rsdk_mode_panoramic_photo
        CameraRemoteMode.BOOST_VIDEO -> R.string.rsdk_mode_boost_video
        CameraRemoteMode.VORTEX -> R.string.rsdk_mode_vortex
        CameraRemoteMode.PANORAMIC_SUPER_NIGHT -> R.string.rsdk_mode_panoramic_super_night
        CameraRemoteMode.SINGLE_LENS_SUPER_NIGHT -> R.string.rsdk_mode_single_lens_super_night
    }
}
