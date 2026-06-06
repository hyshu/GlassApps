package bio.aq.glassdisplay.display

import android.graphics.Color

class PackedFrameBitmapConverter {
    private val grayscaleLookup = IntArray(16) { shade ->
        val channel = shade * 17
        Color.argb(255, channel, channel, channel)
    }

    fun copyToPixels(packedFrame: ByteArray, pixelCount: Int, outputPixels: IntArray) {
        var sourceIndex = 0
        var pixelIndex = 0
        while (pixelIndex < pixelCount) {
            val packed = packedFrame[sourceIndex].toInt() and 0xFF
            sourceIndex += 1

            outputPixels[pixelIndex] = grayscaleLookup[(packed ushr 4) and 0x0F]
            pixelIndex += 1

            if (pixelIndex < pixelCount) {
                outputPixels[pixelIndex] = grayscaleLookup[packed and 0x0F]
                pixelIndex += 1
            }
        }
    }
}
