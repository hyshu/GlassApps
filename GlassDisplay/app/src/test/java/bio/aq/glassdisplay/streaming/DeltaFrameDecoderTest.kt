package bio.aq.glassdisplay.streaming

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeltaFrameDecoderTest {
    @Test
    fun decode_appliesDeltaToPreviousFrame() {
        val decoder = DeltaFrameDecoder()

        assertArrayEquals(
            byteArrayOf(0x10, 0x20, 0x30),
            decoder.decode(byteArrayOf(0x10, 0x20, 0x30), isDelta = false)
        )

        assertArrayEquals(
            byteArrayOf(0x11, 0x22, 0x33),
            decoder.decode(byteArrayOf(0x01, 0x02, 0x03), isDelta = true)
        )
    }

    @Test
    fun decode_rejectsDeltaBeforeKeyframe() {
        val decoder = DeltaFrameDecoder()

        assertThrows(IOException::class.java) {
            decoder.decode(byteArrayOf(0x01), isDelta = true)
        }
    }

    @Test
    fun reset_requiresNextDeltaToHaveKeyframe() {
        val decoder = DeltaFrameDecoder()
        decoder.decode(byteArrayOf(0x10), isDelta = false)
        decoder.reset()

        assertThrows(IOException::class.java) {
            decoder.decode(byteArrayOf(0x01), isDelta = true)
        }
    }
}
