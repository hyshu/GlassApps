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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class FrameServer(
    context: Context,
    private val port: Int,
    private val listener: FrameServerListener
) {
    private val logTag = "GlassFrameServer"
    private val streamKeyStore = StreamKeyStore(context)

    private val running = AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var clientSocket: Socket? = null

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

        closeQuietly(clientSocket)
        closeQuietly(serverSocket)
        workerThread?.interrupt()
        workerThread = null
        clientSocket = null
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
                        clientSocket = socket
                        Log.i(logTag, "Client connected from ${socket.inetAddress?.hostAddress}:${socket.port}")
                        listener.onTransportConnected(Transport.Tcp)

                        listener.onStatusChanged(
                            title = "Connected",
                            detail = "Streaming on tcp:$port via adb forward."
                        )

                        try {
                            handleClient(socket)
                        } catch (exception: IOException) {
                            Log.e(logTag, "Stream error", exception)
                            listener.onStatusChanged(
                                title = "Stream error",
                                detail = exception.message ?: "Unable to read stream."
                            )
                        } finally {
                            closeQuietly(socket)
                            clientSocket = null
                            listener.onFrameSourceDisconnected(TCP_SOURCE_ID)
                            listener.onTransportDisconnected(Transport.Tcp)
                        }

                        if (running.get()) {
                            Log.i(logTag, "Client disconnected, waiting again")
                            listener.onStatusChanged(
                                title = "Client disconnected",
                                detail = "Waiting for host on tcp:$port."
                            )
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

    @Throws(IOException::class)
    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        BufferedInputStream(socket.getInputStream()).use { input ->
            DataOutputStream(BufferedOutputStream(socket.getOutputStream())).use { output ->
                val session = FrameReceiveSession(
                    streamKeyProvider = { streamKeyStore.requireStreamKey() },
                    sourceId = TCP_SOURCE_ID,
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

        private const val TCP_SOURCE_ID = "tcp"
        private const val RETRY_DELAY_MS = 750L
    }
}
