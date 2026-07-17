package bio.aq.glassdisplay.streaming.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import bio.aq.glassdisplay.protocol.Transport
import bio.aq.glassdisplay.streaming.FrameServerListener
import bio.aq.glassdisplay.streaming.StreamKeyStore
import bio.aq.glassdisplay.streaming.StreamStatus
import bio.aq.glassdisplay.streaming.StreamStatusKind
import java.io.IOException

class BleFrameServer(
    private val context: Context,
    private val listener: FrameServerListener
) {
    private val logTag = "GlassBleFrameServer"
    private val streamKeyStore = StreamKeyStore(context)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val sessionStore = BleFrameSessionStore(streamKeyStore, listener, listener)
    private val commandResponder = BleHostCommandResponder(
        commandSource = listener,
        streamKeyProvider = { address -> sessionStore.streamKeyForAddress(address) }
    )
    private val requestHandler = BleGattRequestHandler(
        sessionStore = sessionStore,
        commandResponder = commandResponder,
        listener = listener,
        sendResponse = { device, requestId, status, offset, value ->
            sendResponseQuiet(device, requestId, status, offset, value)
        }
    )

    @Volatile
    private var state: BleServerState = BleServerState.Stopped

    @Volatile
    private var receiverRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(logTag, "Advertising started: $settingsInEffect")
            markAdvertisingStarted()
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(logTag, "Advertising failed: $errorCode")
            markAdvertisingFailed(errorCode)
            showBleStatus(
                kind = StreamStatusKind.Unavailable,
                title = "BLE advertise failed",
                detail = "code=$errorCode. Toggle Bluetooth or check permissions."
            )
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            Log.i(logTag, "BT state changed to $state")
            when (state) {
                BluetoothAdapter.STATE_ON -> tryStart()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> tearDown()
            }
        }
    }

    private val healthCheck = object : Runnable {
        override fun run() {
            if (state.isActive) {
                if (refreshDroppedCredentials()) {
                    Log.i(logTag, "health: stream credentials changed; restarting BLE advertising")
                    tearDown()
                    tryStart()
                } else if (state is BleServerState.Starting) {
                    Log.w(logTag, "health: advertise inactive; retrying")
                    tearDown()
                    tryStart()
                }
            }
            mainHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val key = device.address ?: return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(logTag, "BLE central connected: $key")
                    listener.onTransportConnected(Transport.Ble)
                    sessionStore.connect(key)
                    showBleStatus(
                        kind = StreamStatusKind.Connected,
                        title = "Connected",
                        detail = "Streaming over BLE."
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(logTag, "BLE central disconnected: $key")
                    sessionStore.disconnect(key)
                    commandResponder.remove(key)
                    requestHandler.onDeviceDisconnected(key)
                    listener.onFrameSourceDisconnected(sessionStore.sourceIdForAddress(key))
                    if (sessionStore.isEmpty()) {
                        listener.onTransportDisconnected(Transport.Ble)
                    }
                    if (sessionStore.isEmpty() && state.isActive) {
                        showBleStatus(
                            kind = StreamStatusKind.Waiting,
                            title = "Waiting for host",
                            detail = "BLE advertising as $ADVERTISE_LOCAL_NAME."
                        )
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            requestHandler.onCharacteristicWriteRequest(
                device = device,
                requestId = requestId,
                characteristic = characteristic,
                preparedWrite = preparedWrite,
                responseNeeded = responseNeeded,
                offset = offset,
                value = value
            )
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            requestHandler.onCharacteristicReadRequest(
                device = device,
                requestId = requestId,
                offset = offset,
                characteristic = characteristic
            )
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(logTag, "MTU for ${device.address}: $mtu")
        }
    }

    fun start() {
        registerReceiverIfNeeded()
        mainHandler.removeCallbacks(healthCheck)
        mainHandler.postDelayed(healthCheck, HEALTH_CHECK_INTERVAL_MS)
        tryStart()
    }

    fun stop() {
        Log.i(logTag, "stop() called")
        mainHandler.removeCallbacks(healthCheck)
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(btStateReceiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
        tearDown()
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                context,
                btStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (exception: Exception) {
            Log.e(logTag, "register BT state receiver failed", exception)
        }
    }

    @Synchronized
    private fun tryStart() {
        if (state is BleServerState.Advertising) {
            return
        }
        if (state is BleServerState.Starting) {
            tearDown()
        }

        val adapter = this.adapter
        if (adapter == null) {
            Log.w(logTag, "tryStart(): BluetoothAdapter is null")
            showBleStatus(
                kind = StreamStatusKind.Unavailable,
                title = "Bluetooth unavailable",
                detail = "BluetoothAdapter not available on this device."
            )
            return
        }
        if (!adapter.isEnabled) {
            Log.w(logTag, "tryStart(): Bluetooth is disabled")
            showBleStatus(
                kind = StreamStatusKind.Unavailable,
                title = "Bluetooth disabled",
                detail = "Enable Bluetooth to allow BLE streaming."
            )
            return
        }

        if (!hasRuntimePermissions()) {
            Log.w(logTag, "tryStart(): runtime BLE permissions missing")
            showBleStatus(
                kind = StreamStatusKind.PermissionMissing,
                title = "BLE permission missing",
                detail = "Grant Nearby devices permission to enable BLE."
            )
            return
        }

        val manager = bluetoothManager
        if (manager == null) {
            Log.e(logTag, "tryStart(): BluetoothManager is null")
            return
        }

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            showBleStatus(
                kind = StreamStatusKind.Unavailable,
                title = "BLE advertise unsupported",
                detail = "This device cannot act as a BLE peripheral."
            )
            return
        }

        try {
            val server = openGattServerLocked(manager)
            state = BleServerState.Starting(advertiser, server)
            startAdvertisingLocked(advertiser)
            showBleStatus(
                kind = StreamStatusKind.Waiting,
                title = "Waiting for host",
                detail = "BLE advertising as $ADVERTISE_LOCAL_NAME."
            )
        } catch (exception: SecurityException) {
            Log.e(logTag, "BLE start denied", exception)
            tearDown()
            state = BleServerState.Failed
            showBleStatus(
                kind = StreamStatusKind.PermissionMissing,
                title = "BLE permission denied",
                detail = exception.message ?: "Bluetooth permission missing."
            )
        }
    }

    @Synchronized
    private fun tearDown() {
        val resources = state.resources
        state = BleServerState.Stopped

        try {
            resources?.advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
        try {
            resources?.gattServer?.close()
        } catch (_: SecurityException) {
        }
        sessionStore.clear()
        commandResponder.clear()
        requestHandler.clear()
    }

    @SuppressLint("MissingPermission")
    private fun openGattServerLocked(manager: BluetoothManager): BluetoothGattServer {
        val server = manager.openGattServer(context, gattCallback)
            ?: throw IllegalStateException("openGattServer returned null")

        server.addService(BleGattProfile.createService())
        return server
    }

    private fun showBleStatus(kind: StreamStatusKind, title: String, detail: String) {
        if (listener.shouldAcceptFrame(Transport.Ble)) {
            listener.onStatusChanged(StreamStatus(kind, title, detail))
        }
    }

    @Synchronized
    private fun markAdvertisingStarted() {
        val starting = state as? BleServerState.Starting ?: return
        state = BleServerState.Advertising(starting.advertiser, starting.gattServer)
    }

    @Synchronized
    private fun markAdvertisingFailed(errorCode: Int) {
        if (state !is BleServerState.Starting) return
        tearDown()
        state = BleServerState.Failed
        Log.w(logTag, "BLE advertising entered failed state: $errorCode")
    }

    private fun refreshDroppedCredentials(): Boolean {
        return try {
            streamKeyStore.refreshDroppedCredentials()
        } catch (exception: IOException) {
            Log.w(logTag, "health: stream credential refresh failed", exception)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingLocked(advertiser: BluetoothLeAdvertiser) {
        val deviceIdentity = try {
            streamKeyStore.deviceIdentity()
        } catch (exception: IOException) {
            Log.w(logTag, "BLE device id unavailable", exception)
            null
        }
        advertiser.startAdvertising(
            BleGattProfile.createAdvertiseSettings(),
            BleGattProfile.createAdvertiseData(),
            BleGattProfile.createScanResponseData(deviceIdentity),
            advertiseCallback
        )
    }

    private fun hasRuntimePermissions(): Boolean {
        val advertise = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
        val connect = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        return advertise && connect
    }

    @SuppressLint("MissingPermission")
    private fun sendResponseQuiet(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) {
        try {
            state.resources?.gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        val SERVICE_UUID = BleGattProfile.SERVICE_UUID
        val FRAME_CHARACTERISTIC_UUID = BleGattProfile.FRAME_CHARACTERISTIC_UUID
        val COMMAND_CHARACTERISTIC_UUID = BleGattProfile.COMMAND_CHARACTERISTIC_UUID
        const val ADVERTISE_LOCAL_NAME = BleGattProfile.ADVERTISE_LOCAL_NAME
        private const val HEALTH_CHECK_INTERVAL_MS = 3_000L
    }
}

private sealed interface BleServerState {
    data object Stopped : BleServerState
    data object Failed : BleServerState

    data class Starting(
        override val advertiser: BluetoothLeAdvertiser,
        override val gattServer: BluetoothGattServer
    ) : BleServerState, Resources

    data class Advertising(
        override val advertiser: BluetoothLeAdvertiser,
        override val gattServer: BluetoothGattServer
    ) : BleServerState, Resources

    interface Resources {
        val advertiser: BluetoothLeAdvertiser
        val gattServer: BluetoothGattServer
    }
}

private val BleServerState.resources: BleServerState.Resources?
    get() = this as? BleServerState.Resources

private val BleServerState.isActive: Boolean
    get() = this is BleServerState.Starting || this is BleServerState.Advertising
