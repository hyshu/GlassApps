package bio.aq.glassdisplay.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameProtocolTest {
    @Test
    fun protocolConstants_matchSwiftSenderWireValues() {
        assertEquals(0x52474431, WireProtocol.Frame.MAGIC)
        assertEquals(0x52474131, WireProtocol.Ack.MAGIC)
        assertEquals(0x52474331, WireProtocol.Command.MAGIC)
        assertEquals(1, WireProtocol.VERSION)
        assertEquals(0x01, WireProtocol.Flags.DEFLATE)
        assertEquals(0x02, WireProtocol.Flags.AES_GCM)
        assertEquals(0x04, WireProtocol.Flags.DELTA)
        assertEquals(0x08, WireProtocol.Flags.HOST_STATUS)
        assertEquals(18, WireProtocol.Frame.HEADER_BYTES)
        assertEquals(8, WireProtocol.Command.HEADER_BYTES)
        assertEquals(4, WireProtocol.Command.PAYLOAD_BYTES)
        assertEquals(12, WireProtocol.AesGcm.NONCE_BYTES)
        assertEquals(16, WireProtocol.AesGcm.TAG_BYTES)
        assertEquals(1_048_576, WireProtocol.Frame.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun makeCommandHeader_matchesSwiftSenderGoldenBytes() {
        assertArrayEquals(
            bytesOf(
                0x52, 0x47, 0x43, 0x31,
                0x01,
                0x02,
                0x00, 0x20
            ),
            FrameProtocol.makeCommandHeader()
        )
    }

    @Test
    fun intToBytes_writesBigEndian() {
        assertArrayEquals(
            byteArrayOf(0x52, 0x47, 0x41, 0x31),
            FrameProtocol.intToBytes(WireProtocol.Ack.MAGIC)
        )
    }

    @Test
    fun makeEncryptedCommandResponse_roundTripsCommandPayload() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(WireProtocol.AesGcm.NONCE_BYTES) { (it + 1).toByte() }
        val command = HostCommand.Resolution480x320

        val response = FrameProtocol.makeEncryptedCommandResponse(
            commandMagic = command.ackMagic,
            streamKey = key,
            nonce = nonce
        )

        assertEquals(
            WireProtocol.Command.HEADER_BYTES + WireProtocol.Command.ENCRYPTED_PAYLOAD_BYTES,
            response.size
        )

        val header = response.copyOfRange(0, WireProtocol.Command.HEADER_BYTES)
        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        assertEquals(WireProtocol.Command.MAGIC, headerBuffer.int)
        assertEquals(WireProtocol.VERSION, headerBuffer.get().toInt())
        assertEquals(WireProtocol.Flags.AES_GCM, headerBuffer.get().toInt())
        assertEquals(
            WireProtocol.Command.ENCRYPTED_PAYLOAD_BYTES,
            headerBuffer.short.toInt() and 0xFFFF
        )

        val payload = response.copyOfRange(WireProtocol.Command.HEADER_BYTES, response.size)
        val decrypted = FrameProtocol.decryptAesGcmPayload(
            secretKey = SecretKeySpec(key, "AES"),
            authenticatedData = header,
            payload = payload,
            tooShortMessage = "too short",
            authFailedMessage = "auth failed"
        )

        assertArrayEquals(FrameProtocol.intToBytes(command.ackMagic), decrypted)
    }

    @Test
    fun makeEncryptedCommandResponse_matchesSwiftSenderGoldenBytes() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(WireProtocol.AesGcm.NONCE_BYTES) { (it + 1).toByte() }

        val response = FrameProtocol.makeEncryptedCommandResponse(
            commandMagic = HostCommand.Resolution480x320.ackMagic,
            streamKey = key,
            nonce = nonce
        )

        assertArrayEquals(
            bytesOf(
                0x52, 0x47, 0x43, 0x31, 0x01, 0x02, 0x00, 0x20,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0a, 0x0b, 0x0c,
                0x57, 0xad, 0x16, 0xe4,
                0x46, 0x9e, 0xe3, 0xa9, 0x68, 0x12, 0xe5, 0xcc,
                0xf7, 0x4e, 0x3b, 0x66, 0x51, 0x69, 0x85, 0x5e
            ),
            response
        )
    }

    @Test
    fun decryptAesGcmPayload_rejectsTamperedAuthenticatedData() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(WireProtocol.AesGcm.NONCE_BYTES) { (it + 1).toByte() }
        val response = FrameProtocol.makeEncryptedCommandResponse(
            commandMagic = HostCommand.ResolutionOff.ackMagic,
            streamKey = key,
            nonce = nonce
        )
        val tamperedHeader = response.copyOfRange(0, WireProtocol.Command.HEADER_BYTES)
        tamperedHeader[0] = (tamperedHeader[0].toInt() xor 0x01).toByte()
        val payload = response.copyOfRange(WireProtocol.Command.HEADER_BYTES, response.size)

        assertThrows(IOException::class.java) {
            FrameProtocol.decryptAesGcmPayload(
                secretKey = SecretKeySpec(key, "AES"),
                authenticatedData = tamperedHeader,
                payload = payload,
                tooShortMessage = "too short",
                authFailedMessage = "auth failed"
            )
        }
    }

    private fun bytesOf(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
