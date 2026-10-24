package com.shilapi.xcertplay.media

/** Fits the negotiated CarPlay canvas inside the current window without changing its aspect ratio. */
data class CarPlayVideoLayout(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    companion object {
        /** Same geometry for video and touch; leaves an explicit strip for local vehicle data. */
        fun fitBelowHeader(canvasWidth: Int, canvasHeight: Int, viewWidth: Int, viewHeight: Int,
                           headerHeight: Int): CarPlayVideoLayout {
            val top = headerHeight.coerceIn(0, viewHeight.coerceAtLeast(0) / 3)
            val fitted = fit(canvasWidth, canvasHeight, viewWidth, viewHeight - top)
            return fitted.copy(top = fitted.top + top)
        }
        fun fit(canvasWidth: Int, canvasHeight: Int, viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
            if (canvasWidth <= 0 || canvasHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return CarPlayVideoLayout(0f, 0f, viewWidth.coerceAtLeast(0).toFloat(), viewHeight.coerceAtLeast(0).toFloat())
            }
            val scale = minOf(viewWidth.toFloat() / canvasWidth, viewHeight.toFloat() / canvasHeight)
            val width = canvasWidth * scale
            val height = canvasHeight * scale
            return CarPlayVideoLayout((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
        }
    }
}
