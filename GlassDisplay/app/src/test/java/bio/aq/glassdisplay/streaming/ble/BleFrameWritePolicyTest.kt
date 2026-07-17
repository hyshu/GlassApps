package bio.aq.glassdisplay.streaming.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFrameWritePolicyTest {
    @Test
    fun acceptsOnlyImmediateWritesAtOffsetZero() {
        assertTrue(BleFrameWritePolicy.accepts(preparedWrite = false, offset = 0))
        assertFalse(BleFrameWritePolicy.accepts(preparedWrite = true, offset = 0))
        assertFalse(BleFrameWritePolicy.accepts(preparedWrite = false, offset = 1))
        assertFalse(BleFrameWritePolicy.accepts(preparedWrite = false, offset = -1))
    }
}
