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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Non-zero while a one-off read (fault codes, freeze frame, service 06) is running.
     *
     * Without this, a foreground read merely competes with the dashboard's polling loop
     * for [adapterLock] and ends up interleaved one-for-one with it. The mutex keeps that
     * correct but not fast: a freeze frame is twenty requests, so interleaving doubles it
     * to forty round trips, and on a BLE adapter managing five a second that is the
     * difference between four seconds and eight. Pausing the loop for the duration costs
     * a moment of stale gauges and halves the wait.
     *
     * Counted rather than boolean because these operations nest — clearing codes re-reads
     * them afterwards — and a plain flag would be cleared by the inner call while the
     * outer one was still going.
     */
    private val exclusiveDepth = AtomicInteger(0)

    /**
     * Runs [block] with the polling loop held off, publishing [label] as the busy state.
     */
    private suspend fun <T> exclusive(label: String, block: suspend () -> T): T {
        exclusiveDepth.incrementAndGet()
        _busy.value = label
        try {
            return block()
        } finally {
            if (exclusiveDepth.decrementAndGet() == 0) _busy.value = null
        }
    }

    val tripLogger = TripLogger(context)
    val garage = Garage(context)

    private val abnormalMonitor = AbnormalReadingMonitor()

    /** The car currently plugged in, once it has been identified. */
    private val _currentVehicle = MutableStateFlow<Vehicle?>(null)
    val currentVehicle: StateFlow<Vehicle?> = _currentVehicle.asStateFlow()

    /** Cleared on each connection, so one reading is kept per session. */
    private var odometerRecorded = false

    private var pollJob: Job? = null
    private var pollTargets: List<Int> = emptyList()

    /**
     * PIDs the car claimed to support but which then returned nothing. Some ECUs
     * over-report; polling them forever would waste a third of the available bandwidth.
     */
    private val deadPids = mutableSetOf<Int>()

    /** When each retired PID may be tried again. */
    private val retiredAt = mutableMapOf<Int, Long>()

    /**
     * PIDs the car answers with saturated bytes every single time.
     *
     * Distinct from [deadPids], which is about a parameter that fails to answer. This is a
     * parameter that answers confidently and wrongly, which is worse: FF decodes to a
     * perfectly legal full-scale value, so the gauge shows a number rather than a gap and
     * nothing about it looks broken.
     *
     * Not retried during a session. Unlike a dropped reply, which may be the bus being
     * busy, this does not change while the engine is running — and bringing the gauge back
     * every minute to show 100% again would be worse than leaving it out.
     */
    private val unsupportedPids = mutableSetOf<Int>()
    private val saturatedCounts = mutableMapOf<Int, Int>()
    private val failureCounts = mutableMapOf<Int, Int>()

    // ---------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------

    suspend fun connect(device: AdapterDevice) {
        disconnect()

        _connectionState.value = ConnectionState.Connecting("Opening ${device.name}")

        val newTransport = when (device.kind) {
            AdapterKind.BLE -> BleTransport(context, device)
            AdapterKind.CLASSIC_BLUETOOTH -> ClassicBtTransport(context, device).apply {
                onProgress = { step -> _connectionState.value = ConnectionState.Connecting(step) }
            }
            AdapterKind.WIFI -> {
                val host = device.address.substringBefore(':', WifiTransport.DEFAULT_HOST)
                val port = device.address.substringAfter(':', "").toIntOrNull() ?: WifiTransport.DEFAULT_PORT
                WifiTransport(host, port)
            }
            AdapterKind.DEMO -> DemoTransport()
        }

        // A backstop over the whole opening phase. Each transport bounds its own attempts,
        // but a stall anywhere in here used to leave the UI on "Opening" with no timeout and
        // no way back, which is indistinguishable from the app having crashed.
        try {
            withTimeout(OPEN_TIMEOUT_MS) { newTransport.connect() }
        } catch (e: TimeoutCancellationException) {
            runCatching { newTransport.close() }
            _connectionState.value = ConnectionState.Failed(
                "${device.name} didn't respond within ${OPEN_TIMEOUT_MS / 1000} seconds.\n\n" +
                    "Check the adapter is plugged in and its light is on — the OBD socket " +
                    "has no power until the ignition is at position II.",
                device.name,
            )
            return
        } catch (e: ObdConnectionException) {
            runCatching { newTransport.close() }
            _connectionState.value = ConnectionState.Failed(e.message ?: "Connection failed", device.name)
            return
        } catch (e: Exception) {
            runCatching { newTransport.close() }
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

        abnormalMonitor.reset()

        scope.launch {
            discoverSupportedPids()
            // Identifying needs the VIN, so this waits for the service 09 read rather
            // than racing it — a car filed under "unidentified" and then again under its
            // VIN would split its own history in two.
            refreshVehicleInfo()
            _currentVehicle.value = garage.identify(_vehicleInfo.value, device.address)
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        elm?.stop()
        elm = null
        transport?.close()
        transport = null
        deadPids.clear()
        retiredAt.clear()
        unsupportedPids.clear()
        saturatedCounts.clear()
        odometerRecorded = false
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
        val supported = mutableSetOf<Int>()

        exclusive("Checking what this car supports") {
            for (base in PidRegistry.SUPPORT_PIDS) {
                val result = adapterLock.withLock { session.obd(0x01, base, expectedResponses = 1) }
                if (!result.isSuccess || result.data.size < 4) break
                val block = ObdParser.decodeSupportedPids(result.data, base)
                supported += block
                // Bit 0 of the last byte means "the next block of 32 PIDs is described too".
                val hasNext = result.data[3] and 0x01 != 0
                if (!hasNext) break
            }
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
            var pass = 0
            var window = System.currentTimeMillis()

            while (isActive) {
                val session = elm
                if (session == null || transport?.isConnected != true) {
                    delay(500)
                    continue
                }

                if (exclusiveDepth.get() > 0) {
                    // A foreground read owns the adapter for now.
                    delay(POLL_YIELD_MS)
                    continue
                }

                // Give retired parameters another chance periodically rather than writing
                // them off for the rest of the session.
                val now0 = System.currentTimeMillis()
                retiredAt.entries.filter { now0 - it.value >= RETIRE_RETRY_MS }.forEach {
                    deadPids -= it.key
                    failureCounts.remove(it.key)
                }
                retiredAt.keys.removeAll { it !in deadPids }

                val active = pollTargets.filter { it !in deadPids && it !in unsupportedPids }
                if (active.isEmpty()) {
                    delay(1000)
                    continue
                }

                // Not every parameter deserves the same share of a slow bus.
                //
                // Coolant temperature moves over minutes; engine speed and throttle move
                // faster than the eye. Polling them equally spent most of the available
                // bandwidth re-reading numbers that had not changed, which is what made
                // the gauges that matter feel laggy — five parameters at five reads a
                // second is one update per second each, whether or not it was worth it.
                //
                // Slow-moving parameters are read every fourth pass instead, which roughly
                // doubles the rate of the ones being watched.
                for (pid in active) {
                    if (!isActive) break
                    if (pid in SLOW_MOVING && pass % 4 != 0) continue
                    readAndStore(session, pid)
                    samples++
                }
                pass++

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
                retiredAt[pidId] = System.currentTimeMillis()
                Log.i(TAG, "Retiring PID ${definition.hex} after $count failures")
            }
            return
        }

        failureCounts.remove(pidId)

        // A parameter pinned to full scale forever is a stub, not a sensor.
        //
        // FF is what an ECU pads with and what many older ones return for something they
        // report as supported but never implemented. Decoded, it is a legal maximum —
        // 100% engine load, 255 kPa, 1.0 lambda — so it renders as a confident reading
        // and the gauge looks like it is working.
        //
        // The discriminator is persistence, not the value. A real reading can touch full
        // scale; a genuine 100% load happens under hard acceleration. What never happens
        // is full scale on twenty consecutive reads, roughly twenty seconds, spanning
        // idle and cruise alike. That is the case being caught here.
        if (result.data.isNotEmpty() && result.data.all { it == 0xFF }) {
            val saturated = (saturatedCounts[pidId] ?: 0) + 1
            saturatedCounts[pidId] = saturated
            if (saturated >= MAX_SATURATED_READS) {
                unsupportedPids += pidId
                saturatedCounts.remove(pidId)
                // Drop the last value too, so the gauge falls back to "no reading" rather
                // than freezing on the fiction it was showing.
                _liveData.update { it - pidId }
                Log.i(
                    TAG,
                    "Treating PID ${definition.hex} (${definition.name}) as unsupported: " +
                        "answered full-scale $saturated times running",
                )
                return
            }
        } else {
            saturatedCounts.remove(pidId)
        }

        val readings = definition.decode(result.data)
        if (readings.isEmpty()) return

        // The odometer is worth keeping outside the car, because the car keeps no history
        // of it — only the current number. Recorded once a session rather than on every
        // read, since it changes by a tenth of a kilometre at a time and a log of that
        // would be noise.
        if (pidId == PID_ODOMETER && !odometerRecorded) {
            readings.firstOrNull()?.value?.takeIf { it > 0 }?.let { km ->
                odometerRecorded = true
                _currentVehicle.value?.let { vehicle -> recordMileage(vehicle, km) }
            }
        }

        val now = System.currentTimeMillis()

        // A reading outside its healthy range is worth a dated note in the car's history,
        // whether or not the ECU ever considers it bad enough to store a code.
        readings.firstOrNull()?.let { primary ->
            abnormalMonitor.observe(pidId, primary.value)?.let { abnormal ->
                _currentVehicle.value?.let { vehicle ->
                    garage.record(
                        vehicle.key,
                        VehicleHistoryEvent(
                            timestamp = now,
                            type = EventType.ABNORMAL,
                            title = "${abnormal.label}: ${"%.1f".format(java.util.Locale.UK, abnormal.value)} ${abnormal.unit}".trim(),
                            detail = "${abnormal.severity.label}. ${abnormal.message}",
                        ),
                    )
                }
            }
        }

        _liveData.update { current ->
            val previous = current[pidId]
            val history = ((previous?.history ?: emptyList()) + readings.first().value.toFloat())
                .takeLast(HISTORY_POINTS)
            current + (pidId to LiveValue(pidId, readings, now, history))
        }

        tripLogger.record(pidId, definition, readings, now)
    }

    /**
     * Files an odometer reading, and raises it as an event if it went backwards.
     *
     * A decrease is recorded at the severity it deserves. Barring a replaced cluster or
     * ECU, an odometer does not run backwards, and this record — kept outside the car,
     * with dates — is the only place that comparison can be made at all.
     */
    private fun recordMileage(vehicle: Vehicle, km: Double) {
        val verdict = garage.recordOdometer(vehicle.key, km)
        val miles = km * 0.621371
        val now = System.currentTimeMillis()

        when (verdict) {
            MileageVerdict.WENT_BACKWARDS -> {
                val highest = garage.odometerHistory(vehicle.key).maxByOrNull { it.km }
                garage.record(
                    vehicle.key,
                    VehicleHistoryEvent(
                        timestamp = now,
                        type = EventType.MILEAGE,
                        title = "Odometer went backwards",
                        detail = "Read ${"%,.0f".format(java.util.Locale.UK, miles)} miles, " +
                            "having previously recorded " +
                            "${"%,.0f".format(java.util.Locale.UK, (highest?.km ?: km) * 0.621371)} miles.\n\n" +
                            "An odometer does not go backwards. The innocent explanations are a " +
                            "replaced instrument cluster or engine ECU; the other one is that it " +
                            "has been altered. Either way it is worth knowing about, and the car " +
                            "cannot tell you — it stores the current number and no history.",
                    ),
                )
                Log.w(TAG, "Odometer decreased for ${vehicle.key}: $km km")
            }
            MileageVerdict.FIRST -> garage.record(
                vehicle.key,
                VehicleHistoryEvent(
                    timestamp = now,
                    type = EventType.MILEAGE,
                    title = "${"%,.0f".format(java.util.Locale.UK, miles)} miles",
                    detail = "First odometer reading recorded for this car. Later readings are " +
                        "compared against it.",
                ),
            )
            MileageVerdict.CONSISTENT -> Unit
        }
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
        return exclusive("Reading fault codes") {
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
            recordCodesIfChanged(snapshot)
            return@exclusive snapshot
        }
    }

    private suspend fun readDtcList(session: Elm327, mode: Int, status: DtcStatus): List<Dtc> {
        val result = adapterLock.withLock {
            session.obd(mode, null, timeoutMs = Elm327.SLOW_TIMEOUT_MS)
        }
        // NO_DATA here is the normal, healthy answer: it means no codes of that kind.
        if (!result.isSuccess) return emptyList()

        // Each message is decoded on its own rather than concatenating the bytes first:
        // every message carries its own padding, and on CAN its own leading count byte,
        // so merging the raw bytes would confuse the alignment detection.
        val messages = ObdParser.parseMessages(result.raw, mode)
        return when {
            messages.size > 1 -> messages
                .flatMap { Dtc.decodeList(it, status) }
                .distinctBy { it.code }
            else -> Dtc.decodeList(result.data, status)
        }
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

        // Snapshot the codes and freeze frame into the car's history first. Once mode 04
        // runs they are gone from the ECU for good, and the history is then the only
        // record that this fault ever happened — which is the difference between "a new
        // fault" and "the same fault for the fourth time".
        recordClearedCodes()

        return exclusive("Clearing codes") {
            val result = adapterLock.withLock {
                session.obd(0x04, null, timeoutMs = Elm327.SLOW_TIMEOUT_MS)
            }
            if (!result.isSuccess && result.error != ObdError.NO_DATA) {
                return@exclusive ClearResult(false, "The car refused the clear request (${result.error}). Try again with the engine off and the ignition on.")
            }

            _freezeFrame.value = null
            _monitorTests.value = emptyList()
            delay(500)
            val after = refreshDtcs()
            refreshReadiness()

            val remaining = after?.stored?.size ?: 0
            return@exclusive if (remaining == 0) {
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
        }
    }

    data class ClearResult(val success: Boolean, val message: String)

    /**
     * Logs a fault-code event only when the set of codes has changed since last time.
     *
     * Reading codes three times in a row is one fact, not three, and a history padded
     * with duplicates is a history nobody reads.
     */
    private fun recordCodesIfChanged(snapshot: DtcSnapshot) {
        val vehicle = _currentVehicle.value ?: return
        val current = snapshot.all.map { it.code }.toSet()
        if (current == vehicle.lastCodes) return

        if (current.isNotEmpty()) {
            garage.record(
                vehicle.key,
                VehicleHistoryEvent(
                    timestamp = System.currentTimeMillis(),
                    type = EventType.CODES_FOUND,
                    title = "${current.size} fault code${if (current.size == 1) "" else "s"} present",
                    detail = snapshot.all.joinToString("\n") { "${it.code} — ${it.description}" },
                ),
            )
        }
        garage.updateLastCodes(vehicle.key, current)
        _currentVehicle.value = garage.vehicle(vehicle.key)
    }

    /** Writes the current codes and freeze frame to history before mode 04 erases them. */
    private fun recordClearedCodes() {
        val vehicle = _currentVehicle.value ?: return
        val snapshot = _dtcs.value
        val frame = _freezeFrame.value

        val detail = buildString {
            if (snapshot == null || snapshot.total == 0) {
                append("No codes were stored at the time of clearing.")
            } else {
                appendLine("Codes erased:")
                snapshot.all.forEach { appendLine("  ${it.code} — ${it.description}") }
            }
            if (frame != null && frame.values.isNotEmpty()) {
                appendLine()
                appendLine("Conditions when the fault was stored:")
                frame.values.forEach { (pid, readings) ->
                    readings.firstOrNull()?.let { reading ->
                        appendLine("  ${pid.name}: ${"%.1f".format(java.util.Locale.UK, reading.value)} ${reading.unit}".trimEnd())
                    }
                }
            }
        }.trim()

        val count = snapshot?.total ?: 0
        garage.record(
            vehicle.key,
            VehicleHistoryEvent(
                timestamp = System.currentTimeMillis(),
                type = EventType.CODES_CLEARED,
                title = if (count == 0) "Codes cleared (none stored)"
                        else "$count code${if (count == 1) "" else "s"} cleared",
                detail = detail,
            ),
        )
        // The car now has no codes, so the next read is a genuinely new observation.
        garage.updateLastCodes(vehicle.key, emptySet())
        _currentVehicle.value = garage.vehicle(vehicle.key)
    }

    /** Called by the view model when a trip recording finishes. */
    fun recordTrip(summary: String, detail: String) {
        val vehicle = _currentVehicle.value ?: return
        garage.record(
            vehicle.key,
            VehicleHistoryEvent(System.currentTimeMillis(), EventType.TRIP, summary, detail),
        )
    }

    // ---------------------------------------------------------------------------------
    // Readiness, freeze frame, service 06
    // ---------------------------------------------------------------------------------

    suspend fun refreshReadiness(): Readiness? {
        val session = elm ?: return null
        return exclusive("Reading emissions monitors") {
            val result = adapterLock.withLock { session.obd(0x01, 0x01, expectedResponses = 1) }
            if (!result.isSuccess) return@exclusive null
            val readiness = Readiness.decode(result.data)
            _readiness.value = readiness
            return@exclusive readiness
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
        return exclusive("Reading freeze frame") {
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
                return@exclusive null
            }

            val frame = FreezeFrame(triggerCode, captured)
            _freezeFrame.value = frame
            return@exclusive frame
        }
    }

    suspend fun refreshMonitorTests(): List<MonitorTest> {
        val session = elm ?: return emptyList()
        return exclusive("Reading on-board test results") {
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
            return@exclusive collected
        }
    }

    // ---------------------------------------------------------------------------------
    // Vehicle identity
    // ---------------------------------------------------------------------------------

    suspend fun refreshVehicleInfo(): VehicleInfo? {
        val session = elm ?: return null
        return exclusive("Reading vehicle information") {
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
            return@exclusive info
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
        /**
         * Consecutive failures before a parameter is dropped from the rotation.
         *
         * Three was far too few. The older buses drop the occasional reply as a matter of
         * course, so three unlucky reads in a row is an ordinary event rather than
         * evidence of anything — and the consequence was a gauge going blank partway
         * through a drive and never coming back, because retirement only lifted on
         * reconnect. Twenty consecutive failures is a real signal; a handful is weather.
         */
        private const val MAX_PID_FAILURES = 20

        /**
         * How long a retired parameter stays retired before it is tried again.
         *
         * Retirement exists to stop bandwidth being wasted on a parameter the car claimed
         * to support and doesn't, which is worth avoiding on a link this slow. It should
         * not be a life sentence: a bus that was busy earlier may not be now.
         */
        private const val RETIRE_RETRY_MS = 60_000L

        /**
         * Consecutive full-scale readings before a parameter is treated as not implemented.
         *
         * Twenty, at roughly one read a second, is twenty seconds of an absolutely
         * unchanging maximum. A real sensor does not do that — even at sustained full
         * throttle the last digit moves.
         */
        private const val MAX_SATURATED_READS = 20

        /** Odometer, added to the standard in a later revision and rare before about 2018. */
        const val PID_ODOMETER = 0xA6

        /**
         * Parameters that change over minutes rather than moments.
         *
         * Temperatures, fuel level and battery voltage. Reading these as often as engine
         * speed is bandwidth spent confirming that a number has not changed, on a link
         * where bandwidth is the entire constraint.
         */
        private val SLOW_MOVING = setOf(
            0x05, // coolant temperature
            0x0F, // intake air temperature
            0x2F, // fuel level
            0x42, // control module voltage
            0x46, // ambient air temperature
            0x5C, // oil temperature
            0x33, // barometric pressure
        )
        private const val POLL_YIELD_MS = 50L
        private const val MAX_MONITOR_IDS = 24

        /**
         * Backstop for opening the physical link, across every transport.
         *
         * Classic Bluetooth bounds its three attempts at 10 + 8 + 6 seconds plus settling,
         * so this sits above that total: it exists to catch a stall the transport didn't
         * anticipate, not to pre-empt the transport's own, more specific, diagnosis.
         */
        private const val OPEN_TIMEOUT_MS = 30_000L

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
