package bio.aq.glassdisplay.streaming

import android.content.Context
import java.io.File
import java.io.IOException

class StreamKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Throws(IOException::class)
    fun requireStreamKey(): ByteArray {
        importDroppedKeyIfPresent()
        val hex = prefs.getString(KEY_STREAM_KEY_HEX, null)
            ?: throw IOException("No stream key installed. Connect over adb once.")
        return parseHexKey(hex)
    }

    @Throws(IOException::class)
    fun requireStreamKeyForHost(hostIdentity: ByteArray): ByteArray {
        importDroppedKeyIfPresent()
        val hostHex = encodeHex(hostIdentity)
        parseHostIdentityHex(hostHex)
        val hex = prefs.getString(hostStreamKeyPreference(hostHex), null)
            ?: throw IOException("No stream key installed for this host. Connect over adb once.")
        return parseHexKey(hex)
    }

    @Throws(IOException::class)
    fun deviceIdentity(): ByteArray? {
        importDroppedDeviceIdentityIfPresent()
        val hex = prefs.getString(KEY_DEVICE_ID_HEX, null) ?: return null
        return parseDeviceIdentityHex(hex)
    }

    @Throws(IOException::class)
    fun refreshDroppedCredentials(): Boolean {
        val keyChanged = importDroppedKeyIfPresent()
        val deviceIdChanged = importDroppedDeviceIdentityIfPresent()
        return keyChanged || deviceIdChanged
    }

    @Throws(IOException::class)
    private fun importDroppedKeyIfPresent(): Boolean {
        val keyFile = File(appContext.filesDir, DROP_FILE_NAME)
        if (!keyFile.isFile) {
            return false
        }

        val hex = keyFile.readText(Charsets.US_ASCII).trim()
        parseHexKey(hex)
        val hostHex = readDroppedHostIdentityHex()
        val existingKey = prefs.getString(KEY_STREAM_KEY_HEX, null)
        if (hostHex == null) {
            if (existingKey == hex) {
                return false
            }
            prefs.edit().putString(KEY_STREAM_KEY_HEX, hex).apply()
            return true
        }

        val hostKeyPreference = hostStreamKeyPreference(hostHex)
        val existingHostKey = prefs.getString(hostKeyPreference, null)
        val existingActiveHost = prefs.getString(KEY_ACTIVE_HOST_ID_HEX, null)
        if (existingKey == hex && existingHostKey == hex && existingActiveHost == hostHex) {
            return false
        }
        prefs.edit()
            .putString(KEY_STREAM_KEY_HEX, hex)
            .putString(KEY_ACTIVE_HOST_ID_HEX, hostHex)
            .putString(hostKeyPreference, hex)
            .apply()

        // Keep the private app files so later app starts can import the latest dropped credentials.
        return true
    }

    @Throws(IOException::class)
    private fun readDroppedHostIdentityHex(): String? {
        val hostIdFile = File(appContext.filesDir, DROP_HOST_ID_FILE_NAME)
        if (!hostIdFile.isFile) {
            return null
        }

        val hex = hostIdFile.readText(Charsets.US_ASCII).trim()
        parseHostIdentityHex(hex)
        return hex.lowercase()
    }

    @Throws(IOException::class)
    private fun importDroppedDeviceIdentityIfPresent(): Boolean {
        val idFile = File(appContext.filesDir, DROP_DEVICE_ID_FILE_NAME)
        if (!idFile.isFile) {
            return false
        }

        val hex = idFile.readText(Charsets.US_ASCII).trim()
        parseDeviceIdentityHex(hex)
        if (prefs.getString(KEY_DEVICE_ID_HEX, null) == hex) {
            return false
        }
        prefs.edit().putString(KEY_DEVICE_ID_HEX, hex).apply()

        // Keep the private app file so another trusted Mac can read the same device id through adb/run-as.
        return true
    }

    companion object {
        private const val PREFS_NAME = "glass_display_stream_key"
        private const val KEY_STREAM_KEY_HEX = "stream_key_hex"
        private const val KEY_ACTIVE_HOST_ID_HEX = "active_host_id_hex"
        private const val KEY_HOST_STREAM_KEY_HEX_PREFIX = "host_stream_key_hex."
        private const val KEY_DEVICE_ID_HEX = "device_id_hex"
        private const val DROP_FILE_NAME = "glass-stream.key"
        private const val DROP_HOST_ID_FILE_NAME = "glass-host-id"
        private const val DROP_DEVICE_ID_FILE_NAME = "glass-device-id"

        @Throws(IOException::class)
        fun parseHexKey(hex: String): ByteArray {
            val trimmed = hex.trim()
            if (trimmed.length != 64) {
                throw IOException("Invalid stream key length.")
            }

            val output = ByteArray(32)
            for (index in output.indices) {
                val high = decodeHexNibble(trimmed[index * 2])
                val low = decodeHexNibble(trimmed[(index * 2) + 1])
                output[index] = ((high shl 4) or low).toByte()
            }
            return output
        }

        @Throws(IOException::class)
        fun parseDeviceIdentityHex(hex: String): ByteArray {
            val trimmed = hex.trim()
            if (trimmed.length != 16) {
                throw IOException("Invalid BLE device id length.")
            }

            val output = ByteArray(8)
            for (index in output.indices) {
                val high = decodeHexNibble(trimmed[index * 2])
                val low = decodeHexNibble(trimmed[(index * 2) + 1])
                output[index] = ((high shl 4) or low).toByte()
            }
            return output
        }

        @Throws(IOException::class)
        fun parseHostIdentityHex(hex: String): ByteArray = parseDeviceIdentityHex(hex)

        private fun hostStreamKeyPreference(hostHex: String): String =
            KEY_HOST_STREAM_KEY_HEX_PREFIX + hostHex.lowercase()

        private fun encodeHex(bytes: ByteArray): String =
            bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        @Throws(IOException::class)
        private fun decodeHexNibble(char: Char): Int {
            return when (char) {
                in '0'..'9' -> char - '0'
                in 'a'..'f' -> char - 'a' + 10
                in 'A'..'F' -> char - 'A' + 10
                else -> throw IOException("Invalid stream key format.")
            }
        }
    }
}
