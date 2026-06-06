package bio.aq.glassdisplay.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object FrameProtocol {
    @Throws(IOException::class)
    fun decryptAesGcmPayload(
        secretKey: SecretKeySpec,
        authenticatedData: ByteArray,
        payload: ByteArray,
        tooShortMessage: String,
        authFailedMessage: String
    ): ByteArray {
        if (payload.size <= WireProtocol.AesGcm.NONCE_BYTES + WireProtocol.AesGcm.TAG_BYTES) {
            throw IOException(tooShortMessage)
        }

        val nonce = payload.copyOfRange(0, WireProtocol.AesGcm.NONCE_BYTES)
        val ciphertextAndTag = payload.copyOfRange(WireProtocol.AesGcm.NONCE_BYTES, payload.size)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(WireProtocol.AesGcm.TAG_BITS, nonce)
            )
            cipher.updateAAD(authenticatedData)
            cipher.doFinal(ciphertextAndTag)
        } catch (exception: GeneralSecurityException) {
            throw IOException(authFailedMessage, exception)
        }
    }

    fun makeEncryptedCommandResponse(
        commandMagic: Int,
        streamKey: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        require(nonce.size == WireProtocol.AesGcm.NONCE_BYTES) {
            "Command nonce must be ${WireProtocol.AesGcm.NONCE_BYTES} bytes."
        }

        val header = makeCommandHeader()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(streamKey, "AES")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            GCMParameterSpec(WireProtocol.AesGcm.TAG_BITS, nonce)
        )
        cipher.updateAAD(header)
        val encryptedPayload = cipher.doFinal(intToBytes(commandMagic))

        return ByteBuffer.allocate(
            WireProtocol.Command.HEADER_BYTES + nonce.size + encryptedPayload.size
        )
            .put(header)
            .put(nonce)
            .put(encryptedPayload)
            .array()
    }

    fun makeCommandHeader(): ByteArray =
        ByteBuffer.allocate(WireProtocol.Command.HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(WireProtocol.Command.MAGIC)
            .put(WireProtocol.VERSION.toByte())
            .put(WireProtocol.Flags.AES_GCM.toByte())
            .putShort(WireProtocol.Command.ENCRYPTED_PAYLOAD_BYTES.toShort())
            .array()

    fun intToBytes(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
}
