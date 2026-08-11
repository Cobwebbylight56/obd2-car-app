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
    /** A reading that had been abnormal returning to where it should be. */
    RECOVERED("Back to normal"),
    /** The engine management light, or another warning the ECU raised. */
    WARNING("Warning light"),
    /** The adapter or the car stopped answering, and whether it came back. */
    COMMS("Connection"),
    /** Something declared removed, blanked, disabled or otherwise changed. */
    MODIFICATION("Modification"),
    TRIP("Trip recorded"),
    MILEAGE("Mileage"),
    NOTE("Note"),
}

/** What a new odometer reading says about the ones before it. */
enum class MileageVerdict {
    /** First reading for this car, so there is nothing to compare against yet. */
    FIRST,

    /** Same or higher than last time, as it should be. */
    CONSISTENT,

    /**
     * Lower than a reading already recorded. Barring a replaced instrument cluster or
     * ECU, an odometer does not go backwards.
     */
    WENT_BACKWARDS,
}

data class MileageReading(val timestamp: Long, val km: Double)

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
    /**
     * Parts the owner has declared removed, blanked, disabled or changed.
     *
     * Kept on the car rather than in app settings because it is a fact about this car and
     * has to survive plugging into a different one — the whole point is that the app stops
     * calling a blanked EGR a fault on the Freelander without going quiet about a genuinely
     * faulty one on anything else.
     */
    val modifications: List<Modification> = emptyList(),
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
     * Declares a part removed, blanked, disabled or otherwise changed.
     *
     * Recorded in the history as well as on the car, because when a modification was
     * declared is itself diagnostic: a fault that started the week the EGR was blanked and
     * a fault that started two years later are different stories.
     */
    fun setModification(key: String, modification: Modification) {
        val vehicle = _vehicles.value.firstOrNull { it.key == key } ?: return
        val existing = vehicle.modifications.firstOrNull { it.componentId == modification.componentId }
        if (existing?.kind == modification.kind && existing.note == modification.note) return

        val others = vehicle.modifications.filterNot { it.componentId == modification.componentId }
        save(vehicle.copy(modifications = others + modification))

        val component = ModificationCatalogue[modification.componentId]
        record(
            key,
            VehicleHistoryEvent(
                timestamp = modification.recordedAt,
                type = EventType.MODIFICATION,
                title = "${component?.name ?: modification.componentId} — ${modification.kind.label.lowercase()}",
                detail = buildString {
                    append(modification.kind.detail)
                    if (modification.note.isNotBlank()) append("\n\n${modification.note}")
                    component?.expectedInstead?.takeIf { it.isNotBlank() }?.let {
                        append("\n\nWhat to expect now: $it")
                    }
                },
            ),
        )
        refresh()
    }

    /** Puts a component back to standard, so its readings are judged normally again. */
    fun clearModification(key: String, componentId: String) {
        val vehicle = _vehicles.value.firstOrNull { it.key == key } ?: return
        if (vehicle.modifications.none { it.componentId == componentId }) return
        save(vehicle.copy(modifications = vehicle.modifications.filterNot { it.componentId == componentId }))

        record(
            key,
            VehicleHistoryEvent(
                timestamp = System.currentTimeMillis(),
                type = EventType.MODIFICATION,
                title = "${ModificationCatalogue[componentId]?.name ?: componentId} — back to standard",
                detail = "Its readings and fault codes are judged against the factory " +
                    "figures again.",
            ),
        )
        refresh()
    }

    fun modificationsFor(key: String?): VehicleModifications {
        val vehicle = _vehicles.value.firstOrNull { it.key == key } ?: return VehicleModifications()
        return VehicleModifications(vehicle.modifications)
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

    /**
     * Records an odometer reading and says whether it is consistent with what came before.
     *
     * The check is deliberately narrow, because the useful version of this question and the
     * answerable version are not the same one. What this can prove is that the reading went
     * down *while the app was watching*: readings are kept outside the car with the dates
     * they were taken, so a later one that is lower than an earlier one is evidence, and
     * evidence the car itself no longer holds.
     *
     * What it cannot do is tell you anything about a rollback that happened before you
     * first plugged in — which is the case that matters when buying. Nothing on the OBD-II
     * port can: generic mode 01 exposes the current value and no history, and a competent
     * rollback rewrites every module that stores one. For a car's past, the MOT history at
     * gov.uk is the real tool, and it is free.
     */
    fun recordOdometer(key: String, km: Double): MileageVerdict {
        val previous = odometerHistory(key)
        val highest = previous.maxByOrNull { it.km }

        val file = File(directory(key), ODOMETER_FILE)
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("${System.currentTimeMillis()}\t$km\n")
        }.onFailure { Log.e(TAG, "Could not record odometer", it) }

        return when {
            highest == null -> MileageVerdict.FIRST
            // A small tolerance, because the reading has 0.1 km resolution and a car can be
            // reversed. Anything beyond that is not rounding.
            km < highest.km - ODOMETER_TOLERANCE_KM -> MileageVerdict.WENT_BACKWARDS
            else -> MileageVerdict.CONSISTENT
        }
    }

    /** Every odometer reading taken for this car, oldest first. */
    fun odometerHistory(key: String): List<MileageReading> {
        val file = File(directory(key), ODOMETER_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2) return@mapNotNull null
                val at = parts[0].toLongOrNull() ?: return@mapNotNull null
                val km = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                MileageReading(at, km)
            }
        }.getOrDefault(emptyList())
    }

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
                    appendLine("modifications=${escape(encodeModifications(vehicle.modifications))}")
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
                modifications = decodeModifications(fields["modifications"].orEmpty()),
            )
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Garage"
        private const val VEHICLE_FILE = "vehicle.txt"
        private const val EVENTS_FILE = "events.log"
        private const val ODOMETER_FILE = "odometer.log"

        /**
         * How far a reading may fall below the highest seen before it counts as backwards.
         *
         * The PID has 0.1 km resolution and a car can genuinely be reversed onto a drive,
         * so a hundred metres of slack costs nothing. A rollback is measured in thousands.
         */
        internal const val ODOMETER_TOLERANCE_KM = 0.5

        /**
         * Modifications on one line of the vehicle file.
         *
         * Pipe between records and semicolon between fields, both of which are already
         * escaped away by [escape] before the line is written, so an owner's free-text note
         * can contain either without breaking the file. A record that does not parse is
         * dropped rather than failing the load: a corrupt modification must not cost
         * somebody their whole vehicle history.
         */
        internal fun encodeModifications(mods: List<Modification>): String =
            mods.joinToString("|") { mod ->
                listOf(
                    mod.componentId,
                    mod.kind.name,
                    mod.recordedAt.toString(),
                    mod.note.replace(";", ",").replace("|", "/"),
                ).joinToString(";")
            }

        internal fun decodeModifications(encoded: String): List<Modification> =
            encoded.split('|').mapNotNull { record ->
                if (record.isBlank()) return@mapNotNull null
                val parts = record.split(';')
                if (parts.size < 3) return@mapNotNull null
                val kind = runCatching { ModKind.valueOf(parts[1]) }.getOrNull()
                    ?: return@mapNotNull null
                Modification(
                    componentId = parts[0],
                    kind = kind,
                    note = parts.getOrNull(3).orEmpty(),
                    recordedAt = parts[2].toLongOrNull() ?: 0L,
                )
            }

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
