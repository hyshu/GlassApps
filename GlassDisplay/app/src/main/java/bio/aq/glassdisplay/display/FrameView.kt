package bio.aq.glassdisplay.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import bio.aq.glassdisplay.protocol.Transport

class FrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val frameLock = Any()
    private val dividerPaint = Paint().apply {
        color = Color.rgb(48, 48, 48)
        strokeWidth = 1f
    }
    private val frameConverter = PackedFrameBitmapConverter()
    private val sourceStore = FrameSourceStore()
    private val sourceFrameRenderer = SourceFrameRenderer()
    private val fpsOverlayRenderer = FpsOverlayRenderer(resources.displayMetrics.density)
    private val viewportRect = RectF()

    private var displayMode = DisplayMode.Full
    private var fpsOverlayVisible = false
    private var fpsText = "0.0 FPS"

    fun submitPackedFrame(width: Int, height: Int, packedFrame: ByteArray) {
        submitPackedFrame(DEFAULT_SOURCE_ID, Transport.Tcp, width, height, packedFrame)
    }

    fun submitPackedFrame(sourceId: String, width: Int, height: Int, packedFrame: ByteArray) {
        submitPackedFrame(sourceId, Transport.Tcp, width, height, packedFrame)
    }

    fun submitPackedFrame(
        sourceId: String,
        transport: Transport,
        width: Int,
        height: Int,
        packedFrame: ByteArray
    ) {
        val pixelCount = width * height
        val expectedPackedBytes = (pixelCount + 1) / 2
        if (packedFrame.size != expectedPackedBytes || pixelCount <= 0) {
            return
        }

        synchronized(frameLock) {
            val sourceFrame = sourceStore.ensureSourceFrame(sourceId, transport, width, height)
            frameConverter.copyToPixels(packedFrame, pixelCount, sourceFrame.pixels)
            sourceFrame.bitmap.setPixels(sourceFrame.pixels, 0, width, 0, 0, width, height)
        }

        postInvalidateOnAnimation()
    }

    fun removeSource(sourceId: String) {
        synchronized(frameLock) {
            sourceStore.removeSource(sourceId)
        }

        postInvalidateOnAnimation()
    }

    fun hasFrames(): Boolean {
        return synchronized(frameLock) {
            !sourceStore.isEmpty()
        }
    }

    fun visibleSplitFrameCount(): Int {
        return synchronized(frameLock) {
            sourceStore.splitFrames().size
        }
    }

    fun advanceDisplayMode(): DisplayMode {
        displayMode = displayMode.next()
        postInvalidateOnAnimation()
        return displayMode
    }

    fun setDisplayMode(mode: DisplayMode): DisplayMode {
        displayMode = mode
        postInvalidateOnAnimation()
        return displayMode
    }

    fun toggleFpsOverlay(): Boolean {
        fpsOverlayVisible = !fpsOverlayVisible
        postInvalidateOnAnimation()
        return fpsOverlayVisible
    }

    fun submitFps(framesPerSecond: Double) {
        synchronized(frameLock) {
            fpsText = String.format("%.1f FPS", framesPerSecond)
        }
        if (fpsOverlayVisible) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        synchronized(frameLock) {
            if (sourceStore.isEmpty()) return
            when (displayMode) {
                DisplayMode.Full -> {
                    val sourceFrame = sourceStore.currentFrame() ?: return
                    drawSourceFrame(canvas, sourceFrame, splitIndex = 0)
                }
                DisplayMode.Split -> {
                    sourceStore.splitFrames().forEachIndexed { index, sourceFrame ->
                        drawSourceFrame(canvas, sourceFrame, splitIndex = index)
                    }
                }
            }

            if (displayMode == DisplayMode.Split) {
                val dividerY = height * 0.5f
                canvas.drawLine(0f, dividerY, width.toFloat(), dividerY, dividerPaint)
            }

            if (fpsOverlayVisible) {
                fpsOverlayRenderer.draw(canvas, width, fpsText)
            }
        }
    }

    private fun drawSourceFrame(canvas: Canvas, sourceFrame: SourceFrame, splitIndex: Int) {
        FrameViewportCalculator.setViewport(
            output = viewportRect,
            displayMode = displayMode,
            viewWidth = width,
            viewHeight = height,
            splitIndex = splitIndex
        )
        sourceFrameRenderer.draw(canvas, sourceFrame, viewportRect)
    }

    enum class DisplayMode {
        Full,
        Split;

        fun next(): DisplayMode {
            return when (this) {
                Full -> Split
                Split -> Full
            }
        }
    }

    companion object {
        private const val DEFAULT_SOURCE_ID = "default"
    }
}
