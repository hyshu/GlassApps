package bio.aq.glassdisplay.streaming.ble

import bio.aq.glassdisplay.protocol.FrameProtocol
import bio.aq.glassdisplay.protocol.HostCommand
import bio.aq.glassdisplay.protocol.Transport
import bio.aq.glassdisplay.protocol.WireProtocol
import bio.aq.glassdisplay.streaming.HostCommandSource
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleHostCommandResponderTest {
    @Test
    fun responseForDevice_returnsPlainAckWhenNoCommandIsPending() {
        val responder = BleHostCommandResponder(
            commandSource = FakeCommandSource(),
            streamKeyProvider = { _ -> error("No stream key expected.") },
            secureRandom = FixedSecureRandom()
        )

        val response = responder.responseForDevice(
            address = "a",
            connectedAddresses = setOf("a")
        )

        assertNull(response.command)
        assertArrayEquals(FrameProtocol.intToBytes(WireProtocol.Ack.MAGIC), response.bytes)
    }

    @Test
    fun responseForDevice_fansOutCommandToConnectedDevices() {
        val commandSource = FakeCommandSource(HostCommand.Resolution480x320)
        val responder = BleHostCommandResponder(
            commandSource = commandSource,
            streamKeyProvider = { _ -> ByteArray(32) { it.toByte() } },
            secureRandom = FixedSecureRandom()
        )

        val first = responder.responseForDevice(
            address = "a",
            connectedAddresses = setOf("a", "b")
        )
        val second = responder.responseForDevice(
            address = "b",
            connectedAddresses = setOf("a", "b")
        )

        assertEquals(1, commandSource.consumeCount)
        assertEquals(HostCommand.Resolution480x320, first.command)
        assertEquals(HostCommand.Resolution480x320, second.command)
        assertEquals(
            WireProtocol.Command.HEADER_BYTES + WireProtocol.Command.ENCRYPTED_PAYLOAD_BYTES,
            first.bytes.size
        )
        assertEquals(
            WireProtocol.Command.HEADER_BYTES + WireProtocol.Command.ENCRYPTED_PAYLOAD_BYTES,
            second.bytes.size
        )
    }

    private class FakeCommandSource(
        private var nextCommand: HostCommand? = null
    ) : HostCommandSource {
        var consumeCount = 0
            private set

        override fun consumeHostCommand(transport: Transport): HostCommand? {
            consumeCount += 1
            return nextCommand.also {
                nextCommand = null
            }
        }
    }

    private class FixedSecureRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { index ->
                bytes[index] = (index + 1).toByte()
            }
        }
    }
}
