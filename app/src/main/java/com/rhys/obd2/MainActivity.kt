package com.rhys.obd2

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestPermissions()
        keepScreenAwakeWhilePreferred()

        setContent {
            OpenObdTheme {
                OpenObdApp(
                    onRequestPermissions = { requestPermissions() },
                )
            }
        }
    }

    private fun requestPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
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
