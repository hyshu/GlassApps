package bio.aq.glassdisplay.streaming

import java.io.IOException

class DeltaFrameDecoder {
    private var previousFrame: ByteArray? = null

    fun reset() {
        previousFrame = null
    }

    @Throws(IOException::class)
    fun decode(rawBytes: ByteArray, isDelta: Boolean): ByteArray {
        val packedFrame = if (isDelta) {
            val previous = previousFrame
                ?: throw IOException("Delta frame received before a keyframe.")
            if (previous.size != rawBytes.size) {
                throw IOException("Delta frame size mismatch.")
            }
            ByteArray(rawBytes.size) { index ->
                (rawBytes[index].toInt() xor previous[index].toInt()).toByte()
            }
        } else {
            rawBytes
        }

        previousFrame = packedFrame
        return packedFrame
    }
}
