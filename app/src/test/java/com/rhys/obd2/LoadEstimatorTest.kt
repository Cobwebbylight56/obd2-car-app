package com.rhys.obd2

import com.rhys.obd2.data.LoadEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived load figure, for cars whose ECU will not supply one.
 *
 * The risk is not that the number is imprecise — it is stated as an estimate. The risk is
 * that it looks like a measurement, so the tests pin the cases where it must refuse to
 * produce one at all rather than produce something plausible.
 */
class LoadEstimatorTest {

    @Test
    fun `airflow at the learned maximum reads full`() {
        val e = LoadEstimator()
        e.fromAirflow(mafGramsPerSecond = 40.0, rpm = 2000.0)
        val at = e.fromAirflow(mafGramsPerSecond = 40.0, rpm = 2000.0)!!
        assertEquals(100.0, at.percent, 0.1)
    }

    @Test
    fun `half the air per revolution reads about half`() {
        val e = LoadEstimator()
        e.fromAirflow(40.0, 2000.0)                       // learns 0.02 g per rev
        val half = e.fromAirflow(20.0, 2000.0)!!          // 0.01 g per rev
        assertEquals(50.0, half.percent, 0.1)
    }

    @Test
    fun `the same airflow at twice the speed is half the load`() {
        // This is the whole idea: load is air per revolution, not air per second. An engine
        // pulling the same airflow at double the rpm is working half as hard per stroke.
        val e = LoadEstimator()
        e.fromAirflow(40.0, 2000.0)
        val faster = e.fromAirflow(40.0, 4000.0)!!
        assertEquals(50.0, faster.percent, 0.1)
    }

    @Test
    fun `the learned maximum only ever rises`() {
        val e = LoadEstimator()
        e.fromAirflow(20.0, 2000.0)
        e.fromAirflow(40.0, 2000.0)                       // a harder pull raises the ceiling
        val back = e.fromAirflow(20.0, 2000.0)!!
        assertEquals("the earlier reading must now be half", 50.0, back.percent, 0.1)
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
        e.fromAirflow(40.0, 2000.0)
        assertTrue(e.fromAirflow(80.0, 2000.0)!!.percent <= 100.0)
        assertTrue(e.fromAirflow(0.001, 6000.0)!!.percent >= 0.0)
        assertTrue(e.fromThrottle(140.0)!!.percent <= 100.0)
        assertTrue(e.fromThrottle(-5.0)!!.percent >= 0.0)
    }

    @Test
    fun `resetting forgets the learned maximum`() {
        // A different car, or the same car after a repair, must not inherit the old ceiling.
        val e = LoadEstimator()
        e.fromAirflow(40.0, 2000.0)
        e.reset()
        val fresh = e.fromAirflow(20.0, 2000.0)!!
        assertEquals("a fresh maximum means this is now full scale", 100.0, fresh.percent, 0.1)
    }
}
