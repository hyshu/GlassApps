package bio.aq.glassdisplay.streaming.tcp

import android.content.Context
import android.util.Log
import bio.aq.glassdisplay.protocol.Transport
import bio.aq.glassdisplay.protocol.WireProtocol
import bio.aq.glassdisplay.streaming.FrameReceiveSession
import bio.aq.glassdisplay.streaming.FrameServerListener
import bio.aq.glassdisplay.streaming.StreamKeyStore
import bio.aq.glassdisplay.streaming.StreamStatus
import bio.aq.glassdisplay.streaming.StreamStatusKind
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class FrameServer(
    context: Context,
    private val port: Int,
    private val listener: FrameServerListener
) {
    private val logTag = "GlassFrameServer"
    private val streamKeyStore = StreamKeyStore(context)

    private val running = AtomicBoolean(false)
    private val connectedClientCount = AtomicInteger(0)
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val wifiClientSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        workerThread = thread(
            start = true,
            isDaemon = true,
            name = "glass-frame-server"
        ) {
            runServerLoop()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        clientSockets.forEach { closeQuietly(it) }
        clientSockets.clear()
        wifiClientSockets.clear()
        closeQuietly(serverSocket)
        workerThread?.interrupt()
        workerThread = null
        serverSocket = null
    }

    private fun runServerLoop() {
        while (running.get()) {
            try {
                openServerSocket().use { server ->
                    serverSocket = server
                    Log.i(logTag, "Listening on tcp:$port")
                    listener.onStatusChanged(StreamStatus(
                        kind = StreamStatusKind.Waiting,
                        title = "Waiting for host",
                        detail = "Keep host/scripts/glass-stream.sh running. It will forward tcp:$port and connect automatically."
                    ))

                    while (running.get()) {
                        val socket = server.accept()
                        val requiresWifiAuthentication =
                            TcpClientPolicy.requiresAuthenticationTimeout(socket.inetAddress)
                        if (requiresWifiAuthentication && wifiClientSockets.size >= MAX_WIFI_CLIENTS) {
                            Log.w(
                                logTag,
                                "Rejecting Wi-Fi client ${socket.inetAddress?.hostAddress}: " +
                                    "already $MAX_WIFI_CLIENTS connected"
                            )
                            closeQuietly(socket)
                            continue
                        }

                        clientSockets.add(socket)
                        if (requiresWifiAuthentication) {
                            wifiClientSockets.add(socket)
                        }
                        thread(
                            start = true,
                            isDaemon = true,
                            name = "glass-frame-client-${socket.inetAddress?.hostAddress}:${socket.port}"
                        ) {
                            serveClient(socket)
                        }
                    }
                }
            } catch (exception: IOException) {
                if (!running.get()) {
                    break
                }

                Log.e(logTag, "Socket error", exception)
                listener.onStatusChanged(StreamStatus(
                    kind = StreamStatusKind.StreamError,
                    title = "Socket error",
                    detail = exception.message ?: "Unable to open stream socket."
                ))
                sleepQuietly(RETRY_DELAY_MS)
            } finally {
                serverSocket = null
            }
        }
    }

    private fun serveClient(socket: Socket) {
        val sourceId = "tcp:${socket.inetAddress?.hostAddress}:${socket.port}"
        val requiresWifiAuthentication =
            TcpClientPolicy.requiresAuthenticationTimeout(socket.inetAddress)
        val transportConnected = AtomicBoolean(false)
        Log.i(logTag, "Client connected from $sourceId")
        val announceConnected = {
            if (transportConnected.compareAndSet(false, true)) {
                if (connectedClientCount.incrementAndGet() == 1) {
                    listener.onTransportConnected(Transport.Tcp)
                }
                listener.onStatusChanged(StreamStatus(
                    kind = StreamStatusKind.Connected,
                    title = "Connected",
                    detail = "Streaming on tcp:$port."
                ))
            }
        }

        if (!requiresWifiAuthentication) {
            announceConnected()
        }

        try {
            handleClient(socket, sourceId, requiresWifiAuthentication, announceConnected)
        } catch (exception: IOException) {
            if (running.get()) {
                if (transportConnected.get()) {
                    Log.e(logTag, "Stream error", exception)
                    listener.onStatusChanged(StreamStatus(
                        kind = StreamStatusKind.StreamError,
                        title = "Stream error",
                        detail = exception.message ?: "Unable to read stream."
                    ))
                } else {
                    Log.w(logTag, "Unauthenticated Wi-Fi client disconnected: $sourceId")
                }
            }
        } finally {
            closeQuietly(socket)
            clientSockets.remove(socket)
            wifiClientSockets.remove(socket)
            listener.onFrameSourceDisconnected(sourceId)
            if (transportConnected.get() && connectedClientCount.decrementAndGet() == 0) {
                listener.onTransportDisconnected(Transport.Tcp)
                if (running.get()) {
                    Log.i(logTag, "Client disconnected, waiting again")
                    listener.onStatusChanged(StreamStatus(
                        kind = StreamStatusKind.Disconnected,
                        title = "Client disconnected",
                        detail = "Waiting for host on tcp:$port."
                    ))
                }
            } else if (transportConnected.get()) {
                Log.i(logTag, "Client disconnected: $sourceId")
            }
        }
    }

    @Throws(IOException::class)
    private fun handleClient(
        socket: Socket,
        sourceId: String,
        requiresWifiAuthentication: Boolean,
        onAuthenticated: () -> Unit
    ) {
        socket.tcpNoDelay = true
        if (requiresWifiAuthentication) {
            socket.soTimeout = WIFI_AUTH_TIMEOUT_MS
        }
        BufferedInputStream(socket.getInputStream()).use { input ->
            DataOutputStream(BufferedOutputStream(socket.getOutputStream())).use { output ->
                val streamKey = readStreamKey(input)
                val session = FrameReceiveSession(
                    streamKeyProvider = { streamKey },
                    sourceId = sourceId,
                    transport = Transport.Tcp,
                    frameSink = listener,
                    hostStatusSink = listener
                ) { frameId, acceptsFrames ->
                    if (requiresWifiAuthentication && socket.soTimeout != 0) {
                        socket.soTimeout = 0
                        onAuthenticated()
                    }
                    val hostCommand = if (acceptsFrames) {
                        listener.consumeHostCommand(Transport.Tcp)
                    } else {
                        null
                    }
                    if (hostCommand != null) {
                        Log.i(logTag, "Sending host command: $hostCommand")
                    }
                    output.writeInt(hostCommand?.ackMagic ?: WireProtocol.Ack.MAGIC)
                    output.writeInt(frameId)
                    output.flush()
                }

                val readBuffer = ByteArray(8 * 1024)
                while (running.get()) {
                    val read = input.read(readBuffer)
                    if (read < 0) return
                    if (read == 0) continue
                    session.append(readBuffer, 0, read)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun readStreamKey(input: BufferedInputStream): ByteArray {
        input.mark(WireProtocol.HostIdentity.PACKET_BYTES)
        val magic = DataInputStream(input).readInt()
        if (magic != WireProtocol.HostIdentity.MAGIC) {
            input.reset()
            return streamKeyStore.requireStreamKey()
        }

        val hostIdentity = ByteArray(WireProtocol.HostIdentity.ID_BYTES)
        DataInputStream(input).readFully(hostIdentity)
        return streamKeyStore.requireStreamKeyForHost(hostIdentity)
    }

    @Throws(IOException::class)
    private fun openServerSocket(): ServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        return server
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun sleepQuietly(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val DEFAULT_PORT = 19400
        const val MAX_WIFI_CLIENTS = 2

        private const val WIFI_AUTH_TIMEOUT_MS = 5_000
        private const val RETRY_DELAY_MS = 750L
    }
}

object TcpClientPolicy {
    fun requiresAuthenticationTimeout(address: InetAddress?): Boolean =
        address?.isLoopbackAddress != true
}
