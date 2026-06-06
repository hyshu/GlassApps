package bio.aq.glassdisplay.streaming.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import bio.aq.glassdisplay.protocol.Transport
import bio.aq.glassdisplay.streaming.FrameServerListener

class BleGattRequestHandler(
    private val sessionStore: BleFrameSessionStore,
    private val commandResponder: BleHostCommandResponder,
    private val listener: FrameServerListener,
    private val sendResponse: (
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) -> Unit
) {
    fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {
        if (characteristic.uuid == BleGattProfile.HOST_CHARACTERISTIC_UUID) {
            handleHostIdentityWrite(device, requestId, responseNeeded, offset, value)
            return
        }

        if (characteristic.uuid != BleGattProfile.FRAME_CHARACTERISTIC_UUID) {
            sendFailureIfNeeded(device, requestId, responseNeeded, offset)
            return
        }

        val session = sessionStore.session(device.address)
        if (session == null) {
            sendFailureIfNeeded(device, requestId, responseNeeded, offset)
            return
        }

        try {
            session.append(value, 0, value.size)
            if (responseNeeded) {
                sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Frame parse error from ${device.address}", exception)
            session.reset()
            sendFailureIfNeeded(device, requestId, responseNeeded, offset)
            if (listener.shouldShowStreamError(Transport.Ble)) {
                listener.onStatusChanged(
                    title = "BLE stream error",
                    detail = exception.message ?: "Parser reset; continuing."
                )
            }
        }
    }

    private fun handleHostIdentityWrite(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {
        val address = device.address
        if (address == null || offset != 0 || value.size != HOST_ID_BYTES) {
            sendFailureIfNeeded(device, requestId, responseNeeded, offset)
            return
        }

        try {
            sessionStore.authenticateHost(address, value)
            if (responseNeeded) {
                sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "BLE host authentication failed for $address", exception)
            sendFailureIfNeeded(device, requestId, responseNeeded, offset)
            if (listener.shouldShowStreamError(Transport.Ble)) {
                listener.onStatusChanged(
                    title = "BLE key unavailable",
                    detail = exception.message ?: "Connect over adb once for this Mac."
                )
            }
        }
    }

    fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (characteristic.uuid != BleGattProfile.COMMAND_CHARACTERISTIC_UUID) {
            sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            return
        }

        val address = device.address
        if (address == null || !sessionStore.contains(address)) {
            sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            return
        }

        val commandResponse = try {
            commandResponder.responseForDevice(
                address = address,
                connectedAddresses = sessionStore.connectedAddresses()
            )
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Command response failed for $address", exception)
            sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            return
        }

        if (commandResponse.command != null) {
            Log.i(LOG_TAG, "Sending BLE host command to $address: ${commandResponse.command}")
        }

        val sliced = if (offset >= commandResponse.bytes.size) {
            ByteArray(0)
        } else {
            commandResponse.bytes.copyOfRange(offset, commandResponse.bytes.size)
        }
        sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, sliced)
    }

    private fun sendFailureIfNeeded(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        offset: Int
    ) {
        if (responseNeeded) {
            sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
        }
    }

    companion object {
        private const val LOG_TAG = "GlassBleGattRequest"
        private const val HOST_ID_BYTES = 8
    }
}
