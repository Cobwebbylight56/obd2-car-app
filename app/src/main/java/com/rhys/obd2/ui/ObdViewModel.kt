package com.rhys.obd2.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.obd2.Obd2App
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.data.DiagnosticReport
import com.rhys.obd2.data.ObdForegroundService
import com.rhys.obd2.data.ObdRepository
import com.rhys.obd2.data.VehicleHistoryEvent
import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.transport.AdapterDevice
import com.rhys.obd2.transport.AdapterKind
import com.rhys.obd2.transport.DeviceScanner
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

/**
 * Bridges the repository to Compose.
 *
 * Deliberately thin: all the real state lives in the application-scoped repository so it
 * survives configuration changes and navigation. This adds the things that are genuinely
 * view concerns — scan results, transient messages, which screen is polling what.
 */
/** What a history export is written as. */
enum class ExportFormat(val label: String, val detail: String, val mime: String) {
    PDF("PDF", "Prints, and a garage will accept it", "application/pdf"),
    TEXT("Text", "Pastes into an email or a message", "text/plain"),
    CSV("Spreadsheet", "Opens in Excel or Sheets", "text/csv"),
}

class ObdViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Obd2App get() = getApplication()
    val repository: ObdRepository get() = app.repository
    val settings get() = app.settings

    val connectionState: StateFlow<ConnectionState> get() = repository.connectionState
    val liveData get() = repository.liveData
    val supportedPids get() = repository.supportedPids
    val vehicleInfo get() = repository.vehicleInfo
    val dtcs get() = repository.dtcs
    val readiness get() = repository.readiness
    val freezeFrame get() = repository.freezeFrame
    val monitorTests get() = repository.monitorTests
    val pollRate get() = repository.pollRate
    val busy get() = repository.busy
    val isLogging get() = repository.tripLogger.isLogging
    val tripStats get() = repository.tripLogger.stats

    val vehicles get() = repository.garage.vehicles
    val currentVehicle get() = repository.currentVehicle

    private val _scanResults = MutableStateFlow<List<AdapterDevice>>(emptyList())
    val scanResults: StateFlow<List<AdapterDevice>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var scanJob: kotlinx.coroutines.Job? = null

    // ---------------------------------------------------------------------------------
    // Discovery and connection
    // ---------------------------------------------------------------------------------

    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        _scanResults.value = app.scanner.bondedDevices()

        scanJob = viewModelScope.launch {
            app.scanner.scanBle()
                .catch { error ->
                    _message.value = error.message ?: "Bluetooth scan failed"
                    _scanning.value = false
                }
                .collect { devices ->
                    // Merge live BLE hits with the already-paired classic devices, keeping
                    // one entry per address so a dual-mode dongle isn't listed twice.
                    val bonded = app.scanner.bondedDevices()
                        .filter { paired -> devices.none { it.address == paired.address } }
                    _scanResults.value = DeviceScanner.order(devices + bonded)
                }
        }
    }

    /**
     * Re-reads Android's paired-device list and merges it into the results.
     *
     * Classic Bluetooth adapters — the cheap ELM327 dongles that say "Android and Windows
     * only" — never show up in a BLE scan and only become visible once they're paired in
     * Android's own settings. The normal sequence is therefore: open the app, see nothing,
     * leave to pair, come back. Without this the list is still stale on return and the
     * adapter appears to be unsupported.
     */
    fun refreshPairedDevices() {
        val paired = app.scanner.bondedDevices()
        val existing = _scanResults.value
        _scanResults.value = DeviceScanner.order(
            existing + paired.filter { new -> existing.none { it.address == new.address } }
        )
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    fun connect(device: AdapterDevice) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            // Wait for the scan to actually stop, not just to be told to.
            //
            // stopScan() cancels the collecting coroutine, but the BLE scanner is only
            // released in that flow's awaitClose, which runs asynchronously afterwards.
            // Starting an RFCOMM connect while the radio is still scanning is a well-known
            // way to make the connect fail or hang on a lot of Bluetooth stacks, and it
            // was a race the app lost more often than not.
            val running = scanJob
            _scanning.value = false
            scanJob = null
            running?.cancelAndJoin()

            repository.connect(device)
            if (repository.connectionState.value is ConnectionState.Connected) {
                startDashboardPolling()
            }
        }
    }

    /** Abandons an in-progress connection attempt. */
    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        repository.disconnect()
    }

    private var connectJob: kotlinx.coroutines.Job? = null

    /**
     * Connects to the previously used adapter, at most once per app session.
     *
     * Guarded rather than driven purely off connection state, so that deliberately
     * disconnecting doesn't immediately reconnect underneath the user.
     */
    fun autoConnectOnce() {
        if (autoConnectAttempted) return
        autoConnectAttempted = true
        connectToLastDevice()
    }

    private var autoConnectAttempted = false

    fun connectToLastDevice() {
        val saved = settings.lastDevice ?: return
        connect(AdapterDevice(saved.name, saved.address, saved.kind))
    }

    fun connectDemo() {
        connect(AdapterDevice("Demo vehicle", "demo", AdapterKind.DEMO))
    }

    fun connectWifi(host: String, port: Int) {
        connect(AdapterDevice("Wi-Fi adapter", "$host:$port", AdapterKind.WIFI))
    }

    fun disconnect() {
        stopLogging()
        repository.disconnect()
    }

    // ---------------------------------------------------------------------------------
    // Polling
    // ---------------------------------------------------------------------------------

    /** Polls the dashboard's chosen PIDs. */
    fun startDashboardPolling() {
        val chosen = settings.dashboardPids.value
        val supported = supportedPids.value
        val targets = if (supported.isEmpty()) chosen else chosen.filter { it in supported }
        repository.startPolling(targets.ifEmpty { PidRegistry.DEFAULT_DASHBOARD })
    }

    /** Polls an arbitrary set, used by the live data screen when the user picks rows. */
    fun setPollTargets(pids: List<Int>) {
        repository.startPolling(pids)
    }

    fun stopPolling() = repository.stopPolling()

    fun setDashboardPids(pids: List<Int>) {
        settings.setDashboardPids(pids)
        startDashboardPolling()
    }

    // ---------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------

    fun refreshDtcs() = viewModelScope.launch {
        repository.refreshDtcs()
        repository.refreshFreezeFrame()
    }

    fun clearDtcs() = viewModelScope.launch {
        val result = repository.clearDtcs()
        _message.value = result.message
    }

    fun refreshReadiness() = viewModelScope.launch { repository.refreshReadiness() }

    fun refreshVehicleInfo() = viewModelScope.launch { repository.refreshVehicleInfo() }

    fun refreshMonitorTests() = viewModelScope.launch { repository.refreshMonitorTests() }

    fun startLogging() {
        val pids = (settings.dashboardPids.value + PidRegistry.DEFAULT_DASHBOARD)
            .distinct()
            .mapNotNull { PidRegistry[it] }
        repository.tripLogger.start(pids)
        ObdForegroundService.start(getApplication())
    }

    fun stopLogging(): File? {
        val stats = repository.tripLogger.stats.value
        val file = repository.tripLogger.stop()
        ObdForegroundService.stop(getApplication())
        if (file != null) {
            _message.value = "Saved ${file.name}"
            stats?.let {
                repository.recordTrip(
                    summary = "Trip recorded — %.1f km".format(java.util.Locale.UK, it.distanceKm),
                    detail = buildString {
                        appendLine("Duration: ${it.durationMs / 60000} min")
                        appendLine("Distance: %.1f km".format(java.util.Locale.UK, it.distanceKm))
                        appendLine("Top speed: %.0f km/h".format(java.util.Locale.UK, it.maxSpeed))
                        appendLine("Max RPM: %.0f".format(java.util.Locale.UK, it.maxRpm))
                        appendLine("Max coolant: %.0f °C".format(java.util.Locale.UK, it.maxCoolant))
                        append("Log file: ${file.name}")
                    },
                )
            }
        }
        return file
    }

    /**
     * Captures everything currently known about the car as a shareable text file.
     *
     * Reads whatever hasn't been read yet first, so the report is complete even if the
     * user goes straight to it — there is no point producing a document that says
     * "not read" for the sections they most wanted.
     */
    suspend fun saveReport(): File? {
        if (repository.isConnected) {
            if (dtcs.value == null) repository.refreshDtcs()
            if (freezeFrame.value == null) repository.refreshFreezeFrame()
            if (readiness.value == null) repository.refreshReadiness()
            if (vehicleInfo.value?.vin == null) repository.refreshVehicleInfo()
        }
        val text = DiagnosticReport.build(
            vehicle = vehicleInfo.value,
            dtcs = dtcs.value,
            readiness = readiness.value,
            freezeFrame = freezeFrame.value,
            monitorTests = monitorTests.value,
            units = settings.units.value,
        )
        return runCatching { DiagnosticReport.save(getApplication(), text) }
            .onFailure { _message.value = "Couldn't save the report: ${it.message}" }
            .getOrNull()
    }

    // -----------------------------------------------------------------------------
    // Garage
    // -----------------------------------------------------------------------------

    fun setVehicleModel(key: String, modelId: String?) {
        repository.garage.setModel(key, modelId)
        historyRevision.value++
    }

    /** When this code has been seen on this car before. Newest first. */
    fun codeHistory(code: String): List<Long> {
        val key = currentVehicle.value?.key ?: return emptyList()
        return repository.garage.occurrencesOf(key, code)
    }

    /** The model the owner picked for the connected car, for known-issue notes. */
    fun currentModelId(): String? = currentVehicle.value?.modelId

    fun exportHistory(key: String, format: ExportFormat): File? {
        val vehicle = repository.garage.vehicle(key) ?: return null
        val events = repository.garage.events(key)
        val context = getApplication<Application>()
        return when (format) {
            ExportFormat.PDF -> com.rhys.obd2.data.HistoryExport.toPdf(context, vehicle, events)
            ExportFormat.TEXT -> com.rhys.obd2.data.HistoryExport.toText(context, vehicle, events)
            ExportFormat.CSV -> com.rhys.obd2.data.HistoryExport.toCsv(context, vehicle, events)
        }
    }

    fun deleteHistoryEvent(key: String, event: VehicleHistoryEvent) {
        repository.garage.deleteEvent(key, event)
        historyRevision.value++
    }

    fun clearHistory(key: String) {
        repository.garage.clearHistory(key)
        historyRevision.value++
    }

    /**
     * Bumped whenever the history changes on disk.
     *
     * The events are read from a file rather than held in a flow, so nothing would
     * otherwise tell Compose that a deletion happened and the list would keep showing the
     * entry until the screen was left and re-entered.
     */
    val historyRevision = MutableStateFlow(0)

    fun historyFor(key: String): List<VehicleHistoryEvent> = repository.garage.events(key)

    fun historySize(key: String): String {
        val bytes = repository.garage.storageBytes(key)
        return when {
            bytes >= 1_048_576 -> "%.1f MB".format(java.util.Locale.UK, bytes / 1_048_576.0)
            bytes >= 1024 -> "%.0f KB".format(java.util.Locale.UK, bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun renameVehicle(key: String, name: String) = repository.garage.rename(key, name)

    fun deleteVehicle(key: String) {
        repository.garage.delete(key)
        _message.value = "Car and its history deleted"
    }

    fun listLogs(): List<File> = repository.tripLogger.listLogs()

    fun deleteLog(file: File) {
        repository.tripLogger.deleteLog(file)
    }

    suspend fun sendRaw(command: String): String = repository.sendRaw(command)

    fun showMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
