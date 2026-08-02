package com.rhys.obd2.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.obd2.Obd2App
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.data.DiagnosticReport
import com.rhys.obd2.data.ObdForegroundService
import com.rhys.obd2.data.ObdRepository
import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.transport.AdapterDevice
import com.rhys.obd2.transport.AdapterKind
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Bridges the repository to Compose.
 *
 * Deliberately thin: all the real state lives in the application-scoped repository so it
 * survives configuration changes and navigation. This adds the things that are genuinely
 * view concerns — scan results, transient messages, which screen is polling what.
 */
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
                    _scanResults.value = devices + bonded
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    fun connect(device: AdapterDevice) {
        stopScan()
        viewModelScope.launch {
            repository.connect(device)
            if (repository.connectionState.value is ConnectionState.Connected) {
                startDashboardPolling()
            }
        }
    }

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
        val file = repository.tripLogger.stop()
        ObdForegroundService.stop(getApplication())
        if (file != null) _message.value = "Saved ${file.name}"
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
