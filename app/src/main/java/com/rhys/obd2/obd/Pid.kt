package com.rhys.obd2.obd

/** One decoded number out of a PID response. Most PIDs give one; some give several. */
data class Reading(
    val label: String,
    val value: Double,
    val unit: String,
    /** Set for enumerated PIDs where the number is meaningless on its own. */
    val text: String? = null,
)

/** Loose grouping used to organise the live-data screen. */
enum class PidCategory(val label: String) {
    ENGINE("Engine"),
    FUEL("Fuel & trims"),
    AIR("Air & intake"),
    TEMPERATURE("Temperatures"),
    EMISSIONS("Emissions"),
    ELECTRICAL("Electrical"),
    SPEED("Speed & distance"),
    OXYGEN("Oxygen sensors"),
    DIESEL("Diesel & turbo"),
    HYBRID("Hybrid"),
    STATUS("Status"),
    OTHER("Other"),
}

/**
 * A service-01 parameter: what to ask for, how many bytes come back, and how to turn
 * them into something a human can read.
 *
 * [min] and [max] are the standard's full range, used to scale gauges. They are not
 * limits on the decoded value.
 */
class Pid(
    val id: Int,
    val name: String,
    val unit: String = "",
    val min: Double = 0.0,
    val max: Double = 100.0,
    val bytes: Int = 1,
    val category: PidCategory = PidCategory.OTHER,
    /** True for values worth showing on the main dashboard by default. */
    val featured: Boolean = false,
    private val decoder: (IntArray) -> List<Reading>,
) {
    val hex: String get() = "%02X".format(id)

    /**
     * A version of [name] short enough for a gauge tile.
     *
     * Half a phone's width fits about twenty-six characters across two lines, and fifty of
     * these names are longer than that — "Commanded air-fuel equivalence ratio", "Catalyst
     * temperature — Bank 1 Sensor 1". On a tile they truncated mid-word, which is both ugly
     * and ambiguous: "Catalyst temperature — Bank 1 Se…" and its Bank 2 twin are
     * indistinguishable at a glance, which is precisely when a dashboard gets read.
     *
     * The full name is kept everywhere there is room for it — the live data list, reports,
     * trip logs — so nothing is lost, only abbreviated where abbreviating is the only
     * alternative to cutting.
     */
    val shortName: String by lazy { abbreviate(name) }

    /**
     * Decodes a payload, filling in this PID's own name and unit for readings that
     * didn't specify their own. Returns empty if the payload is too short, which is
     * common with adapters that truncate responses.
     */
    fun decode(data: IntArray): List<Reading> {
        if (data.size < bytes) return emptyList()
        return runCatching { decoder(data) }
            .getOrDefault(emptyList())
            .map { reading ->
                reading.copy(
                    label = reading.label.ifEmpty { name },
                    unit = reading.unit.ifEmpty { unit },
                )
            }
    }

    /** Convenience for the single-value case, which is most of them. */
    fun decodeSingle(data: IntArray): Double? = decode(data).firstOrNull()?.value
}

/**
 * Shortens a parameter name by the conventions a workshop already uses.
 *
 * Rules rather than a table of fifty hand-written strings: a table drifts out of step with
 * the names beside it the first time one is edited, and these substitutions are the ones
 * any manual makes anyway — bank and sensor numbers collapse to B1 S2, "temperature"
 * becomes "temp", and so on.
 */
internal fun abbreviate(name: String): String {
    var out = name
    val rules = listOf(
        Regex("\\s*[—-]\\s*Bank (\\d)/(\\d) Sensor (\\d)") to " B$1/$2 S$3",
        Regex("\\s*[—-]\\s*Bank (\\d) Sensor (\\d)") to " B$1 S$2",
        Regex("\\s*[—-]\\s*Bank (\\d)/(\\d)") to " B$1/$2",
        Regex("\\s*[—-]\\s*Bank (\\d)") to " B$1",
        Regex("\\bBank (\\d) Sensor (\\d)\\b") to "B$1 S$2",
        Regex("\\bSensor (\\d)\\b") to "S$1",
        Regex("\\bShort term\\b") to "Short",
        Regex("\\bLong term\\b") to "Long",
        Regex("\\btemperature\\b") to "temp",
        Regex("\\bTemperature\\b") to "Temp",
        Regex("\\bpressure\\b") to "press.",
        Regex("\\babsolute\\b") to "abs.",
        Regex("\\bAbsolute\\b") to "Abs.",
        Regex("\\bposition\\b") to "pos.",
        Regex("\\bpercentage\\b") to "%",
        Regex("\\bCommanded\\b") to "Cmd",
        Regex("\\bequivalence ratio\\b") to "lambda",
        Regex("\\bAccelerator pedal\\b") to "Accel. pedal",
        Regex("\\bIntake manifold\\b") to "Intake man.",
        Regex("\\bsecondary O2\\b") to "sec. O2",
        Regex("\\bbarometric\\b") to "baro.",
        Regex("\\bDriver's demand\\b") to "Driver demand",
        Regex("\\bRun time since engine start\\b") to "Engine run time",
        Regex("\\bMonitor status since DTCs cleared\\b") to "Monitor status",
        Regex("\\bHybrid battery pack remaining life\\b") to "Hybrid battery life",
        Regex("\\bTurbocharger compressor inlet\\b") to "Turbo inlet",
        Regex("\\bTurbocharger\\b") to "Turbo",
        // Shortened, never dropped. These suffixes are the only thing separating PID 05
        // from 67 and 0B from 87, so removing them made four pairs of gauges identical —
        // which is worse than a long label and precisely the failure abbreviating is
        // supposed to avoid.
        Regex("\\s*\\(sensors\\)") to " (sens.)",
        Regex("\\s*\\(extended\\)") to " (ext.)",
        Regex("\\s*\\(rel\\. to manifold vacuum\\)") to " (rel.)",
    )
    rules.forEach { (pattern, replacement) -> out = pattern.replace(out, replacement) }
    return out.replace(Regex("\\s+"), " ").trim()
}
