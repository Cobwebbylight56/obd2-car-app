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
