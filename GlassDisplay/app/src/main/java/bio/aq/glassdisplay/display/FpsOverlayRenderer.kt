package bio.aq.glassdisplay.display

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

class FpsOverlayRenderer(density: Float) {
    private val padding = 6f * density
    private val margin = 8f * density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.RIGHT
        textSize = 14f * density
    }
    private val backgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
    }
    private val backgroundRect = RectF()

    fun draw(canvas: Canvas, viewWidth: Int, text: String) {
        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.fontMetrics.run { bottom - top }
        val right = viewWidth - margin
        val top = margin
        backgroundRect.set(
            right - textWidth - (padding * 2f),
            top,
            right,
            top + textHeight + (padding * 2f)
        )
        canvas.drawRect(backgroundRect, backgroundPaint)
        canvas.drawText(
            text,
            right - padding,
            top + padding - textPaint.fontMetrics.top,
            textPaint
        )
    }
}
