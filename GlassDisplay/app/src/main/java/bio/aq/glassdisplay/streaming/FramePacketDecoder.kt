package bio.aq.glassdisplay.streaming

import bio.aq.glassdisplay.protocol.FrameHeader
import bio.aq.glassdisplay.protocol.StreamStats
import bio.aq.glassdisplay.protocol.WireProtocol
import java.io.IOException

sealed class DecodedFramePacket {
    abstract val frameId: Int

    data class Frame(
        override val frameId: Int,
        val width: Int,
        val height: Int,
        val packedFrame: ByteArray,
        val stats: StreamStats
    ) : DecodedFramePacket() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false

            return frameId == other.frameId &&
                width == other.width &&
                height == other.height &&
                packedFrame.contentEquals(other.packedFrame) &&
                stats == other.stats
        }

        override fun hashCode(): Int {
            var result = frameId
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + packedFrame.contentHashCode()
            result = 31 * result + stats.hashCode()
            return result
        }
    }

    data class HostStatus(
        override val frameId: Int,
        val title: String,
        val detail: String
    ) : DecodedFramePacket()
}

data class HostStatusMessage(
    val title: String,
    val detail: String
)

class FramePacketDecoder(
    streamKeyProvider: () -> ByteArray
) {
    private val payloadDecoder = FramePayloadDecoder(streamKeyProvider)
    private val fpsEstimator = FpsEstimator()

    fun reset() {
        payloadDecoder.reset()
        fpsEstimator.reset()
    }

    @Throws(IOException::class)
    fun decode(header: FrameHeader, encryptedPayload: ByteArray): DecodedFramePacket {
        val payload = payloadDecoder.decrypt(header, encryptedPayload)
        if (header.hasFlag(WireProtocol.Flags.HOST_STATUS)) {
            val status = parseHostStatus(payload)
            return DecodedFramePacket.HostStatus(
                frameId = header.frameId,
                title = status.title,
                detail = status.detail
            )
        }

        val packedFrame = payloadDecoder.decodePackedFrame(header, payload)
        return DecodedFramePacket.Frame(
            frameId = header.frameId,
            width = header.size.width,
            height = header.size.height,
            packedFrame = packedFrame,
            stats = StreamStats(
                frameId = header.frameId,
                framesPerSecond = fpsEstimator.update(),
                compressedBytes = header.payloadLength,
                packedBytes = header.packedByteCount
            )
        )
    }

    private fun parseHostStatus(payload: ByteArray): HostStatusMessage {
        val text = payload.toString(Charsets.UTF_8)
        val splitAt = text.indexOf('\n')
        if (splitAt < 0) {
            return HostStatusMessage(title = text, detail = "")
        }
        return HostStatusMessage(
            title = text.substring(0, splitAt),
            detail = text.substring(splitAt + 1)
        )
    }
}
