package bio.aq.glassdisplay.display

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

class SourceFrameRenderer {
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
    }
    private val destinationRect = RectF()

    fun draw(canvas: Canvas, sourceFrame: SourceFrame, viewport: RectF) {
        val widthScale = viewport.width() / sourceFrame.width.toFloat()
        val heightScale = viewport.height() / sourceFrame.height.toFloat()
        val scale = min(widthScale, heightScale)
        val drawWidth = sourceFrame.width * scale
        val drawHeight = sourceFrame.height * scale
        val left = viewport.left + ((viewport.width() - drawWidth) * 0.5f)
        val top = viewport.top + ((viewport.height() - drawHeight) * 0.5f)
        destinationRect.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(sourceFrame.bitmap, null, destinationRect, drawPaint)
    }
}
