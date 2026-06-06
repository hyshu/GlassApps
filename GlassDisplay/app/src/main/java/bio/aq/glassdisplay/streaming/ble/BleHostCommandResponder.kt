package bio.aq.glassdisplay.streaming.ble

import bio.aq.glassdisplay.protocol.FrameProtocol
import bio.aq.glassdisplay.protocol.HostCommand
import bio.aq.glassdisplay.protocol.Transport
import bio.aq.glassdisplay.protocol.WireProtocol
import bio.aq.glassdisplay.streaming.HostCommandSource
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class BleHostCommandResponse(
    val command: HostCommand?,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleHostCommandResponse) return false

        return command == other.command && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = command?.hashCode() ?: 0
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

class BleHostCommandResponder(
    private val commandSource: HostCommandSource,
    private val streamKeyProvider: (address: String) -> ByteArray,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private val pendingCommands = ConcurrentHashMap<String, HostCommand>()

    fun responseForDevice(
        address: String,
        connectedAddresses: Set<String>
    ): BleHostCommandResponse {
        val command = pendingCommandForDevice(address, connectedAddresses)
        val response = makeCommandResponse(address, command)
        if (command != null) {
            pendingCommands.remove(address)
        }
        return BleHostCommandResponse(command = command, bytes = response)
    }

    fun remove(address: String) {
        pendingCommands.remove(address)
    }

    fun clear() {
        pendingCommands.clear()
    }

    private fun pendingCommandForDevice(
        address: String,
        connectedAddresses: Set<String>
    ): HostCommand? {
        pendingCommands[address]?.let { return it }

        val command = commandSource.consumeHostCommand(Transport.Ble) ?: return null
        if (connectedAddresses.isEmpty()) {
            return command
        }
        connectedAddresses.forEach { connectedAddress ->
            pendingCommands[connectedAddress] = command
        }
        return pendingCommands[address] ?: command
    }

    private fun makeCommandResponse(address: String, command: HostCommand?): ByteArray {
        if (command == null) {
            return FrameProtocol.intToBytes(WireProtocol.Ack.MAGIC)
        }

        val nonce = ByteArray(WireProtocol.AesGcm.NONCE_BYTES)
        secureRandom.nextBytes(nonce)

        return FrameProtocol.makeEncryptedCommandResponse(
            commandMagic = command.ackMagic,
            streamKey = streamKeyProvider(address),
            nonce = nonce
        )
    }
}
