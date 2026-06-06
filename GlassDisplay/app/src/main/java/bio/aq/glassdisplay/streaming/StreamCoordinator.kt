package bio.aq.glassdisplay.streaming

import android.os.Handler
import android.os.SystemClock
import bio.aq.glassdisplay.protocol.HostCommand
import bio.aq.glassdisplay.protocol.Transport
import java.util.concurrent.atomic.AtomicReference

class StreamCoordinator(
    private val mainHandler: Handler,
    private val onRestartBle: () -> Unit,
    private val onResolutionPending: (HostCommand) -> Unit,
    private val nowMs: () -> Long = SystemClock::uptimeMillis
) {
    private val pendingHostCommand = AtomicReference<HostCommand?>()
    private val pendingResolutionSwitch = AtomicReference<PendingResolutionSwitch?>()
    private val transportPriorityTracker = TransportPriorityTracker()
    private var hostStatusVisibleUntilMs = 0L

    private val resolutionSwitchTimeout = Runnable {
        val pending = pendingResolutionSwitch.get() ?: return@Runnable
        onResolutionPending(pending.command)
    }

    private val restartBleAfterTcpDisconnect = Runnable {
        if (transportPriorityTracker.activeTransport() == null) {
            onRestartBle()
        }
    }

    fun clearCallbacks() {
        mainHandler.removeCallbacks(resolutionSwitchTimeout)
        mainHandler.removeCallbacks(restartBleAfterTcpDisconnect)
    }

    fun queueResolutionCommand(command: HostCommand) {
        pendingResolutionSwitch.set(PendingResolutionSwitch(command = command))
        pendingHostCommand.set(command)
        mainHandler.removeCallbacks(resolutionSwitchTimeout)
        mainHandler.postDelayed(resolutionSwitchTimeout, RESOLUTION_SWITCH_TIMEOUT_MS)
    }

    fun consumeHostCommand(transport: Transport): HostCommand? {
        if (!shouldRouteHostCommandTo(transport)) {
            return null
        }

        val command = pendingHostCommand.getAndSet(null)
        if (command != null) {
            markResolutionCommandDispatched(command)
        }
        return command
    }

    fun onTransportConnected(transport: Transport) {
        transportPriorityTracker.onTransportConnected(transport)
        when (transport) {
            Transport.Tcp -> {
                mainHandler.post {
                    mainHandler.removeCallbacks(restartBleAfterTcpDisconnect)
                }
            }
            Transport.Ble -> Unit
        }
    }

    fun onTransportDisconnected(transport: Transport) {
        when (transport) {
            Transport.Tcp -> {
                if (transportPriorityTracker.onTransportDisconnected(Transport.Tcp)) {
                    mainHandler.post {
                        mainHandler.removeCallbacks(restartBleAfterTcpDisconnect)
                        mainHandler.postDelayed(
                            restartBleAfterTcpDisconnect,
                            BLE_RESTART_AFTER_TCP_DISCONNECT_MS
                        )
                    }
                }
            }
            Transport.Ble -> {
                transportPriorityTracker.onTransportDisconnected(Transport.Ble)
            }
        }
    }

    fun onHostStatusReceived() {
        pendingResolutionSwitch.set(null)
        hostStatusVisibleUntilMs = nowMs() + HOST_STATUS_MIN_VISIBLE_MS
        mainHandler.removeCallbacks(resolutionSwitchTimeout)
    }

    fun shouldHideStatusAfterFrame(width: Int, height: Int): Boolean {
        if (nowMs() < hostStatusVisibleUntilMs) {
            return false
        }
        hostStatusVisibleUntilMs = 0L

        val pending = pendingResolutionSwitch.get() ?: return true
        if (!pending.dispatched) {
            return false
        }

        val target = pending.command.expectedFrameSize
        val matched = target == null || (width == target.width && height == target.height)
        if (matched) {
            pendingResolutionSwitch.compareAndSet(pending, null)
            mainHandler.removeCallbacks(resolutionSwitchTimeout)
        }
        return matched
    }

    fun shouldAcceptFrame(transport: Transport): Boolean {
        return shouldRouteHostCommandTo(transport)
    }

    private fun shouldRouteHostCommandTo(transport: Transport): Boolean {
        return transportPriorityTracker.shouldUse(transport)
    }

    private fun markResolutionCommandDispatched(command: HostCommand) {
        while (true) {
            val pending = pendingResolutionSwitch.get() ?: return
            if (pending.command != command) {
                return
            }
            if (pending.dispatched) {
                return
            }
            if (pendingResolutionSwitch.compareAndSet(pending, pending.copy(dispatched = true))) {
                return
            }
        }
    }

    companion object {
        private const val BLE_RESTART_AFTER_TCP_DISCONNECT_MS = 2_000L
        private const val RESOLUTION_SWITCH_TIMEOUT_MS = 20_000L
        private const val HOST_STATUS_MIN_VISIBLE_MS = 8_000L
    }
}

private data class PendingResolutionSwitch(
    val command: HostCommand,
    val dispatched: Boolean = false
)
