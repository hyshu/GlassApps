package bio.aq.glassdisplay.streaming

import bio.aq.glassdisplay.protocol.FrameHeader
import bio.aq.glassdisplay.protocol.StreamStats
import bio.aq.glassdisplay.protocol.WireProtocol
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameStreamParser(
    streamKeyProvider: () -> ByteArray,
    private val sink: Sink
) {
    interface Sink {
        fun onFrame(
            width: Int,
            height: Int,
            packedFrame: ByteArray,
            stats: StreamStats
        )

        fun onFrameAccepted(frameId: Int) = Unit

        fun onHostStatus(title: String, detail: String) = Unit
    }

    private val buffer = ByteBuffer.allocate(WireProtocol.Frame.MAX_BUFFER_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
    private val packetDecoder = FramePacketDecoder(streamKeyProvider)

    fun reset() {
        buffer.clear()
        packetDecoder.reset()
    }

    @Throws(IOException::class)
    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length <= 0) return
        if (length > buffer.remaining()) {
            throw IOException("Frame buffer overflow: incoming=$length remaining=${buffer.remaining()}")
        }
        buffer.put(data, offset, length)
        drainComplete()
    }

    @Throws(IOException::class)
    private fun drainComplete() {
        while (true) {
            val available = buffer.position()
            if (available < WireProtocol.Frame.HEADER_BYTES) return

            val header = FrameHeader.parse(buffer)

            val totalBytes = WireProtocol.Frame.HEADER_BYTES + header.payloadLength
            if (available < totalBytes) return

            val payload = ByteArray(header.payloadLength)
            System.arraycopy(
                buffer.array(),
                WireProtocol.Frame.HEADER_BYTES,
                payload,
                0,
                header.payloadLength
            )

            val packet = packetDecoder.decode(header, payload)
            consumeBytes(totalBytes)

            when (packet) {
                is DecodedFramePacket.Frame -> {
                    sink.onFrame(
                        width = packet.width,
                        height = packet.height,
                        packedFrame = packet.packedFrame,
                        stats = packet.stats
                    )
                }
                is DecodedFramePacket.HostStatus -> {
                    sink.onHostStatus(packet.title, packet.detail)
                }
            }
            sink.onFrameAccepted(packet.frameId)
        }
    }

    private fun consumeBytes(count: Int) {
        val remaining = buffer.position() - count
        if (remaining > 0) {
            System.arraycopy(buffer.array(), count, buffer.array(), 0, remaining)
        }
        buffer.position(remaining)
    }
}
