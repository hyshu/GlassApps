package bio.aq.glassdisplay.display

import android.graphics.RectF

object FrameViewportCalculator {
    fun setViewport(
        output: RectF,
        displayMode: FrameView.DisplayMode,
        viewWidth: Int,
        viewHeight: Int,
        splitIndex: Int
    ) {
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()
        val halfHeight = height * 0.5f
        when (displayMode) {
            FrameView.DisplayMode.Full -> output.set(0f, 0f, width, height)
            FrameView.DisplayMode.Split -> if (splitIndex == 0) {
                output.set(0f, 0f, width, halfHeight)
            } else {
                output.set(0f, halfHeight, width, height)
            }
        }
    }
}
