package bio.aq.glassdisplay.protocol

enum class Transport {
    Tcp,
    Ble
}

enum class HostCommand(val spec: HostCommandSpec) {
    Resolution480x640(
        HostCommandSpec(
            ackMagic = WireProtocol.HostCommand.RESOLUTION_480X640_ACK,
            label = "480x640",
            expectedFrameSize = FrameSize.ROKID_PORTRAIT
        )
    ),
    Resolution480x320(
        HostCommandSpec(
            ackMagic = WireProtocol.HostCommand.RESOLUTION_480X320_ACK,
            label = "480x320",
            expectedFrameSize = FrameSize.ROKID_LANDSCAPE
        )
    ),
    ResolutionOff(
        HostCommandSpec(
            ackMagic = WireProtocol.HostCommand.RESOLUTION_OFF_ACK,
            label = "off",
            expectedFrameSize = null
        )
    );

    val ackMagic: Int
        get() = spec.ackMagic

    val label: String
        get() = spec.label

    val expectedFrameSize: FrameSize?
        get() = spec.expectedFrameSize
}

data class HostCommandSpec(
    val ackMagic: Int,
    val label: String,
    val expectedFrameSize: FrameSize?
)

data class FrameSize(
    val width: Int,
    val height: Int
) {
    val pixelCountLong: Long
        get() = width.toLong() * height.toLong()

    val packedByteCount: Int
        get() = ((pixelCountLong + 1L) / 2L).toInt()

    companion object {
        val ROKID_PORTRAIT = FrameSize(width = 480, height = 640)
        val ROKID_LANDSCAPE = FrameSize(width = 480, height = 320)
    }
}

data class StreamStats(
    val frameId: Int,
    val framesPerSecond: Double,
    val compressedBytes: Int,
    val packedBytes: Int
)
