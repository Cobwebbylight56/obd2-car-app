package com.rhys.obd2

import com.rhys.obd2.data.AbnormalReadingMonitor
import com.rhys.obd2.data.AbnormalSeverity
import com.rhys.obd2.data.Garage
import com.rhys.obd2.data.LoadEstimator
import com.rhys.obd2.data.ModKind
import com.rhys.obd2.data.Modification
import com.rhys.obd2.data.ModificationCatalogue
import com.rhys.obd2.data.VehicleModifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic logic: what counts as a fault, what counts as expected, and what it takes
 * to say either.
 *
 * Written against a real car with a real modification — a Freelander Td4 with the EGR
 * blanked — because the failure this exists to prevent is the app being confidently wrong
 * about a modified vehicle and burying the real faults under noise nobody reads.
 */
class DiagnosticLogicTest {

    private val monitor = AbnormalReadingMonitor()

    /** Puts the engine into a warm, running, stationary state the rules can judge. */
    private fun warmIdle() {
        monitor.observe(AbnormalReadingMonitor.PID_RPM, 800.0)
        monitor.observe(AbnormalReadingMonitor.PID_COOLANT, 88.0)
        monitor.observe(AbnormalReadingMonitor.PID_SPEED, 0.0)
    }

    /** Feeds the same reading until the monitor is willing to commit to a verdict. */
    private fun repeat(pid: Int, value: Double, times: Int = AbnormalReadingMonitor.CONFIRM_READS) =
        (1..times).mapNotNull { monitor.observe(pid, value) }.lastOrNull()

    // -----------------------------------------------------------------------------------
    // Not crying wolf
    // -----------------------------------------------------------------------------------

    @Test
    fun `one bad reading is not a fault`() {
        // The whole reason the app can be left running: a value that dips for a moment on a
        // gear change must not put an entry in the car's permanent history.
        warmIdle()
        assertNull(monitor.observe(AbnormalReadingMonitor.PID_COOLANT, 120.0))
    }

    @Test
    fun `a fault has to persist before it is raised`() {
        warmIdle()
        val raised = repeat(AbnormalReadingMonitor.PID_COOLANT, 120.0)
        assertNotNull("three consecutive bad readings is a fault", raised)
        assertEquals(AbnormalSeverity.SERIOUS, raised!!.severity)
        assertTrue(raised.active)
    }

    @Test
    fun `a raised fault is reported once, not once per reading`() {
        warmIdle()
        repeat(AbnormalReadingMonitor.PID_COOLANT, 120.0)
        val further = (1..50).mapNotNull {
            monitor.observe(AbnormalReadingMonitor.PID_COOLANT, 120.0)
        }
        assertTrue("a fault held for a minute is one event, got ${further.size}", further.isEmpty())
    }

    @Test
    fun `getting worse is worth saying again`() {
        warmIdle()
        repeat(AbnormalReadingMonitor.PID_COOLANT, 108.0)   // notable
        val worse = repeat(AbnormalReadingMonitor.PID_COOLANT, 122.0, times = 1)
        assertNotNull("escalation is new information", worse)
        assertEquals(AbnormalSeverity.SERIOUS, worse!!.severity)
    }

    @Test
    fun `a fault coming good is recorded as recovered rather than forgotten`() {
        warmIdle()
        repeat(AbnormalReadingMonitor.PID_COOLANT, 120.0)

        val cleared = (1..AbnormalReadingMonitor.CLEAR_READS).mapNotNull {
            monitor.observe(AbnormalReadingMonitor.PID_COOLANT, 90.0)
        }.lastOrNull()

        assertNotNull("coming good is a transition worth recording", cleared)
        assertTrue("must not still be active", !cleared!!.active)
        assertTrue(
            "the record is kept so an intermittent fault is distinguishable from a fixed one",
            monitor.findings.any { it.pid == AbnormalReadingMonitor.PID_COOLANT },
        )
    }

    // -----------------------------------------------------------------------------------
    // Context
    // -----------------------------------------------------------------------------------

    @Test
    fun `a resting battery is not a charging fault`() {
        // Ignition on, engine off. 12.4 V is a healthy battery and was the reading that
        // would have been called a dead alternator without the engine-running gate.
        monitor.observe(AbnormalReadingMonitor.PID_RPM, 0.0)
        assertNull(repeat(AbnormalReadingMonitor.PID_VOLTAGE, 12.4))
    }

    @Test
    fun `the same voltage with the engine running is a charging fault`() {
        warmIdle()
        val raised = repeat(AbnormalReadingMonitor.PID_VOLTAGE, 12.4)
        assertNotNull(raised)
        assertEquals(AbnormalSeverity.SERIOUS, raised!!.severity)
        assertTrue("must say what it should have been", raised.expected != null)
    }

    @Test
    fun `a healthy charging voltage is not flagged at all`() {
        // 13.5 V is an alternator doing exactly its job. This is the reading the dashboard
        // gauge was colouring amber, which is the same mistake in a different place.
        warmIdle()
        assertNull(repeat(AbnormalReadingMonitor.PID_VOLTAGE, 13.5))
        assertNull(repeat(AbnormalReadingMonitor.PID_VOLTAGE, 14.2))
    }

    @Test
    fun `a cold engine is not accused of a stuck thermostat`() {
        // Two minutes after a cold start on a winter morning. A rule that fires here is a
        // rule that gets ignored by March.
        monitor.observe(AbnormalReadingMonitor.PID_RPM, 800.0)
        assertNull(repeat(AbnormalReadingMonitor.PID_COOLANT, 45.0))
    }

    @Test
    fun `a developing fault is caught before it is a failure`() {
        // The tier that exists for exactly this: 12% trim sets no code and fails no test.
        warmIdle()
        val raised = repeat(AbnormalReadingMonitor.PID_LTFT_1, 12.0)
        assertNotNull("a drifting trim is worth knowing about early", raised)
        assertEquals(AbnormalSeverity.WATCH, raised!!.severity)
    }

    @Test
    fun `a fuel trim is meaningless on a cold engine and stays quiet`() {
        monitor.observe(AbnormalReadingMonitor.PID_RPM, 800.0)
        monitor.observe(AbnormalReadingMonitor.PID_COOLANT, 30.0)
        assertNull(repeat(AbnormalReadingMonitor.PID_LTFT_1, 22.0))
    }

    // -----------------------------------------------------------------------------------
    // Modifications
    // -----------------------------------------------------------------------------------

    @Test
    fun `a blanked EGR stops its own readings counting as faults`() {
        monitor.setModifications(
            VehicleModifications(listOf(Modification("egr", ModKind.BLANKED, "Blanking plate")))
        )
        warmIdle()

        val raised = repeat(AbnormalReadingMonitor.PID_EGR_ERROR, 80.0)
        assertNotNull("the reading is still reported, not hidden", raised)
        assertTrue("but not as a fault", raised!!.suppressed)
        assertEquals(AbnormalSeverity.WATCH, raised.severity)
        assertTrue(
            "and it says which change explains it, got '${raised.explainedBy}'",
            raised.explainedBy?.contains("EGR", ignoreCase = true) == true,
        )
    }

    @Test
    fun `an unmodified car still gets told about its EGR`() {
        warmIdle()
        val raised = repeat(AbnormalReadingMonitor.PID_EGR_ERROR, 80.0)
        assertNotNull(raised)
        assertTrue("nothing explains it, so it is a fault", !raised!!.suppressed)
        assertEquals(AbnormalSeverity.NOTABLE, raised.severity)
    }

    @Test
    fun `declaring the EGR blanked does not silence the coolant temperature`() {
        // The failure mode of suppression: declaring one modification must not make the app
        // quiet about everything else.
        monitor.setModifications(
            VehicleModifications(listOf(Modification("egr", ModKind.BLANKED)))
        )
        warmIdle()
        val raised = repeat(AbnormalReadingMonitor.PID_COOLANT, 122.0)
        assertNotNull(raised)
        assertTrue(!raised!!.suppressed)
        assertEquals(AbnormalSeverity.SERIOUS, raised.severity)
    }

    // -----------------------------------------------------------------------------------
    // Fault codes a modification accounts for
    // -----------------------------------------------------------------------------------

    @Test
    fun `a de-catted car stops being told about P0420`() {
        val mods = VehicleModifications(listOf(Modification("catalyst", ModKind.REMOVED)))
        assertNotNull("the code every de-catted car sets", mods.explains("P0420"))
        assertNotNull(mods.explains("P0430"))
        assertNull("but not an unrelated misfire", mods.explains("P0301"))
    }

    @Test
    fun `manufacturer variants of a suppressed code are matched too`() {
        // Land Rover's P1436 is in the same family as the generic P0400s, and listing every
        // manufacturer's variant of every code is not a list anyone can keep correct.
        val mods = VehicleModifications(listOf(Modification("egr", ModKind.BLANKED)))
        assertNotNull(mods.explains("P0401"))
        assertNotNull(mods.explains("P1436"))
        assertNull(mods.explains("P0171"))
    }

    @Test
    fun `swirl flap removal covers the codes it actually causes`() {
        val mods = VehicleModifications(listOf(Modification("swirl_flaps", ModKind.REMOVED)))
        assertNotNull("the M47's own code", mods.explains("P2015"))
        assertNotNull(mods.explains("P2004"))
    }

    @Test
    fun `every catalogued component can do something concrete`() {
        // A modification the diagnostic logic ignores is a form that does nothing. Each
        // entry has to affect at least one parameter or at least one code, or it should not
        // be offered.
        ModificationCatalogue.all.forEach { component ->
            assertTrue(
                "${component.id} affects nothing, so declaring it would change nothing",
                component.affectedPids.isNotEmpty() || component.affectedCodes.isNotEmpty(),
            )
            assertTrue("${component.id} offers no kind of change", component.kinds.isNotEmpty())
        }
    }

    @Test
    fun `component ids are unique`() {
        val ids = ModificationCatalogue.all.map { it.id }
        assertEquals("duplicate ids would make one unreachable", ids.size, ids.distinct().size)
    }

    // -----------------------------------------------------------------------------------
    // Storage
    // -----------------------------------------------------------------------------------

    @Test
    fun `modifications survive a round trip through the vehicle file`() {
        val mods = listOf(
            Modification("egr", ModKind.BLANKED, "Blanking plate, March 2024", 1_700_000_000_000),
            Modification("swirl_flaps", ModKind.REMOVED, "", 1_700_000_100_000),
        )
        val decoded = Garage.decodeModifications(Garage.encodeModifications(mods))

        assertEquals(2, decoded.size)
        assertEquals("egr", decoded[0].componentId)
        assertEquals(ModKind.BLANKED, decoded[0].kind)
        assertEquals("Blanking plate, March 2024", decoded[0].note)
        assertEquals(1_700_000_000_000, decoded[0].recordedAt)
        assertEquals(ModKind.REMOVED, decoded[1].kind)
    }

    @Test
    fun `a corrupt modification is dropped rather than losing the whole car`() {
        // The vehicle file also holds the VIN, the name and the history pointer. A record
        // that will not parse must cost that record, not somebody's entire car history.
        val decoded = Garage.decodeModifications("egr;BLANKED;123;fine|nonsense|;;;|x;NOTAKIND;1;")
        assertEquals(1, decoded.size)
        assertEquals("egr", decoded[0].componentId)
    }

    @Test
    fun `no modifications encodes and decodes as none`() {
        assertEquals(emptyList<Modification>(), Garage.decodeModifications(""))
        assertEquals("", Garage.encodeModifications(emptyList()))
    }

    // -----------------------------------------------------------------------------------
    // The pressure-based load estimate
    // -----------------------------------------------------------------------------------

    @Test
    fun `boost pressure gives a usable load figure where airflow is missing`() {
        // What the Freelander needs. Its throttle reads a flat 0.00% however it is driven,
        // so falling straight from airflow to throttle produced a gauge pinned at nothing —
        // no more use than the 100% it replaced.
        val e = LoadEstimator()
        assertNull("no airflow reported", e.fromAirflow(null, 800.0))

        repeat(30) { e.fromPressure(mapKilopascals = 100.0, rpm = 800.0, intakeAirC = 20.0) }
        e.fromPressure(mapKilopascals = 210.0, rpm = 3000.0, intakeAirC = 45.0)

        val idle = e.fromPressure(100.0, 800.0, 20.0)!!
        val boost = e.fromPressure(210.0, 3000.0, 45.0)!!

        assertTrue("idle must not read full, got ${idle.percent}", idle.percent < 15.0)
        assertTrue("on boost must read high, got ${boost.percent}", boost.percent > 85.0)
        assertTrue(boost.basis.contains("pressure"))
    }

    @Test
    fun `the pressure estimate never claims to be more than a proxy`() {
        val e = LoadEstimator()
        repeat(600) { e.fromPressure(100.0 + (it % 100), 1500.0, 25.0) }
        val settled = e.fromPressure(150.0, 1500.0, 25.0)!!
        assertTrue(
            "pressure cannot know injection quantity, so it never reaches GOOD",
            settled.confidence != LoadEstimator.Confidence.GOOD,
        )
    }

    @Test
    fun `a missing intake temperature still produces a figure`() {
        val e = LoadEstimator()
        repeat(30) { e.fromPressure(100.0, 800.0, null) }
        e.fromPressure(200.0, 3000.0, null)
        assertNotNull(e.fromPressure(150.0, 2000.0, null))
    }
}
