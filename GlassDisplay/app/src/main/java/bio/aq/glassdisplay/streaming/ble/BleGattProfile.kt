package bio.aq.glassdisplay.streaming.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import java.util.UUID

object BleGattProfile {
    val SERVICE_UUID: UUID = UUID.fromString("52474431-0001-4f4e-9f0c-524744313031")
    val FRAME_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("52474431-0002-4f4e-9f0c-524744313031")
    val COMMAND_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("52474431-0003-4f4e-9f0c-524744313031")
    val HOST_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("52474431-0004-4f4e-9f0c-524744313031")
    const val ADVERTISE_LOCAL_NAME = "GlassDisplay"

    fun createService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val frameCharacteristic = BluetoothGattCharacteristic(
            FRAME_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(frameCharacteristic)

        val commandCharacteristic = BluetoothGattCharacteristic(
            COMMAND_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(commandCharacteristic)

        val hostCharacteristic = BluetoothGattCharacteristic(
            HOST_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(hostCharacteristic)
        return service
    }

    fun createAdvertiseSettings(): AdvertiseSettings =
        AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

    fun createAdvertiseData(): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

    fun createScanResponseData(deviceIdentity: ByteArray?): AdvertiseData? {
        if (deviceIdentity == null) {
            return null
        }
        return AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), deviceIdentity)
            .build()
    }
}
