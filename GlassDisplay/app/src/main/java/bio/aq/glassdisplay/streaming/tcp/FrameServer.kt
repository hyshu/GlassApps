package bio.aq.glassdisplay.streaming.tcp

import android.content.Context
import android.util.Log
import bio.aq.glassdisplay.protocol.Transport
import bio.aq.glassdisplay.protocol.WireProtocol
import bio.aq.glassdisplay.streaming.FrameReceiveSession
import bio.aq.glassdisplay.streaming.FrameServerListener
import bio.aq.glassdisplay.streaming.StreamKeyStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
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
                    listener.onStatusChanged(
                        title = "Waiting for host",
                        detail = "Keep host/scripts/glass-stream.sh running. It will forward tcp:$port and connect automatically."
                    )

                    while (running.get()) {
                        val socket = server.accept()
                        if (clientSockets.size >= MAX_CLIENTS) {
                            Log.w(
                                logTag,
                                "Rejecting client ${socket.inetAddress?.hostAddress}: already $MAX_CLIENTS connected"
                            )
                            closeQuietly(socket)
                            continue
                        }

                        clientSockets.add(socket)
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
                listener.onStatusChanged(
                    title = "Socket error",
                    detail = exception.message ?: "Unable to open stream socket."
                )
                sleepQuietly(RETRY_DELAY_MS)
            } finally {
                serverSocket = null
            }
        }
    }

    private fun serveClient(socket: Socket) {
        val sourceId = "tcp:${socket.inetAddress?.hostAddress}:${socket.port}"
        Log.i(logTag, "Client connected from $sourceId")
        if (connectedClientCount.incrementAndGet() == 1) {
            listener.onTransportConnected(Transport.Tcp)
        }
        listener.onStatusChanged(
            title = "Connected",
            detail = "Streaming on tcp:$port."
        )

        try {
            handleClient(socket, sourceId)
        } catch (exception: IOException) {
            if (running.get()) {
                Log.e(logTag, "Stream error", exception)
                listener.onStatusChanged(
                    title = "Stream error",
                    detail = exception.message ?: "Unable to read stream."
                )
            }
        } finally {
            closeQuietly(socket)
            clientSockets.remove(socket)
            listener.onFrameSourceDisconnected(sourceId)
            if (connectedClientCount.decrementAndGet() == 0) {
                listener.onTransportDisconnected(Transport.Tcp)
                if (running.get()) {
                    Log.i(logTag, "Client disconnected, waiting again")
                    listener.onStatusChanged(
                        title = "Client disconnected",
                        detail = "Waiting for host on tcp:$port."
                    )
                }
            } else {
                Log.i(logTag, "Client disconnected: $sourceId")
            }
        }
    }

    @Throws(IOException::class)
    private fun handleClient(socket: Socket, sourceId: String) {
        socket.tcpNoDelay = true
        BufferedInputStream(socket.getInputStream()).use { input ->
            DataOutputStream(BufferedOutputStream(socket.getOutputStream())).use { output ->
                val session = FrameReceiveSession(
                    streamKeyProvider = { streamKeyStore.requireStreamKey() },
                    sourceId = sourceId,
                    transport = Transport.Tcp,
                    frameSink = listener,
                    hostStatusSink = listener
                ) { frameId, acceptsFrames ->
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
        const val MAX_CLIENTS = 2

        private const val RETRY_DELAY_MS = 750L
    }
}
