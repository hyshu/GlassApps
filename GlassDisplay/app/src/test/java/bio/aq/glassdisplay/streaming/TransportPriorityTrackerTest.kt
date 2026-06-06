package bio.aq.glassdisplay.streaming

import bio.aq.glassdisplay.protocol.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPriorityTrackerTest {
    @Test
    fun shouldUse_prefersTcpWhileTcpIsConnected() {
        val tracker = TransportPriorityTracker()

        tracker.onTransportConnected(Transport.Ble)
        assertTrue(tracker.shouldUse(Transport.Ble))

        tracker.onTransportConnected(Transport.Tcp)

        assertTrue(tracker.shouldUse(Transport.Tcp))
        assertFalse(tracker.shouldUse(Transport.Ble))
    }

    @Test
    fun shouldUse_allowsBleAgainAfterTcpDisconnects() {
        val tracker = TransportPriorityTracker()

        tracker.onTransportConnected(Transport.Ble)
        tracker.onTransportConnected(Transport.Tcp)
        tracker.onTransportDisconnected(Transport.Tcp)

        assertTrue(tracker.shouldUse(Transport.Ble))
    }
}
