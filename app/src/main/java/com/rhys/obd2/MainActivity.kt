package com.rhys.obd2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.rhys.obd2.ui.OpenObdApp
import com.rhys.obd2.ui.theme.OpenObdTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Bluetooth permissions changed shape in Android 12. Below that, scanning counted as
     * a location capability and needed ACCESS_FINE_LOCATION; from 12 onwards it's the
     * dedicated BLUETOOTH_SCAN and BLUETOOTH_CONNECT pair. Asking for the wrong set on
     * the wrong version silently fails, so the list is built per version.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsGranted.value = hasScanPermissions() }

    /**
     * Whether scanning is currently permitted.
     *
     * The connect screen keys its scan off this rather than firing as soon as it appears:
     * on a first launch the permission dialog is still on screen at that point, the scan
     * throws, and the user is told permission was denied a moment before they grant it.
     */
    private val permissionsGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionsGranted.value = hasScanPermissions()
        requestPermissions()
        keepScreenAwakeWhilePreferred()

        setContent {
            OpenObdTheme {
                OpenObdApp(
                    permissionsGranted = permissionsGranted.value,
                    onRequestPermissions = { requestPermissions() },
                )
            }
        }
    }

    /** The permissions a BLE scan needs on this Android version, ignoring the optional ones. */
    private fun scanPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasScanPermissions(): Boolean = scanPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val needed = buildList {
            addAll(scanPermissions())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    /** A phone that sleeps mid-drive stops logging, which defeats the point. */
    private fun keepScreenAwakeWhilePreferred() {
        val app = application as Obd2App
        lifecycleScope.launch {
            app.settings.keepScreenOn.collectLatest { enabled ->
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }
}
