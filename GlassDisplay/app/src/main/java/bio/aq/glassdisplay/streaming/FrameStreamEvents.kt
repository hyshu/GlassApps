package bio.aq.glassdisplay.streaming

import bio.aq.glassdisplay.protocol.HostCommand
import bio.aq.glassdisplay.protocol.StreamStats
import bio.aq.glassdisplay.protocol.Transport

interface FrameServerListener :
    StreamStatusSink,
    FrameStreamSink,
    HostStatusSink,
    HostCommandSource,
    TransportObserver

interface StreamStatusSink {
    fun onStatusChanged(title: String, detail: String)
}

interface FrameStreamSink {
    fun onFrameReceived(
        sourceId: String,
        transport: Transport,
        width: Int,
        height: Int,
        packedFrame: ByteArray,
        stats: StreamStats
    )

    fun onFrameSourceDisconnected(sourceId: String) = Unit

    fun shouldAcceptFrame(transport: Transport): Boolean = true

    fun shouldShowStreamError(transport: Transport): Boolean = shouldAcceptFrame(transport)
}

interface HostStatusSink {
    fun onHostStatus(title: String, detail: String) = Unit
}

interface HostCommandSource {
    fun consumeHostCommand(transport: Transport): HostCommand? = null
}

interface TransportObserver {
    fun onTransportConnected(transport: Transport) = Unit

    fun onTransportDisconnected(transport: Transport) = Unit
}
