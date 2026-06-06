package bio.aq.glassdisplay.streaming

import bio.aq.glassdisplay.protocol.FrameHeader
import bio.aq.glassdisplay.protocol.FrameProtocol
import bio.aq.glassdisplay.protocol.WireProtocol
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.spec.SecretKeySpec

class FramePayloadDecoder(
    private val streamKeyProvider: () -> ByteArray
) {
    private val inflater = Inflater(true)
    private val deltaDecoder = DeltaFrameDecoder()
    private var secretKey: SecretKeySpec? = null

    fun reset() {
        deltaDecoder.reset()
    }

    @Throws(IOException::class)
    fun decrypt(header: FrameHeader, payload: ByteArray): ByteArray =
        FrameProtocol.decryptAesGcmPayload(
            secretKey = requireSecretKey(),
            authenticatedData = header.bytes,
            payload = payload,
            tooShortMessage = "Encrypted payload is too short.",
            authFailedMessage = "Encrypted frame authentication failed."
        )

    @Throws(IOException::class)
    fun decodePackedFrame(header: FrameHeader, framePayload: ByteArray): ByteArray {
        val packedBytes = header.packedByteCount
        val rawBytes = if (header.hasFlag(WireProtocol.Flags.DEFLATE)) {
            inflateFrame(framePayload, packedBytes)
        } else {
            if (framePayload.size != packedBytes) {
                throw IOException("Expected $packedBytes packed bytes, got ${framePayload.size}")
            }
            framePayload
        }

        return deltaDecoder.decode(
            rawBytes = rawBytes,
            isDelta = header.hasFlag(WireProtocol.Flags.DELTA)
        )
    }

    @Throws(IOException::class)
    private fun requireSecretKey(): SecretKeySpec {
        val existing = secretKey
        if (existing != null) {
            return existing
        }

        return SecretKeySpec(streamKeyProvider(), "AES").also {
            secretKey = it
        }
    }

    @Throws(IOException::class)
    private fun inflateFrame(payload: ByteArray, expectedBytes: Int): ByteArray {
        inflater.reset()
        inflater.setInput(payload)
        val output = ByteArray(expectedBytes)

        val inflated = try {
            inflater.inflate(output)
        } catch (exception: DataFormatException) {
            throw IOException("Compressed frame is malformed.", exception)
        }

        if (inflated != expectedBytes || !inflater.finished()) {
            throw IOException("Compressed frame size mismatch.")
        }

        return output
    }
}
