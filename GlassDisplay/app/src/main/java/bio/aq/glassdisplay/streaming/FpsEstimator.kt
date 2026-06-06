package bio.aq.glassdisplay.streaming

import android.os.SystemClock

class FpsEstimator {
    private var lastFrameTimestampNs = 0L
    private var smoothedFps = 0.0

    fun reset() {
        lastFrameTimestampNs = 0L
        smoothedFps = 0.0
    }

    fun update(): Double {
        val now = SystemClock.elapsedRealtimeNanos()
        val previous = lastFrameTimestampNs
        lastFrameTimestampNs = now

        if (previous == 0L) {
            return 0.0
        }

        val instantFps = 1_000_000_000.0 / (now - previous).toDouble()
        smoothedFps = if (smoothedFps == 0.0) {
            instantFps
        } else {
            (smoothedFps * 0.85) + (instantFps * 0.15)
        }
        return smoothedFps
    }
}
