package com.rhys.obd2.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Finds candidate adapters.
 *
 * BLE results stream in over time so they're exposed as a flow of the full list so far,
 * re-sorted on every hit. Paired classic devices are a one-shot query.
 */
@SuppressLint("MissingPermission")
class DeviceScanner(private val context: Context) {

    private fun manager(): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    fun bluetoothAvailable(): Boolean = manager()?.adapter != null

    fun bluetoothEnabled(): Boolean = manager()?.adapter?.isEnabled == true

    /**
     * Devices already paired in Android's Bluetooth settings. Classic ELM327s must be
     * paired before they can be connected to, so this is the whole candidate list for them.
     */
    fun bondedDevices(): List<AdapterDevice> {
        val adapter = manager()?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { device ->
                AdapterDevice(
                    name = device.name ?: "Unnamed device",
                    address = device.address,
                    kind = AdapterKind.CLASSIC_BLUETOOTH,
                    bonded = true,
                )
            }.sortedByDescending { looksLikeObdAdapter(it.name) }
        }.getOrDefault(emptyList())
    }

    /**
     * Live BLE scan. Emits the accumulated result list whenever it changes.
     *
     * Deliberately unfiltered: many adapters advertise no service UUIDs at all, so
     * filtering on the known ones would hide working hardware. Ranking happens instead.
     */
    fun scanBle(): Flow<List<AdapterDevice>> = callbackFlow {
        val scanner = manager()?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(ObdConnectionException("Bluetooth LE scanning is unavailable"))
            return@callbackFlow
        }

        val found = LinkedHashMap<String, AdapterDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val advertisedName = result.scanRecord?.deviceName
                val name = advertisedName
                    ?: runCatching { device.name }.getOrNull()
                    ?: "Unnamed (${device.address.takeLast(5)})"

                val entry = AdapterDevice(
                    name = name,
                    address = device.address,
                    kind = AdapterKind.BLE,
                    rssi = result.rssi,
                    bonded = runCatching { device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED }
                        .getOrDefault(false),
                )
                val previous = found[device.address]
                // Keep the better-known name if a later advert drops it.
                found[device.address] = if (previous != null && previous.name.startsWith("Unnamed")) {
                    entry
                } else if (previous != null && entry.name.startsWith("Unnamed")) {
                    previous.copy(rssi = result.rssi)
                } else {
                    entry
                }
                trySend(rank(found.values.toList()))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed with code $errorCode")
                close(ObdConnectionException(scanFailureMessage(errorCode)))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            close(ObdConnectionException("Bluetooth scan permission was denied", e))
            return@callbackFlow
        }

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /** Likely adapters float to the top; strong signal breaks ties. */
    private fun rank(devices: List<AdapterDevice>): List<AdapterDevice> =
        devices.sortedWith(
            compareByDescending<AdapterDevice> { looksLikeObdAdapter(it.name) }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
        )

    private fun scanFailureMessage(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "A scan is already running"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android refused the scan registration"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone doesn't support this scan mode"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth internal error — try toggling Bluetooth off and on"
        else -> "Bluetooth scan failed (code $code)"
    }

    companion object {
        private const val TAG = "DeviceScanner"

        private val HINTS = listOf(
            "obd", "elm", "vgate", "icar", "vlink", "obdlink", "konnwei", "veepeak",
            "lelink", "carista", "bafx", "viecar", "kiwi", "scan", "v-link", "obdii",
        )

        /** Name-based heuristic for ordering the scan list. Never used to exclude. */
        fun looksLikeObdAdapter(name: String): Boolean {
            val lower = name.lowercase()
            return HINTS.any { lower.contains(it) }
        }
    }
}
