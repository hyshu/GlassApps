package bio.aq.glassdisplay.streaming.ble

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class BlePermissionController(
    activity: ComponentActivity,
    private val onGranted: () -> Unit,
    private val onDenied: () -> Unit
) {
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )

    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val allGranted = permissions.all { results[it] == true }
            if (allGranted) {
                onGranted()
            } else {
                onDenied()
            }
        }

    private val context = activity.applicationContext

    fun ensureGrantedOrRequest() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
        } else {
            launcher.launch(missing.toTypedArray())
        }
    }
}
