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
import java.util.UUID

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
                val name = device.name
                AdapterDevice(
                    name = name ?: "Unnamed device",
                    address = device.address,
                    kind = AdapterKind.CLASSIC_BLUETOOTH,
                    bonded = true,
                    relevance = classify(name, emptyList(), bonded = true),
                )
            }.let { order(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * Live BLE scan. Emits the accumulated result list whenever it changes.
     *
     * The scan itself stays deliberately unfiltered at the radio level: many working
     * adapters advertise no service UUIDs at all, so a hardware scan filter would make
     * them permanently invisible with no way for the user to discover why. Each result is
     * instead classified into [DeviceRelevance] and the UI decides what to show, which
     * keeps the noise out of the way while leaving it reachable.
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
                    ?: runCatching { device.name }.getOrNull()
                val name = advertisedName ?: "Unnamed (${device.address.takeLast(5)})"
                val bonded = runCatching {
                    device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED
                }.getOrDefault(false)

                // Some dongles advertise a recognisable GATT service but no name at all,
                // which is the one case where the UUIDs are the only thing to go on.
                val services = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }

                val entry = AdapterDevice(
                    name = name,
                    address = device.address,
                    kind = AdapterKind.BLE,
                    rssi = result.rssi,
                    bonded = bonded,
                    relevance = classify(advertisedName, services, bonded),
                )
                val previous = found[device.address]
                // Keep the better-known name if a later advert drops it. Adverts from one
                // device vary between packets, so relevance only ever improves — a dongle
                // that identified itself once doesn't stop being a dongle when the next
                // packet omits the name.
                val merged = if (previous != null && previous.name.startsWith("Unnamed")) {
                    entry
                } else if (previous != null && entry.name.startsWith("Unnamed")) {
                    previous.copy(rssi = result.rssi)
                } else {
                    entry
                }
                found[device.address] = if (previous == null) merged else {
                    merged.copy(relevance = merged.relevance.or(previous.relevance))
                }
                trySend(order(found.values.toList()))
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
            "obd", "elm", "elm327", "vgate", "icar", "vlink", "v-link", "vlinker",
            "obdlink", "obdii", "obd2", "konnwei", "veepeak", "lelink", "carista",
            "bafx", "viecar", "kiwi", "friencity", "ancel", "autel", "topdon",
            "thinkdiag", "foxwell", "launch", "nexpeak", "panlong", "scantool",
            "torque", "carly", "kobra", "wgw", "diagnostic", "scanner",
        )

        /** Name-based heuristic. Only ever promotes a device, never excludes one. */
        fun looksLikeObdAdapter(name: String): Boolean {
            val lower = name.lowercase()
            return HINTS.any { lower.contains(it) }
        }

        /**
         * Decides whether a discovered device is worth showing by default.
         *
         * [name] must be the *advertised* name, not a placeholder built from the address:
         * having no name at all is the strongest single signal that something is not an
         * OBD adapter. Adapters exist to be found and every one of them names itself; the
         * anonymous devices filling up a scan are beacons and other people's electronics
         * advertising under a rotating address.
         */
        fun classify(
            name: String?,
            serviceUuids: List<UUID>,
            bonded: Boolean,
        ): DeviceRelevance = when {
            name != null && looksLikeObdAdapter(name) -> DeviceRelevance.LIKELY
            serviceUuids.any { it in BleTransport.SERVICE_HINTS } -> DeviceRelevance.LIKELY
            bonded -> DeviceRelevance.PAIRED
            else -> DeviceRelevance.OTHER
        }

        /**
         * Shared ordering for the scan list: most relevant first, strongest signal within
         * a group. Lives here so the merged BLE-plus-paired list sorts the same way the
         * scanner's own output does.
         */
        fun order(devices: List<AdapterDevice>): List<AdapterDevice> =
            devices.sortedWith(
                compareBy<AdapterDevice> { it.relevance.ordinal }
                    .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                    .thenBy { it.name }
            )
    }
}
