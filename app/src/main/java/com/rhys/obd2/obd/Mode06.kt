package com.rhys.obd2.obd

/**
 * Service 06 — on-board monitoring test results.
 *
 * This is the most useful and least used part of OBD-II. Where readiness only says
 * "the catalyst test passed", service 06 gives the actual measured value alongside the
 * limits it was judged against. That lets you see a component drifting towards failure
 * months before it sets a code — a catalyst efficiency figure creeping up on its limit,
 * for instance, or an O2 sensor's switching time getting slower each time you check.
 */
data class MonitorTest(
    /** Monitor ID: which subsystem ran the test. */
    val mid: Int,
    /** Test ID: which specific measurement within that subsystem. */
    val tid: Int,
    val monitorName: String,
    val testName: String,
    val value: Double,
    val min: Double?,
    val max: Double?,
    val unit: String,
    /** False when the value sits outside the limits the ECU judged it against. */
    val passed: Boolean,
    /** True when the unit-and-scaling ID wasn't one we recognise, so numbers are raw counts. */
    val rawScaling: Boolean,
) {
    /**
     * How much headroom is left, 0.0 at the limit and 1.0 at the far end of the range.
     * Null when only one limit was reported, which makes a proportion meaningless.
     */
    val margin: Double?
        get() {
            val lo = min ?: return null
            val hi = max ?: return null
            if (hi <= lo) return null
            return ((value - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        }
}

object Mode06 {

    /**
     * Decodes a CAN-format service 06 response.
     *
     * Each test is a fixed 9-byte record: monitor ID, test ID, a unit-and-scaling ID,
     * then the measured value and its two limits as 16-bit words. Pre-CAN protocols use
     * a different and far less consistent layout, which is why this returns nothing
     * rather than guessing when the length doesn't divide evenly.
     */
    fun decode(data: IntArray): List<MonitorTest> {
        if (data.size < 9) return emptyList()

        val tests = mutableListOf<MonitorTest>()
        var i = 0
        while (i + 8 < data.size) {
            val mid = data[i]
            val tid = data[i + 1]
            val uasId = data[i + 2]
            // Record layout: MID, TID, UAS, value (2), min (2), max (2).
            val rawValue = word(data, i + 3)
            val rawMin = word(data, i + 5)
            val rawMax = word(data, i + 7)

            val uas = UAS[uasId]
            val signed = uas?.signed ?: false

            val value = scale(rawValue, uas, signed)

            // A limit the ECU isn't actually applying comes back pegged at the end of the
            // range. Treating those as real limits would report a comfortable pass as a
            // borderline one, so they're dropped.
            val effectiveMin = scale(rawMin, uas, signed).takeIf { rawMin != 0x0000 }
            val effectiveMax = scale(rawMax, uas, signed).takeIf { rawMax != 0xFFFF }

            tests += MonitorTest(
                mid = mid,
                tid = tid,
                monitorName = monitorName(mid),
                testName = testName(tid),
                value = value,
                min = effectiveMin,
                max = effectiveMax,
                unit = uas?.unit ?: "",
                passed = (effectiveMin == null || value >= effectiveMin) &&
                    (effectiveMax == null || value <= effectiveMax),
                rawScaling = uas == null,
            )
            i += 9
        }
        return tests
    }

    private fun word(data: IntArray, offset: Int): Int =
        if (offset + 1 < data.size) (data[offset] shl 8) or data[offset + 1] else 0

    private fun scale(raw: Int, uas: Uas?, signed: Boolean): Double {
        val value = if (signed && raw > 32767) raw - 65536 else raw
        return value * (uas?.multiplier ?: 1.0)
    }

    private data class Uas(val multiplier: Double, val unit: String, val signed: Boolean = false)

    /**
     * Unit-and-scaling table from ISO 15031-5 Annex D.
     *
     * Only entries that are unambiguous in the standard are listed. An unrecognised ID
     * leaves [MonitorTest.rawScaling] set so the UI can say the numbers are raw counts
     * rather than presenting a wrongly-scaled value as fact — the pass/fail comparison
     * is still valid either way, since value and limits share whatever scaling applies.
     */
    private val UAS: Map<Int, Uas> = mapOf(
        0x01 to Uas(1.0, ""),
        0x02 to Uas(0.1, ""),
        0x03 to Uas(0.01, ""),
        0x04 to Uas(0.001, ""),
        0x05 to Uas(0.0000305, "%"),
        0x06 to Uas(0.000122, "%"),
        0x07 to Uas(0.25, "rpm"),
        0x08 to Uas(0.01, "km/h"),
        0x09 to Uas(1.0, "km/h"),
        0x0A to Uas(0.122, "mV"),
        0x0B to Uas(0.001, "V"),
        0x0C to Uas(0.01, "V"),
        0x0D to Uas(0.00390625, "mA"),
        0x0E to Uas(0.001, "A"),
        0x0F to Uas(0.01, "A"),
        0x10 to Uas(1.0, "ms"),
        0x11 to Uas(100.0, "ms"),
        0x12 to Uas(1.0, "s"),
        0x13 to Uas(1.0, "Ω"),
        0x14 to Uas(1.0, "kΩ"),
        0x15 to Uas(1.0, "MΩ"),
        0x16 to Uas(0.1, "°C", signed = true),
        0x17 to Uas(0.01, "kPa"),
        0x18 to Uas(0.0117, "kPa"),
        0x19 to Uas(0.079, "kPa"),
        0x1A to Uas(1.0, "kPa"),
        0x1B to Uas(10.0, "kPa"),
        0x1C to Uas(0.01, "°", signed = true),
        0x1D to Uas(0.5, "°", signed = true),
        0x1E to Uas(0.0000305, "λ"),
        0x1F to Uas(0.05, "L"),
        0x20 to Uas(0.00003, "in"),
        0x21 to Uas(0.01, "s"),
        0x22 to Uas(0.1, "min"),
        0x23 to Uas(0.01, "s"),
        0x24 to Uas(1.0, "count"),
        0x25 to Uas(1.0, "km"),
        0x26 to Uas(0.1, "mV/ms"),
        0x27 to Uas(0.01, "g/s"),
        0x28 to Uas(1.0, "g/s"),
        0x29 to Uas(0.25, "Pa/s"),
        0x2A to Uas(1.0, "kg/h"),
        0x2B to Uas(1.0, "count"),
        0x2C to Uas(0.01, "kg/h"),
        0x2D to Uas(0.0000305, "%", signed = true),
        0x2E to Uas(1.0, ""),
        0x2F to Uas(0.01, "%"),
        0x30 to Uas(1.0, "count"),
        0x31 to Uas(1.0, "km"),
        0x32 to Uas(0.1, "mV"),
        0x33 to Uas(0.01, "%"),
        0x34 to Uas(1.0, "mg/stroke"),
        0x81 to Uas(1.0, "", signed = true),
        0x82 to Uas(0.1, "", signed = true),
        0x83 to Uas(0.01, "", signed = true),
        0x84 to Uas(0.001, "", signed = true),
        0x8A to Uas(0.122, "mV", signed = true),
        0x8B to Uas(0.001, "V", signed = true),
        0x8C to Uas(0.01, "V", signed = true),
        0xFE to Uas(0.000000001, ""),
    )

    /**
     * Monitor IDs. Ranges are defined by the standard; the individual sensor positions
     * within a range follow a regular pattern so they're generated rather than listed.
     */
    fun monitorName(mid: Int): String = when (mid) {
        in 0x01..0x08 -> "O2 sensor monitor, bank ${(mid - 1) / 4 + 1} sensor ${(mid - 1) % 4 + 1}"
        in 0x09..0x10 -> "O2 sensor monitor, bank ${(mid - 9) / 4 + 3} sensor ${(mid - 9) % 4 + 1}"
        in 0x21..0x28 -> "O2 heater monitor, bank ${(mid - 0x21) / 4 + 1} sensor ${(mid - 0x21) % 4 + 1}"
        0x31 -> "EGR monitor, bank 1"
        0x32 -> "EGR monitor, bank 2"
        0x33 -> "EGR monitor, bank 3"
        0x34 -> "EGR monitor, bank 4"
        0x35 -> "VVT monitor, bank 1"
        0x36 -> "VVT monitor, bank 2"
        0x39 -> "EGR cooler monitor"
        0x3A -> "Cold start emission reduction"
        0x3B -> "Cold start emission reduction, bank 2"
        0x41 -> "NMHC catalyst monitor, bank 1"
        0x42 -> "NMHC catalyst monitor, bank 2"
        0x43 -> "NOx aftertreatment monitor, bank 1"
        0x44 -> "NOx aftertreatment monitor, bank 2"
        0x50 -> "Misfire monitor — general data"
        in 0x51..0x5C -> "Misfire monitor — cylinder ${mid - 0x50}"
        0x60 -> "Fuel system monitor, bank 1"
        0x61 -> "Fuel system monitor, bank 2"
        0x71 -> "Catalyst monitor, bank 1"
        0x72 -> "Catalyst monitor, bank 2"
        0x73 -> "Catalyst monitor, bank 3"
        0x74 -> "Catalyst monitor, bank 4"
        0x79 -> "Evaporative system monitor"
        0x7A -> "Evaporative system monitor — 0.040\" leak"
        0x7B -> "Evaporative system monitor — 0.020\" leak"
        0x81 -> "Secondary air monitor, bank 1"
        0x82 -> "Secondary air monitor, bank 2"
        0x86 -> "Boost pressure monitor"
        0x87 -> "Particulate filter monitor, bank 1"
        0x88 -> "Particulate filter monitor, bank 2"
        0xA1 -> "Manufacturer-defined monitor"
        else -> "Monitor 0x%02X".format(mid)
    }

    /**
     * Test IDs. The generic ones below 0x80 are standardised; above that they're
     * manufacturer-defined and can't be named without the marque's own data.
     */
    fun testName(tid: Int): String = when (tid) {
        0x01 -> "Rich-to-lean sensor threshold voltage"
        0x02 -> "Lean-to-rich sensor threshold voltage"
        0x03 -> "Low sensor voltage for switch time"
        0x04 -> "High sensor voltage for switch time"
        0x05 -> "Rich-to-lean switch time"
        0x06 -> "Lean-to-rich switch time"
        0x07 -> "Minimum sensor voltage for test cycle"
        0x08 -> "Maximum sensor voltage for test cycle"
        0x09 -> "Time between sensor transitions"
        0x0A -> "Sensor period"
        0x0B -> "Exponentially weighted moving average misfire counts"
        0x0C -> "Misfire counts for last ten drive cycles"
        0x0D -> "Time since last misfire"
        0x0E -> "Sensor amplitude"
        0x0F -> "Sensor maximum amplitude"
        0x80 -> "Manufacturer-defined test"
        else -> if (tid >= 0x80) "Manufacturer test 0x%02X".format(tid) else "Test 0x%02X".format(tid)
    }
}
