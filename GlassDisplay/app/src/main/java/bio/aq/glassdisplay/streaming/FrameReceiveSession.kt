package bio.aq.glassdisplay.streaming

import bio.aq.glassdisplay.protocol.StreamStats
import bio.aq.glassdisplay.protocol.Transport
import java.io.IOException

class FrameReceiveSession(
    streamKeyProvider: () -> ByteArray,
    private val sourceId: String,
    private val transport: Transport,
    private val frameSink: FrameStreamSink,
    private val hostStatusSink: HostStatusSink,
    private val onFrameAccepted: (frameId: Int, acceptsFrames: Boolean) -> Unit = { _, _ -> }
) {
    private val parser = FrameStreamParser(
        streamKeyProvider,
        object : FrameStreamParser.Sink {
            override fun onFrame(
                width: Int,
                height: Int,
                packedFrame: ByteArray,
                stats: StreamStats
            ) {
                if (frameSink.shouldAcceptFrame(transport)) {
                    frameSink.onFrameReceived(
                        sourceId = sourceId,
                        transport = transport,
                        width = width,
                        height = height,
                        packedFrame = packedFrame,
                        stats = stats
                    )
                }
            }

            override fun onFrameAccepted(frameId: Int) {
                onFrameAccepted(frameId, frameSink.shouldAcceptFrame(transport))
            }

            override fun onHostStatus(title: String, detail: String) {
                hostStatusSink.onHostStatus(title, detail)
            }
        }
    )

    @Throws(IOException::class)
    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        parser.append(data, offset, length)
    }

    fun reset() {
        parser.reset()
    }
}
