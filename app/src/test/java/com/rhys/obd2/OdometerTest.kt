package com.rhys.obd2

import com.rhys.obd2.data.Garage
import com.rhys.obd2.obd.PidRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odometer readings and the one tamper question this app can actually answer.
 *
 * It can prove a reading went down while the app was watching, because readings are kept
 * outside the car with their dates and the car keeps no history of its own. It cannot say
 * anything about a rollback that happened before the first connection — nothing on the
 * OBD-II port can, and pretending otherwise would be worse than saying so.
 */
class OdometerTest {

    private val odometer = PidRegistry[0xA6]!!

    /** The comparison the repository makes, isolated so the arithmetic is checkable. */
    private fun wentBackwards(readings: List<Double>): Boolean {
        if (readings.size < 2) return false
        var highest = readings.first()
        readings.drop(1).forEach { km ->
            if (km < highest - Garage.ODOMETER_TOLERANCE_KM) return true
            if (km > highest) highest = km
        }
        return false
    }

    @Test
    fun `the odometer decodes as tenths of a kilometre`() {
        // 0x0002BF20 is 180,000 tenths, so 18,000.0 km.
        val km = odometer.decode(intArrayOf(0x00, 0x02, 0xBF, 0x20)).first().value
        assertEquals(18_000.0, km, 0.05)
    }

    @Test
    fun `a large reading does not overflow`() {
        // Four bytes exceed Int range, which is why the decode widens to Long first.
        val km = odometer.decode(intArrayOf(0xFF, 0xFF, 0xFF, 0xFF)).first().value
        assertTrue("expected a huge but positive value, got $km", km > 400_000_000)
    }

    @Test
    fun `a rising odometer is never flagged`() {
        assertTrue(!wentBackwards(listOf(120_000.0, 120_450.0, 121_000.0, 138_900.0)))
    }

    @Test
    fun `a decrease is flagged`() {
        assertTrue(wentBackwards(listOf(138_900.0, 121_000.0)))
    }

    @Test
    fun `a decrease is still flagged when later readings climb again`() {
        // Rolled back, then driven. Comparing only against the previous reading would miss
        // it after the first subsequent trip, so the comparison is against the highest ever.
        assertTrue(wentBackwards(listOf(138_900.0, 60_000.0, 60_500.0, 61_000.0)))
    }

    @Test
    fun `reversing off a drive is not a rollback`() {
        // The PID has 0.1 km resolution and a car can genuinely go backwards a few metres.
        assertTrue(!wentBackwards(listOf(120_000.4, 120_000.1)))
    }

    @Test
    fun `a single reading proves nothing either way`() {
        assertTrue(!wentBackwards(listOf(138_900.0)))
        assertTrue(!wentBackwards(emptyList()))
    }

    @Test
    fun `the odometer is not one of the parameters polled by default`() {
        // It changes by a tenth of a kilometre at a time. Polling it in the rotation would
        // spend bandwidth on a number that is effectively static, on a link where
        // bandwidth is the whole constraint.
        assertTrue(0xA6 !in PidRegistry.DEFAULT_DASHBOARD)
    }
}
