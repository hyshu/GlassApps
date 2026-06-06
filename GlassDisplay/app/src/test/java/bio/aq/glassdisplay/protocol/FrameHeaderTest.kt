package bio.aq.glassdisplay.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameHeaderTest {
    @Test
    fun parse_readsSwiftSenderFrameHeaderGoldenBytes() {
        val headerBytes = bytesOf(
            0x52, 0x47, 0x44, 0x31,
            0x01,
            0x07,
            0x01, 0xe0,
            0x01, 0x40,
            0x00, 0x00, 0x00, 0x20,
            0x00, 0x00, 0x00, 0x2a
        )

        val header = FrameHeader.parse(ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN))

        assertEquals(
            WireProtocol.Flags.AES_GCM or WireProtocol.Flags.DEFLATE or WireProtocol.Flags.DELTA,
            header.flags
        )
        assertEquals(FrameSize(width = 480, height = 320), header.size)
        assertEquals(32, header.payloadLength)
        assertEquals(42, header.frameId)
        assertArrayEquals(headerBytes, header.bytes)
    }

    @Test
    fun parse_readsBigEndianHeader() {
        val headerBytes = makeHeaderBytes(
            flags = WireProtocol.Flags.AES_GCM,
            width = 480,
            height = 320,
            payloadLength = 128,
            frameId = 42
        )

        val header = FrameHeader.parse(ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN))

        assertEquals(WireProtocol.Flags.AES_GCM, header.flags)
        assertEquals(FrameSize(width = 480, height = 320), header.size)
        assertEquals(128, header.payloadLength)
        assertEquals(42, header.frameId)
        assertEquals(76_800, header.packedByteCount)
        assertArrayEquals(headerBytes, header.bytes)
    }

    @Test
    fun parse_rejectsUnsupportedFlags() {
        val headerBytes = makeHeaderBytes(
            flags = WireProtocol.Flags.AES_GCM or 0x80,
            width = 480,
            height = 320,
            payloadLength = 128,
            frameId = 42
        )

        assertThrows(IOException::class.java) {
            FrameHeader.parse(ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN))
        }
    }

    @Test
    fun parse_rejectsFrameThatExceedsReceiverPayloadLimit() {
        val headerBytes = makeHeaderBytes(
            flags = WireProtocol.Flags.AES_GCM,
            width = 4_096,
            height = 4_096,
            payloadLength = 128,
            frameId = 42
        )

        assertThrows(IOException::class.java) {
            FrameHeader.parse(ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN))
        }
    }

    private fun makeHeaderBytes(
        flags: Int,
        width: Int,
        height: Int,
        payloadLength: Int,
        frameId: Int
    ): ByteArray =
        ByteBuffer.allocate(WireProtocol.Frame.HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(WireProtocol.Frame.MAGIC)
            .put(WireProtocol.VERSION.toByte())
            .put(flags.toByte())
            .putShort(width.toShort())
            .putShort(height.toShort())
            .putInt(payloadLength)
            .putInt(frameId)
            .array()

    private fun bytesOf(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
