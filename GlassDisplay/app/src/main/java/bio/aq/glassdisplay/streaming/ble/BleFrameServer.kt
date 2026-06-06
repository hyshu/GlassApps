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

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
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
    private var running = false

    @Volatile
    private var advertiseStarted = false

    @Volatile
    private var receiverRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(logTag, "Advertising started: $settingsInEffect")
            advertiseStarted = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(logTag, "Advertising failed: $errorCode")
            advertiseStarted = false
            showBleStatus(
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
            if (running) {
                if (refreshDroppedCredentials()) {
                    Log.i(logTag, "health: stream credentials changed; restarting BLE advertising")
                    tearDown()
                    tryStart()
                } else if (!advertiseStarted) {
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
                        title = "Connected",
                        detail = "Streaming over BLE."
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(logTag, "BLE central disconnected: $key")
                    sessionStore.disconnect(key)
                    commandResponder.remove(key)
                    listener.onFrameSourceDisconnected(sessionStore.sourceIdForAddress(key))
                    if (sessionStore.isEmpty()) {
                        listener.onTransportDisconnected(Transport.Ble)
                    }
                    if (sessionStore.isEmpty() && running) {
                        showBleStatus(
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
        if (running && advertiseStarted) {
            return
        }
        if (running && !advertiseStarted) {
            tearDown()
        }

        val adapter = this.adapter
        if (adapter == null) {
            Log.w(logTag, "tryStart(): BluetoothAdapter is null")
            showBleStatus(
                title = "Bluetooth unavailable",
                detail = "BluetoothAdapter not available on this device."
            )
            return
        }
        if (!adapter.isEnabled) {
            Log.w(logTag, "tryStart(): Bluetooth is disabled")
            showBleStatus(
                title = "Bluetooth disabled",
                detail = "Enable Bluetooth to allow BLE streaming."
            )
            return
        }

        if (!hasRuntimePermissions()) {
            Log.w(logTag, "tryStart(): runtime BLE permissions missing")
            showBleStatus(
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
                title = "BLE advertise unsupported",
                detail = "This device cannot act as a BLE peripheral."
            )
            return
        }

        running = true

        try {
            openGattServerLocked(manager)
            startAdvertisingLocked(advertiser)
            this.advertiser = advertiser
        } catch (exception: SecurityException) {
            Log.e(logTag, "BLE start denied", exception)
            running = false
            showBleStatus(
                title = "BLE permission denied",
                detail = exception.message ?: "Bluetooth permission missing."
            )
        }
    }

    @Synchronized
    private fun tearDown() {
        running = false
        advertiseStarted = false

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
        advertiser = null

        try {
            gattServer?.close()
        } catch (_: SecurityException) {
        }
        gattServer = null
        sessionStore.clear()
        commandResponder.clear()
    }

    @SuppressLint("MissingPermission")
    private fun openGattServerLocked(manager: BluetoothManager) {
        val server = manager.openGattServer(context, gattCallback)
            ?: throw IllegalStateException("openGattServer returned null")

        server.addService(BleGattProfile.createService())
        gattServer = server

        showBleStatus(
            title = "Waiting for host",
            detail = "BLE advertising as $ADVERTISE_LOCAL_NAME."
        )
    }

    private fun showBleStatus(title: String, detail: String) {
        if (listener.shouldAcceptFrame(Transport.Ble)) {
            listener.onStatusChanged(title = title, detail = detail)
        }
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
            gattServer?.sendResponse(device, requestId, status, offset, value)
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
