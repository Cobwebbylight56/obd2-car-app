package com.rhys.obd2.data

import android.content.Context
import android.util.Log
import com.rhys.obd2.obd.Pid
import com.rhys.obd2.obd.Reading
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Running totals for the current recording. */
data class TripStats(
    val startedAt: Long,
    val durationMs: Long = 0,
    val samples: Int = 0,
    val maxSpeed: Double = 0.0,
    val maxRpm: Double = 0.0,
    val maxCoolant: Double = 0.0,
    val distanceKm: Double = 0.0,
    val fuelUsedLitres: Double = 0.0,
    val idleTimeMs: Long = 0,
) {
    /** Litres per 100 km, once enough distance has accumulated for it to mean anything. */
    val economyL100km: Double?
        get() = if (distanceKm > 0.5 && fuelUsedLitres > 0) fuelUsedLitres * 100.0 / distanceKm else null

    val averageSpeed: Double
        get() = if (durationMs > 0) distanceKm / (durationMs / 3_600_000.0) else 0.0
}

/**
 * Records live data to a CSV the user can export, and keeps a running summary of the trip.
 *
 * Rows are written on a fixed interval rather than per sample. Every PID is read at a
 * different moment, so a row is a snapshot of the most recent value of each — which is
 * what makes the file loadable as a normal wide-format table instead of a sparse mess.
 */
class TripLogger(private val context: Context) {

    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()

    private val _stats = MutableStateFlow<TripStats?>(null)
    val stats: StateFlow<TripStats?> = _stats.asStateFlow()

    private var writer: java.io.BufferedWriter? = null
    private var currentFile: File? = null
    private var columns: List<Pid> = emptyList()
    private val latest = mutableMapOf<Int, Double>()
    private var lastRowAt = 0L
    private var lastSampleAt = 0L
    private var startedAt = 0L

    private val logDirectory: File
        get() = File(context.filesDir, "logs").apply { mkdirs() }

    /** @param pids the columns to record, in order */
    @Synchronized
    fun start(pids: List<Pid>) {
        if (_isLogging.value) return
        if (pids.isEmpty()) return

        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.UK).format(Date())
        val file = File(logDirectory, "trip_$stamp.csv")

        try {
            val out = file.bufferedWriter()
            out.write("timestamp,elapsed_s," + pids.joinToString(",") { column(it) })
            out.newLine()
            out.flush()

            writer = out
            currentFile = file
            columns = pids
            latest.clear()
            startedAt = System.currentTimeMillis()
            lastRowAt = 0L
            lastSampleAt = startedAt
            _stats.value = TripStats(startedAt = startedAt)
            _isLogging.value = true
            Log.i(TAG, "Logging to ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start log", e)
            _isLogging.value = false
        }
    }

    @Synchronized
    fun stop(): File? {
        if (!_isLogging.value) return null
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
        _isLogging.value = false
        val file = currentFile
        currentFile = null
        return file
    }

    /** Called by the repository for every successful read, whether or not logging is on. */
    @Synchronized
    fun record(pidId: Int, definition: Pid, readings: List<Reading>, timestamp: Long) {
        val value = readings.firstOrNull()?.value ?: return
        latest[pidId] = value
        updateStats(pidId, value, timestamp)

        if (!_isLogging.value) return
        if (timestamp - lastRowAt < ROW_INTERVAL_MS) return
        writeRow(timestamp)
        lastRowAt = timestamp
    }

    private fun writeRow(timestamp: Long) {
        val out = writer ?: return
        try {
            val elapsed = (timestamp - startedAt) / 1000.0
            // Locale.ROOT so the decimal separator is always a point: a locale that uses
            // a comma would otherwise split every number across two CSV columns.
            val cells = columns.joinToString(",") { pid ->
                latest[pid.id]?.let { "%.3f".format(Locale.ROOT, it) } ?: ""
            }
            out.write("$timestamp,${"%.2f".format(Locale.ROOT, elapsed)},$cells")
            out.newLine()
        } catch (e: Exception) {
            Log.e(TAG, "Log write failed", e)
        }
    }

    /**
     * Integrates speed into distance and fuel rate into litres.
     *
     * Both are trapezoid-free rectangular integrations over the gap since the last
     * sample, which is accurate enough at the one-to-five-hertz sample rates a typical
     * adapter manages, and honest about being an estimate.
     */
    private fun updateStats(pidId: Int, value: Double, timestamp: Long) {
        val current = _stats.value ?: return
        val gapMs = (timestamp - lastSampleAt).coerceIn(0, 5_000)
        lastSampleAt = timestamp

        var updated = current.copy(
            durationMs = timestamp - current.startedAt,
            samples = current.samples + 1,
        )

        when (pidId) {
            PID_SPEED -> {
                updated = updated.copy(
                    maxSpeed = maxOf(updated.maxSpeed, value),
                    distanceKm = updated.distanceKm + value * (gapMs / 3_600_000.0),
                    idleTimeMs = if (value < 1.0) updated.idleTimeMs + gapMs else updated.idleTimeMs,
                )
            }
            PID_RPM -> updated = updated.copy(maxRpm = maxOf(updated.maxRpm, value))
            PID_COOLANT -> updated = updated.copy(maxCoolant = maxOf(updated.maxCoolant, value))
            PID_FUEL_RATE -> {
                // Value is litres per hour.
                updated = updated.copy(fuelUsedLitres = updated.fuelUsedLitres + value * (gapMs / 3_600_000.0))
            }
            PID_MAF -> {
                // No direct fuel rate PID on most cars, so derive it from air mass:
                // petrol burns roughly 14.7 parts air to 1 part fuel by mass, and petrol
                // is about 745 g per litre. Good to within a few percent in steady
                // cruising, less so under hard acceleration when the mixture enriches.
                if (updated.fuelUsedLitres == 0.0 || !hasDirectFuelRate) {
                    val gramsPerSecond = value / 14.7
                    val litres = gramsPerSecond * (gapMs / 1000.0) / 745.0
                    updated = updated.copy(fuelUsedLitres = updated.fuelUsedLitres + litres)
                }
            }
        }

        if (pidId == PID_FUEL_RATE) hasDirectFuelRate = true
        _stats.value = updated
    }

    private var hasDirectFuelRate = false

    fun listLogs(): List<File> =
        logDirectory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun deleteLog(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    private fun column(pid: Pid): String {
        val unit = pid.unit.ifEmpty { "value" }
        return "${pid.name} ($unit)".replace(',', ';')
    }

    companion object {
        private const val TAG = "TripLogger"
        private const val ROW_INTERVAL_MS = 500L

        private const val PID_RPM = 0x0C
        private const val PID_SPEED = 0x0D
        private const val PID_COOLANT = 0x05
        private const val PID_MAF = 0x10
        private const val PID_FUEL_RATE = 0x5E
    }
}
