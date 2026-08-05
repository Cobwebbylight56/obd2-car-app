package com.rhys.obd2

import com.rhys.obd2.data.LoadEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two gauges that were wrong on a real car, pinned to the readings that were wrong.
 *
 * Both came back from photographs of the app running: a Land Rover Freelander Td4 idling
 * at 773 rpm showing 100.0% engine load and an empty volts dial, and a second car idling at
 * 850 rpm showing 61.2%. Numbers from an actual dashboard, so the cases here are the ones
 * that actually happen rather than the ones that are easy to imagine.
 */
class LoadAndVoltageTest {

    // -----------------------------------------------------------------------------------
    // The estimate's zero point
    // -----------------------------------------------------------------------------------

    @Test
    fun `a diesel idling does not read full load`() {
        // The failure this was rewritten for. Expressing airflow as a plain fraction of the
        // largest ever seen means the first reading is always 100% — and on a diesel, which
        // breathes nearly as hard at idle as at full power, it stays there. The substitute
        // would have shown exactly the 100.0% it was brought in to replace.
        val estimator = LoadEstimator()

        // Two minutes of idle at a Td4's tickover, airflow barely moving.
        repeat(120) { i ->
            estimator.fromAirflow(mafGramsPerSecond = 7.4 + (i % 3) * 0.05, rpm = 773.0)
        }
        val idle = estimator.fromAirflow(mafGramsPerSecond = 7.4, rpm = 773.0)

        assertNotNull(idle)
        assertTrue(
            "idle read ${idle!!.percent}% — the whole point is that this is near zero",
            idle.percent < 25.0,
        )
    }

    @Test
    fun `working the engine moves the figure across the range`() {
        val estimator = LoadEstimator()

        repeat(60) { estimator.fromAirflow(7.4, 773.0) }            // idle: the floor
        repeat(20) { estimator.fromAirflow(95.0, 3200.0) }          // full pull: the ceiling

        val idle = estimator.fromAirflow(7.4, 773.0)!!
        val cruise = estimator.fromAirflow(38.0, 2000.0)!!
        val full = estimator.fromAirflow(95.0, 3200.0)!!

        assertTrue("idle should sit low, got ${idle.percent}", idle.percent < 10.0)
        assertTrue("full should sit high, got ${full.percent}", full.percent > 90.0)
        assertTrue(
            "cruise (${cruise.percent}) should fall between idle (${idle.percent}) and " +
                "full (${full.percent})",
            cruise.percent > idle.percent && cruise.percent < full.percent,
        )
    }

    @Test
    fun `nothing is claimed before the engine has shown its range`() {
        // One reading establishes both the floor and the ceiling, and the gap between them
        // is meaningless. Reporting zero is honest; reporting a hundred looks like a fault.
        val estimator = LoadEstimator()
        val first = estimator.fromAirflow(7.4, 773.0)

        assertNotNull(first)
        assertEquals(0.0, first!!.percent, 0.001)
        assertEquals(LoadEstimator.Confidence.LEARNING, first.confidence)
    }

    @Test
    fun `a stopped engine produces nothing rather than a wild number`() {
        val estimator = LoadEstimator()
        assertNull("cranking speed divides towards infinity", estimator.fromAirflow(0.4, 80.0))
        assertNull("no airflow reading, no estimate", estimator.fromAirflow(null, 800.0))
        assertNull("no engine speed, no estimate", estimator.fromAirflow(12.0, null))
    }

    // -----------------------------------------------------------------------------------
    // Ruling the ECU's own load figure unusable
    // -----------------------------------------------------------------------------------

    /** The rule as the repository applies it, isolated from the adapter. */
    private fun implausible(percent: Double, rpm: Double?, speed: Double?): Boolean {
        val idling = rpm != null && rpm in 300.0..1_400.0 && (speed == null || speed <= 0.0)
        return idling && percent >= 92.0
    }

    @Test
    fun `the Freelander's reading is recognised as impossible`() {
        // 100.0% at 773 rpm, stationary. Whether the ECU is padding with FF or the engine
        // is a diesel reporting a genuine airflow ratio, no engine at tickover is at the
        // whole of its capacity.
        assertTrue(implausible(percent = 100.0, rpm = 773.0, speed = 0.0))
    }

    @Test
    fun `the other car's reading is left alone`() {
        // 61.2% at 850 rpm. High for an idle, but an engine can genuinely be doing that
        // with the air conditioning and the alternator loaded, and it varied on the trace.
        // Ruling it out would replace a working reading with an estimate.
        assertTrue(!implausible(percent = 61.2, rpm = 850.0, speed = 0.0))
    }

    @Test
    fun `a genuine full-throttle reading survives`() {
        // The case the FF check had to be careful of, and the reason the test is idle-only.
        // 100% load at 4000 rpm at speed is a car being driven hard, not a broken sensor.
        assertTrue(!implausible(percent = 100.0, rpm = 4_000.0, speed = 110.0))
        assertTrue(!implausible(percent = 100.0, rpm = 2_200.0, speed = 48.0))
    }

    @Test
    fun `a diesel in the nineties is caught as well as a padded hundred`() {
        // FF decodes to exactly 100.0. A diesel's real ratio lands wherever it lands, and
        // is equally useless as a load figure, which is why the threshold is short of 100.
        assertTrue(implausible(percent = 94.5, rpm = 800.0, speed = 0.0))
        assertTrue(implausible(percent = 100.0, rpm = 800.0, speed = 0.0))
    }

    // -----------------------------------------------------------------------------------
    // Battery voltage from the adapter
    // -----------------------------------------------------------------------------------

    private val voltagePattern = Regex("""\d{1,2}\.\d+""")
    private val plausible = 6.0..36.0

    private fun parseAtrv(raw: String): Double? =
        voltagePattern.find(raw)?.value?.toDoubleOrNull()?.takeIf { it in plausible }

    @Test
    fun `every shape of ATRV reply a clone might give is understood`() {
        // No two ELM327 clones format this the same way, and the gauge is empty on both of
        // the cars this was tested against, so this is the only source it has.
        assertEquals(12.6, parseAtrv("12.6V")!!, 0.001)
        assertEquals(12.6, parseAtrv("12.6V\r\r>")!!, 0.001)
        assertEquals(14.42, parseAtrv("ATRV\r14.42V\r\r>")!!, 0.001)
        assertEquals(11.9, parseAtrv("  11.9 V  ")!!, 0.001)
        assertEquals(13.8, parseAtrv("13.8")!!, 0.001)
    }

    @Test
    fun `a nonsense reply leaves the gauge empty rather than filling it with a lie`() {
        assertNull(parseAtrv("?"))
        assertNull(parseAtrv("UNABLE TO CONNECT"))
        assertNull(parseAtrv(""))
        assertNull("0.0V is the adapter failing, not a flat battery", parseAtrv("0.0V"))
        assertNull("above any vehicle system", parseAtrv("99.9V"))
    }
}
