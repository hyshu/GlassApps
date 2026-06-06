package bio.aq.glassdisplay.streaming

import bio.aq.glassdisplay.protocol.Transport
import java.util.concurrent.atomic.AtomicReference

class TransportPriorityTracker {
    private val activeTransport = AtomicReference<Transport?>()

    fun activeTransport(): Transport? = activeTransport.get()

    fun onTransportConnected(transport: Transport) {
        when (transport) {
            Transport.Tcp -> activeTransport.set(Transport.Tcp)
            Transport.Ble -> activeTransport.compareAndSet(null, Transport.Ble)
        }
    }

    fun onTransportDisconnected(transport: Transport): Boolean {
        return activeTransport.compareAndSet(transport, null)
    }

    fun shouldUse(transport: Transport): Boolean {
        val active = activeTransport.get()
        return active == null || active == transport
    }
}
