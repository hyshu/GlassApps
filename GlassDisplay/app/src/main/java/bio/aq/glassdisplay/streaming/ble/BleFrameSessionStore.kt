package bio.aq.glassdisplay.streaming.ble

import bio.aq.glassdisplay.protocol.Transport
import bio.aq.glassdisplay.streaming.FrameReceiveSession
import bio.aq.glassdisplay.streaming.FrameStreamSink
import bio.aq.glassdisplay.streaming.HostStatusSink
import bio.aq.glassdisplay.streaming.StreamKeyStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class BleFrameSessionStore(
    private val streamKeyStore: StreamKeyStore,
    private val frameSink: FrameStreamSink,
    private val hostStatusSink: HostStatusSink
) {
    private val sessions = ConcurrentHashMap<String, FrameReceiveSession>()
    private val hostIdentities = ConcurrentHashMap<String, ByteArray>()

    fun connect(address: String) {
        hostIdentities.remove(address)
        sessions.remove(address)
    }

    @Throws(IOException::class)
    fun authenticateHost(address: String, hostIdentity: ByteArray) {
        val identity = hostIdentity.copyOf()
        hostIdentities.remove(address)
        sessions.remove(address)
        streamKeyStore.requireStreamKeyForHost(identity)
        hostIdentities[address] = identity
        sessions[address] = FrameReceiveSession(
            streamKeyProvider = { streamKeyStore.requireStreamKeyForHost(identity) },
            sourceId = sourceIdForAddress(address),
            transport = Transport.Ble,
            frameSink = frameSink,
            hostStatusSink = hostStatusSink
        )
    }

    fun disconnect(address: String) {
        hostIdentities.remove(address)
        sessions.remove(address)
    }

    fun session(address: String?): FrameReceiveSession? {
        if (address == null) {
            return null
        }
        return sessions[address]
    }

    fun contains(address: String): Boolean = sessions.containsKey(address)

    fun isEmpty(): Boolean = sessions.isEmpty()

    fun connectedAddresses(): Set<String> = sessions.keys.toSet()

    @Throws(IOException::class)
    fun streamKeyForAddress(address: String): ByteArray {
        val identity = hostIdentities[address]
            ?: throw IOException("BLE host identity is not set.")
        return streamKeyStore.requireStreamKeyForHost(identity)
    }

    fun clear() {
        hostIdentities.clear()
        sessions.clear()
    }

    fun sourceIdForAddress(address: String): String = "$SOURCE_ID_PREFIX$address"

    companion object {
        private const val SOURCE_ID_PREFIX = "ble:"
    }
}
