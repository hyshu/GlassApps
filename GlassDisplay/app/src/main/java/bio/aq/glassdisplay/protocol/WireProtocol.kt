package bio.aq.glassdisplay.protocol

// Keep these values in sync with host/sender/glass_display_sender.swift FrameProtocol.
object WireProtocol {
    const val VERSION = 1

    object Frame {
        const val MAGIC = 0x52474431
        const val HEADER_BYTES = 18
        const val MAX_PAYLOAD_BYTES = 1_048_576
        const val MAX_CLEAR_PAYLOAD_BYTES =
            MAX_PAYLOAD_BYTES - AesGcm.NONCE_BYTES - AesGcm.TAG_BYTES
        const val MAX_BUFFER_BYTES = MAX_PAYLOAD_BYTES + HEADER_BYTES
    }

    object Ack {
        const val MAGIC = 0x52474131
    }

    object Command {
        const val MAGIC = 0x52474331
        const val HEADER_BYTES = 8
        const val PAYLOAD_BYTES = 4
        const val ENCRYPTED_PAYLOAD_BYTES =
            AesGcm.NONCE_BYTES + PAYLOAD_BYTES + AesGcm.TAG_BYTES
    }

    object HostCommand {
        const val RESOLUTION_480X640_ACK = 0x52475031
        const val RESOLUTION_480X320_ACK = 0x52474C31
        const val RESOLUTION_OFF_ACK = 0x52474F31
    }

    object Flags {
        const val DEFLATE = 0x01
        const val AES_GCM = 0x02
        const val DELTA = 0x04
        const val HOST_STATUS = 0x08
        const val SUPPORTED_FRAME = DEFLATE or AES_GCM or DELTA or HOST_STATUS
    }

    object AesGcm {
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = TAG_BYTES * 8
    }
}
