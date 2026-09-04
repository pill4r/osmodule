package dev.pillar.osmodule.pocket4p

/** Scale applied after TextureView's buffer-to-view stretch to keep the entire raster visible. */
internal object PocketPreviewLayout {
    data class Scale(val x: Float, val y: Float)

    fun aspectFit(
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): Scale {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return Scale(1f, 1f)
        }
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()
        val fit = minOf(width / videoWidth, height / videoHeight)
        return Scale(
            x = videoWidth * fit / width,
            y = videoHeight * fit / height,
        )
    }
}
