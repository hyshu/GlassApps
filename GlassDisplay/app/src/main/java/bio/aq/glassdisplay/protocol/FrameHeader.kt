package bio.aq.glassdisplay.protocol

import java.io.IOException
import java.nio.ByteBuffer

data class FrameHeader(
    val flags: Int,
    val size: FrameSize,
    val payloadLength: Int,
    val frameId: Int,
    val bytes: ByteArray
) {
    val packedByteCount: Int
        get() = size.packedByteCount

    fun hasFlag(flag: Int): Boolean = (flags and flag) != 0

    companion object {
        @Throws(IOException::class)
        fun parse(buffer: ByteBuffer): FrameHeader {
            val magic = buffer.getInt(0)
            if (magic != WireProtocol.Frame.MAGIC) {
                throw IOException("Unexpected frame header: 0x${magic.toUInt().toString(16)}")
            }

            val version = buffer.get(4).toInt() and 0xFF
            if (version != WireProtocol.VERSION) {
                throw IOException("Unsupported protocol version: $version")
            }

            val flags = buffer.get(5).toInt() and 0xFF
            if ((flags and WireProtocol.Flags.SUPPORTED_FRAME.inv()) != 0) {
                throw IOException("Unsupported frame flags: 0x${flags.toString(16)}")
            }
            if ((flags and WireProtocol.Flags.AES_GCM) == 0) {
                throw IOException("Encrypted frame required.")
            }

            val width = buffer.getShort(6).toInt() and 0xFFFF
            val height = buffer.getShort(8).toInt() and 0xFFFF
            val payloadLength = buffer.getInt(10)
            val frameId = buffer.getInt(14)

            if (width <= 0 || height <= 0) {
                throw IOException("Invalid frame size: ${width}x$height")
            }
            if (payloadLength <= 0 || payloadLength > WireProtocol.Frame.MAX_PAYLOAD_BYTES) {
                throw IOException("Invalid payload size: $payloadLength")
            }

            val frameSize = FrameSize(width = width, height = height)
            if (frameSize.packedByteCount > WireProtocol.Frame.MAX_CLEAR_PAYLOAD_BYTES) {
                throw IOException("Frame size ${width}x$height is too large.")
            }

            val bytes = ByteArray(WireProtocol.Frame.HEADER_BYTES)
            System.arraycopy(buffer.array(), 0, bytes, 0, WireProtocol.Frame.HEADER_BYTES)

            return FrameHeader(
                flags = flags,
                size = frameSize,
                payloadLength = payloadLength,
                frameId = frameId,
                bytes = bytes
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameHeader) return false

        return flags == other.flags &&
            size == other.size &&
            payloadLength == other.payloadLength &&
            frameId == other.frameId &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = flags
        result = 31 * result + size.hashCode()
        result = 31 * result + payloadLength
        result = 31 * result + frameId
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
