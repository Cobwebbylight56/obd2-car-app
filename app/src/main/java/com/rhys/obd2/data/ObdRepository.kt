package com.rhys.obd2.data

import android.content.Context
import android.util.Log
import com.rhys.obd2.elm.Elm327
import com.rhys.obd2.elm.ObdError
import com.rhys.obd2.elm.ObdParser
import com.rhys.obd2.obd.Dtc
import com.rhys.obd2.obd.DtcStatus
import com.rhys.obd2.obd.Mode06
import com.rhys.obd2.obd.Mode09
import com.rhys.obd2.obd.MonitorTest
import com.rhys.obd2.obd.Pid
import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.obd.Readiness
import com.rhys.obd2.obd.Reading
import com.rhys.obd2.obd.VehicleInfo
import com.rhys.obd2.transport.AdapterDevice
import com.rhys.obd2.transport.AdapterKind
import com.rhys.obd2.transport.BleTransport
import com.rhys.obd2.transport.ClassicBtTransport
import com.rhys.obd2.transport.DemoTransport
import com.rhys.obd2.transport.ObdConnectionException
import com.rhys.obd2.transport.ObdTransport
import com.rhys.obd2.transport.WifiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val step: String) : ConnectionState
    data class Connected(
        val deviceName: String,
        val kind: AdapterKind,
        val adapter: String?,
        val protocol: String?,
    ) : ConnectionState
    data class Failed(val message: String, val deviceName: String?) : ConnectionState
}

/** One PID's current value plus enough history to draw a sparkline. */
data class LiveValue(
    val pid: Int,
    val readings: List<Reading>,
    val timestamp: Long,
    val history: List<Float> = emptyList(),
) {
    val primary: Reading? get() = readings.firstOrNull()
}

data class DtcSnapshot(
    val stored: List<Dtc>,
    val pending: List<Dtc>,
    val permanent: List<Dtc>,
    val milOn: Boolean,
    val readAt: Long = System.currentTimeMillis(),
) {
    val total: Int get() = stored.size + pending.size + permanent.size
    val all: List<Dtc> get() = stored + pending + permanent
}

/** A snapshot of engine conditions captured by the ECU at the moment a fault was stored. */
data class FreezeFrame(
    val triggerCode: String?,
    val values: List<Pair<Pid, List<Reading>>>,
)

/**
 * Owns the connection and every piece of state derived from it.
 *
 * Deliberately a single object rather than one per screen: the adapter is a single
 * half-duplex resource, so having one place that serialises access to it is what keeps
 * the dashboard polling from colliding with a code read.
 */
class ObdRepository(
    private val context: Context,
    val settings: Settings,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var transport: ObdTransport? = null
    private var elm: Elm327? = null

    /**
     * Guards the adapter against interleaved use. The polling loop takes this for each
     * request, so a one-off action like reading codes simply waits its turn rather than
     * corrupting the stream.
     */
    private val adapterLock = Mutex()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _liveData = MutableStateFlow<Map<Int, LiveValue>>(emptyMap())
    val liveData: StateFlow<Map<Int, LiveValue>> = _liveData.asStateFlow()

    private val _supportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val supportedPids: StateFlow<Set<Int>> = _supportedPids.asStateFlow()

    private val _vehicleInfo = MutableStateFlow<VehicleInfo?>(null)
    val vehicleInfo: StateFlow<VehicleInfo?> = _vehicleInfo.asStateFlow()

    private val _dtcs = MutableStateFlow<DtcSnapshot?>(null)
    val dtcs: StateFlow<DtcSnapshot?> = _dtcs.asStateFlow()

    private val _readiness = MutableStateFlow<Readiness?>(null)
    val readiness: StateFlow<Readiness?> = _readiness.asStateFlow()

    private val _freezeFrame = MutableStateFlow<FreezeFrame?>(null)
    val freezeFrame: StateFlow<FreezeFrame?> = _freezeFrame.asStateFlow()

    private val _monitorTests = MutableStateFlow<List<MonitorTest>>(emptyList())
    val monitorTests: StateFlow<List<MonitorTest>> = _monitorTests.asStateFlow()

    /** Requests per second actually achieved, which varies hugely between adapters. */
    private val _pollRate = MutableStateFlow(0.0)
    val pollRate: StateFlow<Double> = _pollRate.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    val tripLogger = TripLogger(context)

    private var pollJob: Job? = null
    private var pollTargets: List<Int> = emptyList()

    /**
     * PIDs the car claimed to support but which then returned nothing. Some ECUs
     * over-report; polling them forever would waste a third of the available bandwidth.
     */
    private val deadPids = mutableSetOf<Int>()
    private val failureCounts = mutableMapOf<Int, Int>()

    // ---------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------

    suspend fun connect(device: AdapterDevice) {
        disconnect()

        _connectionState.value = ConnectionState.Connecting("Opening ${device.name}")

        val newTransport = when (device.kind) {
            AdapterKind.BLE -> BleTransport(context, device)
            AdapterKind.CLASSIC_BLUETOOTH -> ClassicBtTransport(context, device)
            AdapterKind.WIFI -> {
                val host = device.address.substringBefore(':', WifiTransport.DEFAULT_HOST)
                val port = device.address.substringAfter(':', "").toIntOrNull() ?: WifiTransport.DEFAULT_PORT
                WifiTransport(host, port)
            }
            AdapterKind.DEMO -> DemoTransport()
        }

        try {
            newTransport.connect()
        } catch (e: ObdConnectionException) {
            _connectionState.value = ConnectionState.Failed(e.message ?: "Connection failed", device.name)
            return
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(
                e.message ?: "Unexpected error opening ${device.name}", device.name,
            )
            return
        }

        transport = newTransport
        val session = Elm327(newTransport, scope)
        elm = session
        session.start()

        val init = try {
            session.initialise { step -> _connectionState.value = ConnectionState.Connecting(step) }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(
                e.message ?: "The adapter didn't respond to setup commands", device.name,
            )
            newTransport.close()
            transport = null
            elm = null
            return
        }

        if (!init.success) {
            // The adapter is alive but the car isn't answering. Stay connected: the user
            // can still use the terminal, and turning the ignition on may be all it needs.
            _connectionState.value = ConnectionState.Failed(
                init.message ?: "Couldn't talk to the vehicle", device.name,
            )
            settings.lastDevice = Settings.SavedDevice(device.name, device.address, device.kind)
            return
        }

        _connectionState.value = ConnectionState.Connected(
            deviceName = device.name,
            kind = device.kind,
            adapter = init.adapter,
            protocol = init.protocol,
        )
        settings.lastDevice = Settings.SavedDevice(device.name, device.address, device.kind)

        _vehicleInfo.value = VehicleInfo(
            protocol = init.protocol,
            adapter = init.adapter,
            batteryVoltage = init.batteryVoltage,
        )

        scope.launch { discoverSupportedPids() }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        elm?.stop()
        elm = null
        transport?.close()
        transport = null
        deadPids.clear()
        failureCounts.clear()
        _liveData.value = emptyMap()
        _supportedPids.value = emptySet()
        _pollRate.value = 0.0
        _connectionState.value = ConnectionState.Disconnected
    }

    val isConnected: Boolean
        get() = transport?.isConnected == true

    // ---------------------------------------------------------------------------------
    // Capability discovery
    // ---------------------------------------------------------------------------------

    /**
     * Walks the support bitmaps to learn which PIDs this car implements.
     *
     * Each bitmap's last bit says whether the next block exists, so the walk stops as
     * soon as a car says it has no more — asking anyway just wastes time on slow adapters.
     */
    suspend fun discoverSupportedPids() {
        val session = elm ?: return
        _busy.value = "Checking what this car supports"
        val supported = mutableSetOf<Int>()

        try {
            for (base in PidRegistry.SUPPORT_PIDS) {
                val result = adapterLock.withLock { session.obd(0x01, base, expectedResponses = 1) }
                if (!result.isSuccess || result.data.size < 4) break
                val block = ObdParser.decodeSupportedPids(result.data, base)
                supported += block
                // Bit 0 of the last byte means "the next block of 32 PIDs is described too".
                val hasNext = result.data[3] and 0x01 != 0
                if (!hasNext) break
            }
        } finally {
            _busy.value = null
        }

        supported += PidRegistry.SUPPORT_PIDS.first()
        _supportedPids.value = supported
        Log.i(TAG, "Car supports ${supported.size} PIDs")

        // Keep the dashboard honest: drop anything the car doesn't actually have.
        val requested = settings.dashboardPids.value
        val usable = requested.filter { it in supported }
        if (usable.size != requested.size) {
            val replacements = PidRegistry.DEFAULT_DASHBOARD.filter { it in supported && it !in usable }
            settings.setDashboardPids((usable + replacements).distinct().take(8))
        }
    }

    // ---------------------------------------------------------------------------------
    // Live polling
    // ---------------------------------------------------------------------------------

    /**
     * Sets which PIDs the background loop reads.
     *
     * Restarting rather than mutating in place is deliberate: the loop's timing state
     * (rate estimate, failure counts) belongs to one set of targets.
     */
    fun startPolling(pids: List<Int>) {
        val targets = pids.filter { PidRegistry.isPollable(it) }.distinct()
        if (targets == pollTargets && pollJob?.isActive == true) return

        pollJob?.cancel()
        pollTargets = targets
        if (targets.isEmpty()) return

        pollJob = scope.launch {
            var samples = 0
            var window = System.currentTimeMillis()

            while (isActive) {
                val session = elm
                if (session == null || transport?.isConnected != true) {
                    delay(500)
                    continue
                }

                val active = pollTargets.filter { it !in deadPids }
                if (active.isEmpty()) {
                    delay(1000)
                    continue
                }

                for (pid in active) {
                    if (!isActive) break
                    readAndStore(session, pid)
                    samples++
                }

                val now = System.currentTimeMillis()
                val elapsed = now - window
                if (elapsed >= 1000) {
                    _pollRate.value = samples * 1000.0 / elapsed
                    samples = 0
                    window = now
                }

                // A short yield keeps the UI responsive without meaningfully slowing
                // the loop — the adapter round trip dominates by orders of magnitude.
                delay(10)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        pollTargets = emptyList()
        _pollRate.value = 0.0
    }

    private suspend fun readAndStore(session: Elm327, pidId: Int) {
        val definition = PidRegistry[pidId] ?: return
        val result = adapterLock.withLock {
            session.obd(0x01, pidId, expectedResponses = 1)
        }

        if (!result.isSuccess) {
            if (result.error == ObdError.NOT_CONNECTED) return
            // NO_DATA on a PID the car claimed to support means it over-reported.
            // Retire it after a few tries rather than burning bandwidth forever.
            val count = (failureCounts[pidId] ?: 0) + 1
            failureCounts[pidId] = count
            if (count >= MAX_PID_FAILURES) {
                deadPids += pidId
                Log.i(TAG, "Retiring PID ${definition.hex} after $count failures")
            }
            return
        }

        failureCounts.remove(pidId)
        val readings = definition.decode(result.data)
        if (readings.isEmpty()) return

        val now = System.currentTimeMillis()
        _liveData.update { current ->
            val previous = current[pidId]
            val history = ((previous?.history ?: emptyList()) + readings.first().value.toFloat())
                .takeLast(HISTORY_POINTS)
            current + (pidId to LiveValue(pidId, readings, now, history))
        }

        tripLogger.record(pidId, definition, readings, now)
    }

    /** Reads one PID immediately, outside the polling rotation. */
    suspend fun readPid(pidId: Int): List<Reading> {
        val session = elm ?: return emptyList()
        val definition = PidRegistry[pidId] ?: return emptyList()
        val result = adapterLock.withLock {
            session.obd(0x01, pidId, expectedResponses = 1)
        }
        return if (result.isSuccess) definition.decode(result.data) else emptyList()
    }

    // ---------------------------------------------------------------------------------
    // Diagnostic trouble codes
    // ---------------------------------------------------------------------------------

    suspend fun refreshDtcs(): DtcSnapshot? {
        val session = elm ?: return null
        _busy.value = "Reading fault codes"
        try {
            // Services 03, 07 and 0A are three different lists, and a car can have codes
            // in one but not the others. Reading only 03 is why some scanners miss faults.
            val stored = readDtcList(session, 0x03, DtcStatus.STORED)
            val pending = readDtcList(session, 0x07, DtcStatus.PENDING)
            val permanent = readDtcList(session, 0x0A, DtcStatus.PERMANENT)

            val status = adapterLock.withLock { session.obd(0x01, 0x01, expectedResponses = 1) }
            val readinessData = if (status.isSuccess) Readiness.decode(status.data) else null
            readinessData?.let { _readiness.value = it }

            val snapshot = DtcSnapshot(
                stored = stored,
                pending = pending,
                permanent = permanent,
                milOn = readinessData?.milOn ?: stored.isNotEmpty(),
            )
            _dtcs.value = snapshot
            return snapshot
        } finally {
            _busy.value = null
        }
    }

    private suspend fun readDtcList(session: Elm327, mode: Int, status: DtcStatus): List<Dtc> {
        val result = adapterLock.withLock {
            session.obd(mode, null, timeoutMs = Elm327.SLOW_TIMEOUT_MS)
        }
        // NO_DATA here is the normal, healthy answer: it means no codes of that kind.
        if (!result.isSuccess) return emptyList()
        return Dtc.decodeList(result.data, status)
    }

    /**
     * Clears stored codes and turns the engine light off.
     *
     * Worth knowing what this actually does, because it is widely misunderstood: it
     * erases the stored and pending codes, the freeze frame, and resets every readiness
     * monitor to "not complete". It does not fix anything, and it does not clear
     * permanent codes. If the fault is still present the light comes back, usually within
     * a few dozen miles.
     */
    suspend fun clearDtcs(): ClearResult {
        val session = elm ?: return ClearResult(false, "Not connected to the car")
        _busy.value = "Clearing codes"
        try {
            val result = adapterLock.withLock {
                session.obd(0x04, null, timeoutMs = Elm327.SLOW_TIMEOUT_MS)
            }
            if (!result.isSuccess && result.error != ObdError.NO_DATA) {
                return ClearResult(false, "The car refused the clear request (${result.error}). Try again with the engine off and the ignition on.")
            }

            _freezeFrame.value = null
            _monitorTests.value = emptyList()
            delay(500)
            val after = refreshDtcs()
            refreshReadiness()

            val remaining = after?.stored?.size ?: 0
            return if (remaining == 0) {
                ClearResult(
                    true,
                    "Codes cleared. Readiness monitors have reset — the car needs a normal drive cycle " +
                        "before they'll be complete again, which matters if an emissions test is coming up.",
                )
            } else {
                ClearResult(
                    false,
                    "$remaining code${if (remaining == 1) "" else "s"} came straight back, which means the " +
                        "fault is still present rather than a leftover from an earlier repair.",
                )
            }
        } finally {
            _busy.value = null
        }
    }

    data class ClearResult(val success: Boolean, val message: String)

    // ---------------------------------------------------------------------------------
    // Readiness, freeze frame, service 06
    // ---------------------------------------------------------------------------------

    suspend fun refreshReadiness(): Readiness? {
        val session = elm ?: return null
        _busy.value = "Reading emissions monitors"
        try {
            val result = adapterLock.withLock { session.obd(0x01, 0x01, expectedResponses = 1) }
            if (!result.isSuccess) return null
            val readiness = Readiness.decode(result.data)
            _readiness.value = readiness
            return readiness
        } finally {
            _busy.value = null
        }
    }

    /**
     * Reads the freeze frame — the sensor snapshot the ECU saved when it stored a code.
     *
     * This is the single most useful thing for diagnosis, because it says what the engine
     * was doing at the moment the fault appeared: cold or hot, idling or at load, which
     * usually narrows the cause dramatically.
     */
    suspend fun refreshFreezeFrame(): FreezeFrame? {
        val session = elm ?: return null
        _busy.value = "Reading freeze frame"
        try {
            val trigger = adapterLock.withLock { session.obd(0x02, 0x02, expectedResponses = 1) }
            val triggerCode = if (trigger.isSuccess && trigger.data.size >= 3) {
                // Byte 0 is the frame number; the code itself is the two after it.
                Dtc.decodePair(trigger.data[1], trigger.data[2])
            } else if (trigger.isSuccess && trigger.data.size >= 2) {
                Dtc.decodePair(trigger.data[0], trigger.data[1])
            } else {
                null
            }

            val captured = mutableListOf<Pair<Pid, List<Reading>>>()
            for (pidId in FREEZE_FRAME_PIDS) {
                val definition = PidRegistry[pidId] ?: continue
                if (_supportedPids.value.isNotEmpty() && pidId !in _supportedPids.value) continue

                val result = adapterLock.withLock {
                    session.obd(0x02, pidId, expectedResponses = 1)
                }
                if (!result.isSuccess) continue

                // Mode 02 prefixes the payload with the frame number, so a response one
                // byte longer than the PID's own width has the frame number to strip.
                val payload = if (result.data.size == definition.bytes + 1) {
                    result.data.drop(1).toIntArray()
                } else {
                    result.data
                }
                val readings = definition.decode(payload)
                if (readings.isNotEmpty()) captured += definition to readings
            }

            if (triggerCode == null && captured.isEmpty()) {
                _freezeFrame.value = null
                return null
            }

            val frame = FreezeFrame(triggerCode, captured)
            _freezeFrame.value = frame
            return frame
        } finally {
            _busy.value = null
        }
    }

    suspend fun refreshMonitorTests(): List<MonitorTest> {
        val session = elm ?: return emptyList()
        _busy.value = "Reading on-board test results"
        try {
            val collected = mutableListOf<MonitorTest>()

            // Ask which monitor IDs exist, then read each. Requesting 0x00 returns a
            // bitmap in the same shape as the PID support bitmaps.
            val available = mutableSetOf<Int>()
            for (base in listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0)) {
                val result = adapterLock.withLock {
                    session.obd(0x06, base, timeoutMs = Elm327.SLOW_TIMEOUT_MS)
                }
                if (!result.isSuccess || result.data.size < 4) break
                available += ObdParser.decodeSupportedPids(result.data, base)
                if (result.data[3] and 0x01 == 0) break
            }

            for (mid in available.sorted().take(MAX_MONITOR_IDS)) {
                // echoesPid = false: service 06 repeats the monitor ID as the first byte
                // of every test record, so it is payload here, not an echo to discard.
                val result = adapterLock.withLock {
                    session.obd(0x06, mid, timeoutMs = Elm327.SLOW_TIMEOUT_MS, echoesPid = false)
                }
                if (!result.isSuccess) continue
                collected += Mode06.decode(result.data)
            }

            _monitorTests.value = collected
            return collected
        } finally {
            _busy.value = null
        }
    }

    // ---------------------------------------------------------------------------------
    // Vehicle identity
    // ---------------------------------------------------------------------------------

    suspend fun refreshVehicleInfo(): VehicleInfo? {
        val session = elm ?: return null
        _busy.value = "Reading vehicle information"
        try {
            val vin = adapterLock.withLock {
                session.obd(0x09, Mode09.PID_VIN, timeoutMs = Elm327.SLOW_TIMEOUT_MS)
            }.let { if (it.isSuccess) Mode09.parseVin(it.data) else null }

            val calibrations = adapterLock.withLock {
                session.obd(0x09, Mode09.PID_CALIBRATION_ID, timeoutMs = Elm327.SLOW_TIMEOUT_MS)
            }.let { if (it.isSuccess) Mode09.parseCalibrationIds(it.data) else emptyList() }

            val cvns = adapterLock.withLock {
                session.obd(0x09, Mode09.PID_CVN, timeoutMs = Elm327.SLOW_TIMEOUT_MS)
            }.let { if (it.isSuccess) Mode09.parseCvns(it.data) else emptyList() }

            val ecuName = adapterLock.withLock {
                session.obd(0x09, Mode09.PID_ECU_NAME, timeoutMs = Elm327.SLOW_TIMEOUT_MS)
            }.let { if (it.isSuccess) Mode09.parseEcuName(it.data) else null }

            val standard = adapterLock.withLock { session.obd(0x01, 0x1C) }
                .let { if (it.isSuccess && it.data.isNotEmpty()) PidRegistry.obdStandard(it.data[0]) else null }

            val fuel = adapterLock.withLock { session.obd(0x01, 0x51) }
                .let { if (it.isSuccess && it.data.isNotEmpty()) PidRegistry.fuelType(it.data[0]) else null }

            val voltage = runCatching {
                adapterLock.withLock { session.command("ATRV") }
                    .lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            }.getOrNull()

            val info = VehicleInfo(
                vin = vin,
                calibrationIds = calibrations,
                calibrationVerificationNumbers = cvns,
                ecuName = ecuName,
                protocol = session.protocolDescription,
                adapter = session.adapterIdentity,
                batteryVoltage = voltage,
                obdStandard = standard,
                fuelType = fuel,
                supportedPids = _supportedPids.value,
            )
            _vehicleInfo.value = info
            return info
        } finally {
            _busy.value = null
        }
    }

    // ---------------------------------------------------------------------------------
    // Terminal
    // ---------------------------------------------------------------------------------

    /** Sends an arbitrary command. Used by the terminal screen. */
    suspend fun sendRaw(command: String): String = withContext(Dispatchers.IO) {
        val session = elm ?: return@withContext "Not connected"
        try {
            adapterLock.withLock {
                session.command(command.trim().uppercase(), Elm327.SLOW_TIMEOUT_MS)
            }.trim()
        } catch (e: Exception) {
            e.message ?: "Command failed"
        }
    }

    private fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
        while (true) {
            val current = value
            if (compareAndSet(current, transform(current))) return
        }
    }

    companion object {
        private const val TAG = "ObdRepository"
        private const val HISTORY_POINTS = 120
        private const val MAX_PID_FAILURES = 3
        private const val MAX_MONITOR_IDS = 24

        /**
         * The PIDs worth capturing from a freeze frame. Reading all of them would take
         * a minute on a slow adapter for very little extra diagnostic value.
         */
        val FREEZE_FRAME_PIDS = listOf(
            0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D,
            0x0E, 0x0F, 0x10, 0x11, 0x1F, 0x2F, 0x42, 0x43, 0x46, 0x5C,
        )
    }
}
