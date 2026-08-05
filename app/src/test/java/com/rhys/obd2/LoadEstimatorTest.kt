package com.rhys.obd2

import com.rhys.obd2.data.LoadEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived load figure, for cars whose ECU will not supply a usable one.
 *
 * The risk is not that the number is imprecise — it is stated as an estimate. The risk is
 * that it looks like a measurement, so the tests pin the cases where it must refuse to
 * produce one at all rather than produce something plausible.
 *
 * **These assertions changed once the estimator met a real car.** They used to say that
 * airflow at the learned maximum reads 100% and that half of it reads 50% — a plain
 * fraction of the peak. That is arithmetically tidy and wrong on a vehicle, because it
 * assumes an engine drawing no air is at zero load and no running engine draws no air. The
 * first reading of a session was therefore always 100%, and on a diesel — which runs
 * unthrottled and breathes nearly as hard at idle as at full power — it stayed there. A
 * Freelander idling would have shown exactly the 100.0% the estimate exists to replace.
 *
 * The scale is now anchored at both ends: the quietest air-per-revolution seen is zero and
 * the largest is a hundred. Which means the old assertions were pinning the defect in
 * place, and are rewritten here rather than removed — the behaviour they described is
 * still worth stating, it just needs both anchors named.
 */
class LoadEstimatorTest {

    /** Establishes a floor and a ceiling so the scale has a span to report against. */
    private fun LoadEstimator.learnRange() {
        fromAirflow(mafGramsPerSecond = 4.0, rpm = 800.0)      // idle: 0.005 g per rev
        fromAirflow(mafGramsPerSecond = 40.0, rpm = 2000.0)    // pull: 0.020 g per rev
    }

    @Test
    fun `airflow at the learned maximum reads full`() {
        val e = LoadEstimator()
        e.learnRange()
        assertEquals(100.0, e.fromAirflow(40.0, 2000.0)!!.percent, 0.1)
    }

    @Test
    fun `airflow at the learned minimum reads empty`() {
        // The anchor the first version did not have, and the reason it read 100% at idle.
        val e = LoadEstimator()
        e.learnRange()
        assertEquals(0.0, e.fromAirflow(4.0, 800.0)!!.percent, 0.1)
    }

    @Test
    fun `halfway between the floor and the ceiling reads about half`() {
        val e = LoadEstimator()
        e.learnRange()                                          // 0.005 to 0.020 g per rev
        val middle = e.fromAirflow(25.0, 2000.0)!!              // 0.0125 g per rev
        assertEquals(50.0, middle.percent, 0.1)
    }

    @Test
    fun `the same airflow at twice the speed is less load`() {
        // Still the central idea: load is air per revolution, not air per second. An engine
        // pulling the same airflow at double the rpm is working half as hard per stroke.
        val e = LoadEstimator()
        e.learnRange()
        val slow = e.fromAirflow(40.0, 2000.0)!!                // 0.020 g per rev
        val fast = e.fromAirflow(40.0, 4000.0)!!                // 0.010 g per rev
        assertTrue(
            "same airflow at twice the speed must read lower, got ${fast.percent} vs ${slow.percent}",
            fast.percent < slow.percent,
        )
        assertEquals(33.3, fast.percent, 0.5)                   // (0.010-0.005)/0.015
    }

    @Test
    fun `the learned ceiling only ever rises`() {
        val e = LoadEstimator()
        e.fromAirflow(4.0, 800.0)                               // floor
        e.fromAirflow(20.0, 2000.0)                             // 0.010 g per rev
        val beforeHarderPull = e.fromAirflow(20.0, 2000.0)!!.percent
        e.fromAirflow(40.0, 2000.0)                             // a harder pull raises it
        val after = e.fromAirflow(20.0, 2000.0)!!.percent

        assertEquals("was the ceiling", 100.0, beforeHarderPull, 0.1)
        assertTrue("must now read lower against a higher ceiling", after < beforeHarderPull)
        assertEquals(33.3, after, 0.5)                          // (0.010-0.005)/0.015
    }

    @Test
    fun `the learned floor only ever falls`() {
        val e = LoadEstimator()
        e.fromAirflow(20.0, 2000.0)                             // 0.010, floor and ceiling
        e.fromAirflow(40.0, 2000.0)                             // 0.020, new ceiling
        val before = e.fromAirflow(20.0, 2000.0)!!.percent
        e.fromAirflow(4.0, 800.0)                               // 0.005, a quieter idle
        val after = e.fromAirflow(20.0, 2000.0)!!.percent

        assertEquals("was the floor", 0.0, before, 0.1)
        assertTrue("must now read above a lower floor", after > before)
    }

    @Test
    fun `one reading claims nothing`() {
        // Floor and ceiling are the same number, so the position between them is undefined.
        // Zero is the honest answer and, unlike a hundred, does not look like a fault.
        val e = LoadEstimator()
        assertEquals(0.0, e.fromAirflow(40.0, 2000.0)!!.percent, 0.001)
    }

    @Test
    fun `a stationary engine produces nothing rather than a wild number`() {
        // Dividing by a cranking speed sends the ratio towards infinity, which would read
        // as full load on a car that is not running.
        val e = LoadEstimator()
        assertNull(e.fromAirflow(2.0, 0.0))
        assertNull(e.fromAirflow(2.0, 100.0))
    }

    @Test
    fun `missing inputs produce nothing`() {
        val e = LoadEstimator()
        assertNull(e.fromAirflow(null, 2000.0))
        assertNull(e.fromAirflow(30.0, null))
        assertNull(e.fromAirflow(0.0, 2000.0))
        assertNull(e.fromThrottle(null))
    }

    @Test
    fun `confidence starts low and says so`() {
        val e = LoadEstimator()
        val first = e.fromAirflow(30.0, 2000.0)!!
        assertEquals(LoadEstimator.Confidence.LEARNING, first.confidence)
        assertTrue(first.basis.contains("airflow"))
    }

    @Test
    fun `confidence improves once the engine has been worked`() {
        val e = LoadEstimator()
        repeat(500) { e.fromAirflow(20.0 + (it % 30), 1500.0 + (it % 40) * 50.0) }
        val settled = e.fromAirflow(30.0, 2000.0)!!
        assertEquals(LoadEstimator.Confidence.GOOD, settled.confidence)
    }

    @Test
    fun `the throttle fallback is labelled as the poor substitute it is`() {
        val e = LoadEstimator()
        val t = e.fromThrottle(42.0)!!
        assertEquals(42.0, t.percent, 0.01)
        assertEquals(LoadEstimator.Confidence.LEARNING, t.confidence)
        assertTrue("must say what it is based on", t.basis.contains("throttle"))
    }

    @Test
    fun `the result never leaves the range a percentage can occupy`() {
        val e = LoadEstimator()
        e.learnRange()
        assertTrue(e.fromAirflow(80.0, 2000.0)!!.percent <= 100.0)
        assertTrue(e.fromAirflow(0.001, 6000.0)!!.percent >= 0.0)
        assertTrue(e.fromThrottle(140.0)!!.percent <= 100.0)
        assertTrue(e.fromThrottle(-5.0)!!.percent >= 0.0)
    }

    @Test
    fun `resetting forgets both anchors`() {
        // A different car, or the same car after a repair, must not inherit either end of
        // the old scale — a ceiling learned on a 2.0 litre would flatter a 1.0, and a floor
        // learned on one idle would put another car permanently below zero.
        val e = LoadEstimator()
        e.learnRange()
        assertEquals(100.0, e.fromAirflow(40.0, 2000.0)!!.percent, 0.1)

        e.reset()
        assertEquals("the ceiling is gone", 0.0, e.learnedPeak, 0.0)
        assertEquals("the floor is gone", 0.0, e.learnedIdle, 0.0)

        // Checked after the anchors, because this reading establishes new ones.
        assertEquals(
            "nothing learned yet, so nothing claimed",
            0.0, e.fromAirflow(40.0, 2000.0)!!.percent, 0.1,
        )
    }
}
