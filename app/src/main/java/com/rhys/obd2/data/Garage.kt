package com.rhys.obd2.data

import android.content.Context
import android.util.Log
import com.rhys.obd2.obd.VehicleInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** What kind of thing happened. Drives the icon and colour in the history list. */
enum class EventType(val label: String) {
    CONNECTED("Connected"),
    CODES_FOUND("Fault codes"),
    CODES_CLEARED("Codes cleared"),
    ABNORMAL("Unusual reading"),
    TRIP("Trip recorded"),
    NOTE("Note"),
}

/** One dated entry in a vehicle's history. */
data class VehicleHistoryEvent(
    val timestamp: Long,
    val type: EventType,
    val title: String,
    val detail: String = "",
)

/**
 * A car the app has been plugged into.
 *
 * [key] is the stable identity used for storage. [name] is whatever the owner called it,
 * defaulting to something recognisable rather than a bare VIN.
 */
data class Vehicle(
    val key: String,
    val vin: String?,
    val name: String,
    val manufacturer: String? = null,
    val modelYear: String? = null,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    /** Which entry in the known-issues list the owner picked, if any. */
    val modelId: String? = null,
    /** Codes present at the last read, so a repeat read doesn't log a duplicate entry. */
    val lastCodes: Set<String> = emptySet(),
) {
    val identifiedByVin: Boolean get() = vin != null
}

/**
 * Per-vehicle history that outlives the car's own memory.
 *
 * The point of this is that a scan tool can't normally answer "has this happened before".
 * Clearing fault codes wipes the codes, the freeze frame and the readiness state from the
 * ECU, so without a record kept outside the car there is no way to tell a fault that has
 * recurred five times from one that has just appeared — and that distinction is usually
 * the whole diagnosis.
 *
 * Stored as plain files under one directory per vehicle. No database, so there is no
 * schema to migrate and no dependency to carry; deleting a car when it's sold is deleting
 * its directory, which is both obvious and complete.
 */
class Garage(private val context: Context) {

    private val root: File get() = File(context.filesDir, "garage").apply { mkdirs() }

    private val _vehicles = MutableStateFlow(loadAll())
    val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()

    // -------------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------------

    /**
     * Works out which car we're plugged into, creating a record the first time.
     *
     * The VIN is the right identity and is what modern cars report. Reporting it over OBD
     * only became mandatory around 2008 though, so older cars need a fallback: the ECU's
     * calibration ID is stable for a given car and engine, which is good enough to tell
     * two vehicles apart in a personal garage. Failing that, the adapter's own address is
     * used, which is correct only as long as one dongle stays with one car — the UI says
     * so rather than pretending the identification is certain.
     */
    fun identify(info: VehicleInfo?, adapterAddress: String?): Vehicle {
        val key = identityKey(info, adapterAddress)
        val existing = _vehicles.value.firstOrNull { it.key == key }
        val now = System.currentTimeMillis()

        val vehicle = existing?.copy(
            lastSeen = now,
            // Fill in details that a later, more complete read discovered.
            vin = existing.vin ?: info?.vin,
            manufacturer = existing.manufacturer ?: info?.decodedVin?.manufacturer,
            modelYear = existing.modelYear ?: info?.decodedVin?.modelYear,
        ) ?: Vehicle(
            key = key,
            vin = info?.vin,
            name = defaultName(info),
            manufacturer = info?.decodedVin?.manufacturer,
            modelYear = info?.decodedVin?.modelYear,
            firstSeen = now,
            lastSeen = now,
        )

        save(vehicle)
        if (existing == null) {
            record(key, VehicleHistoryEvent(now, EventType.CONNECTED, "First seen", describe(vehicle)))
        }
        refresh()
        return vehicle
    }

    private fun identityKey(info: VehicleInfo?, adapterAddress: String?): String {
        info?.vin?.takeIf { it.isNotBlank() }?.let { return "vin-${sanitise(it)}" }
        info?.calibrationIds?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?.let { return "cal-${sanitise(it)}" }
        adapterAddress?.takeIf { it.isNotBlank() }?.let { return "adapter-${sanitise(it)}" }
        return "unidentified"
    }

    private fun defaultName(info: VehicleInfo?): String {
        val details = info?.decodedVin
        val maker = details?.manufacturer
        val year = details?.modelYear?.substringBefore(" or ")
        return when {
            maker != null && year != null -> "$maker ($year)"
            maker != null -> maker
            info?.vin != null -> "Car ${info.vin.takeLast(6)}"
            else -> "My car"
        }
    }

    private fun describe(vehicle: Vehicle): String = buildString {
        vehicle.vin?.let { appendLine("VIN: $it") }
        vehicle.manufacturer?.let { appendLine("Manufacturer: $it") }
        vehicle.modelYear?.let { appendLine("Model year: $it") }
        if (!vehicle.identifiedByVin) {
            append(
                "This car doesn't report a VIN, so it's identified by its ECU calibration or " +
                    "the adapter it was read with. If you use the same adapter on another car " +
                    "without a VIN, their histories could merge."
            )
        }
    }.trim()

    private fun sanitise(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }.take(40).ifEmpty { "X" }

    // -------------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------------

    /** Records which model the owner says this is, for the known-issues notes. */
    fun setModel(key: String, modelId: String?) {
        val vehicle = _vehicles.value.firstOrNull { it.key == key } ?: return
        save(vehicle.copy(modelId = modelId))
        refresh()
    }

    /**
     * When a given fault code has been seen on this car.
     *
     * This is the thing a scan tool normally cannot tell you. Clearing codes wipes them
     * from the ECU, so without a record kept outside the car there is no way to distinguish
     * a fault that has come back four times from one that has just appeared — and that
     * distinction is usually the whole diagnosis.
     *
     * Matched on the code appearing in the entry's title, which is how the codes are
     * recorded. Newest first.
     */
    fun occurrencesOf(key: String, code: String): List<Long> =
        events(key)
            .filter { it.type == EventType.CODES_FOUND || it.type == EventType.CODES_CLEARED }
            .filter { it.title.contains(code, ignoreCase = true) || it.detail.contains(code, ignoreCase = true) }
            .map { it.timestamp }

    fun rename(key: String, name: String) {
        val vehicle = _vehicles.value.firstOrNull { it.key == key } ?: return
        val trimmed = name.trim().ifEmpty { return }
        save(vehicle.copy(name = trimmed))
        refresh()
    }

    /** Removes the car and everything recorded about it. Used when the car is sold. */
    fun delete(key: String): Boolean {
        val deleted = runCatching { directory(key).deleteRecursively() }.getOrDefault(false)
        refresh()
        return deleted
    }

    fun record(key: String, event: VehicleHistoryEvent) {
        val file = File(directory(key), EVENTS_FILE)
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(encode(event) + "\n")
        }.onFailure { Log.e(TAG, "Could not write history event", it) }
    }

    /**
     * Removes one entry from a car's history.
     *
     * Rewrites the log without it rather than marking it deleted, so "delete" means the
     * bytes are gone — which is the whole point of being able to delete a car when it's
     * sold, applied at a smaller scale.
     *
     * Matched on timestamp and title rather than on an identifier, because the log has no
     * identifiers: it is an append-only text file, and giving every line a synthetic id
     * would break every file already written. Two entries identical in both fields are
     * indistinguishable to a reader as well, so removing either satisfies the request.
     */
    fun deleteEvent(key: String, event: VehicleHistoryEvent): Boolean {
        val file = File(directory(key), EVENTS_FILE)
        if (!file.exists()) return false
        return runCatching {
            val kept = file.readLines().filterNot { line ->
                decode(line)?.let {
                    it.timestamp == event.timestamp && it.title == event.title
                } ?: false
            }
            file.writeText(kept.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" })
            true
        }.onFailure { Log.e(TAG, "Could not delete history event", it) }.getOrDefault(false)
    }

    /** Empties a car's history but keeps the car itself, its name and its identity. */
    fun clearHistory(key: String): Boolean = runCatching {
        File(directory(key), EVENTS_FILE).takeIf { it.exists() }?.delete() ?: true
    }.onFailure { Log.e(TAG, "Could not clear history", it) }.getOrDefault(false)

    /** Remembers the code set so a repeated read doesn't log the same thing again. */
    fun updateLastCodes(key: String, codes: Set<String>) {
        val vehicle = _vehicles.value.firstOrNull { it.key == key } ?: return
        save(vehicle.copy(lastCodes = codes))
        refresh()
    }

    // -------------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------------

    /** Newest first, which is the order the history screen wants. */
    fun events(key: String): List<VehicleHistoryEvent> {
        val file = File(directory(key), EVENTS_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { decode(it) }.sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())
    }

    fun storageBytes(key: String): Long =
        runCatching { directory(key).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)

    fun vehicle(key: String): Vehicle? = _vehicles.value.firstOrNull { it.key == key }

    private fun refresh() {
        _vehicles.value = loadAll()
    }

    private fun directory(key: String): File = File(root, key)

    private fun loadAll(): List<Vehicle> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { load(it) }
            ?.sortedByDescending { it.lastSeen }
            ?: emptyList()

    // -------------------------------------------------------------------------------
    // Storage format
    //
    // Deliberately plain text rather than JSON or a database: it needs no dependency, no
    // schema migration, and stays readable if anyone ever goes looking at the files.
    // -------------------------------------------------------------------------------

    private fun save(vehicle: Vehicle) {
        val file = File(directory(vehicle.key), VEHICLE_FILE)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                buildString {
                    appendLine("key=${escape(vehicle.key)}")
                    appendLine("vin=${escape(vehicle.vin.orEmpty())}")
                    appendLine("name=${escape(vehicle.name)}")
                    appendLine("manufacturer=${escape(vehicle.manufacturer.orEmpty())}")
                    appendLine("modelYear=${escape(vehicle.modelYear.orEmpty())}")
                    appendLine("modelId=${escape(vehicle.modelId.orEmpty())}")
                    appendLine("firstSeen=${vehicle.firstSeen}")
                    appendLine("lastSeen=${vehicle.lastSeen}")
                    appendLine("lastCodes=${escape(vehicle.lastCodes.joinToString(","))}")
                }
            )
        }.onFailure { Log.e(TAG, "Could not save vehicle", it) }
    }

    private fun load(dir: File): Vehicle? {
        val file = File(dir, VEHICLE_FILE)
        if (!file.exists()) return null
        return runCatching {
            val fields = file.readLines().mapNotNull { line ->
                val index = line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, index) to unescape(line.substring(index + 1))
            }.toMap()

            Vehicle(
                key = fields["key"]?.takeIf { it.isNotBlank() } ?: dir.name,
                vin = fields["vin"]?.takeIf { it.isNotBlank() },
                name = fields["name"]?.takeIf { it.isNotBlank() } ?: "My car",
                manufacturer = fields["manufacturer"]?.takeIf { it.isNotBlank() },
                modelYear = fields["modelYear"]?.takeIf { it.isNotBlank() },
                modelId = fields["modelId"]?.takeIf { it.isNotBlank() },
                firstSeen = fields["firstSeen"]?.toLongOrNull() ?: 0L,
                lastSeen = fields["lastSeen"]?.toLongOrNull() ?: 0L,
                lastCodes = fields["lastCodes"].orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            )
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Garage"
        private const val VEHICLE_FILE = "vehicle.txt"
        private const val EVENTS_FILE = "events.log"

        /** Tab-separated, with the separators escaped so a detail can contain anything. */
        internal fun encode(event: VehicleHistoryEvent): String = listOf(
            event.timestamp.toString(),
            event.type.name,
            escape(event.title),
            escape(event.detail),
        ).joinToString("\t")

        internal fun decode(line: String): VehicleHistoryEvent? {
            val parts = line.split('\t')
            if (parts.size < 4) return null
            val timestamp = parts[0].toLongOrNull() ?: return null
            val type = runCatching { EventType.valueOf(parts[1]) }.getOrNull() ?: return null
            return VehicleHistoryEvent(timestamp, type, unescape(parts[2]), unescape(parts[3]))
        }

        internal fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")

        internal fun unescape(value: String): String {
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        't' -> { out.append('\t'); i += 2; continue }
                        'n' -> { out.append('\n'); i += 2; continue }
                        '\\' -> { out.append('\\'); i += 2; continue }
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }
    }
}
