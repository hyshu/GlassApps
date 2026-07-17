package bio.aq.glassdisplay.streaming

import bio.aq.glassdisplay.protocol.StreamStats
import bio.aq.glassdisplay.protocol.WireProtocol
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameStreamParserTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun append_parsesPacketSplitAcrossSingleByteWrites() {
        val sink = RecordingSink()
        val parser = parser(sink)
        val packet = makeFramePacket(frameId = 7, packedFrame = byteArrayOf(0x12, 0x34))

        packet.forEach { byte -> parser.append(byteArrayOf(byte)) }

        assertEquals(listOf(7), sink.acceptedFrameIds)
        assertEquals(1, sink.frames.size)
        assertArrayEquals(byteArrayOf(0x12, 0x34), sink.frames.single().packedFrame)
    }

    @Test
    fun append_drainsMultiplePacketsFromOneWrite() {
        val sink = RecordingSink()
        val parser = parser(sink)
        val packets = makeFramePacket(1, byteArrayOf(0x12, 0x34)) +
            makeFramePacket(2, byteArrayOf(0x56, 0x78))

        parser.append(packets)

        assertEquals(listOf(1, 2), sink.acceptedFrameIds)
        assertArrayEquals(byteArrayOf(0x56, 0x78), sink.frames.last().packedFrame)
    }

    @Test
    fun reset_recoversAfterAuthenticationFailure() {
        val sink = RecordingSink()
        val parser = parser(sink)
        val tampered = makeFramePacket(1, byteArrayOf(0x12, 0x34)).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }

        assertThrows(IOException::class.java) { parser.append(tampered) }
        parser.reset()
        parser.append(makeFramePacket(2, byteArrayOf(0x56, 0x78)))

        assertEquals(listOf(2), sink.acceptedFrameIds)
    }

    @Test
    fun append_deliversAuthenticatedHostStatus() {
        val sink = RecordingSink()
        val parser = parser(sink)

        parser.append(makePacket(9, 1, 1, WireProtocol.Flags.HOST_STATUS, "Ready\nStreaming".toByteArray()))

        assertEquals(listOf(9), sink.acceptedFrameIds)
        assertEquals(listOf("Ready" to "Streaming"), sink.statuses)
    }

    private fun makeFramePacket(frameId: Int, packedFrame: ByteArray): ByteArray =
        makePacket(frameId, width = 2, height = 2, extraFlags = 0, clearPayload = packedFrame)

    private fun parser(sink: RecordingSink): FrameStreamParser =
        FrameStreamParser({ key }, sink, System::nanoTime)

    private fun makePacket(
        frameId: Int,
        width: Int,
        height: Int,
        extraFlags: Int,
        clearPayload: ByteArray
    ): ByteArray {
        val nonce = ByteArray(WireProtocol.AesGcm.NONCE_BYTES) { (frameId + it).toByte() }
        val payloadLength = nonce.size + clearPayload.size + WireProtocol.AesGcm.TAG_BYTES
        val header = ByteBuffer.allocate(WireProtocol.Frame.HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(WireProtocol.Frame.MAGIC)
            .put(WireProtocol.VERSION.toByte())
            .put((WireProtocol.Flags.AES_GCM or extraFlags).toByte())
            .putShort(width.toShort())
            .putShort(height.toShort())
            .putInt(payloadLength)
            .putInt(frameId)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(WireProtocol.AesGcm.TAG_BITS, nonce)
        )
        cipher.updateAAD(header)
        return header + nonce + cipher.doFinal(clearPayload)
    }

    private class RecordingSink : FrameStreamParser.Sink {
        val frames = mutableListOf<ReceivedFrame>()
        val acceptedFrameIds = mutableListOf<Int>()
        val statuses = mutableListOf<Pair<String, String>>()

        override fun onFrame(width: Int, height: Int, packedFrame: ByteArray, stats: StreamStats) {
            frames += ReceivedFrame(width, height, packedFrame)
        }

        override fun onFrameAccepted(frameId: Int) {
            acceptedFrameIds += frameId
        }

        override fun onHostStatus(title: String, detail: String) {
            statuses += title to detail
        }
    }

    private data class ReceivedFrame(val width: Int, val height: Int, val packedFrame: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is ReceivedFrame && width == other.width && height == other.height &&
                packedFrame.contentEquals(other.packedFrame)

        override fun hashCode(): Int = 31 * (31 * width + height) + packedFrame.contentHashCode()
    }
}
