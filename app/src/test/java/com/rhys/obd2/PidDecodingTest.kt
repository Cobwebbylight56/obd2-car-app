package com.rhys.obd2

import com.rhys.obd2.obd.PidRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spot-checks the formulas against values computed by hand from SAE J1979.
 *
 * A wrong scale factor is the kind of bug that produces a plausible number and never gets
 * noticed, so these pin the ones that carry real diagnostic weight.
 */
class PidDecodingTest {

    // `vararg bytes: Int` is already an IntArray, which is what decodeSingle wants.
    private fun decode(pid: Int, vararg bytes: Int): Double? =
        PidRegistry[pid]?.decodeSingle(bytes)

    @Test
    fun `engine RPM is a quarter of the 16-bit value`() {
        // 0x1AF8 = 6904, /4 = 1726 rpm
        assertEquals(1726.0, decode(0x0C, 0x1A, 0xF8)!!, 0.001)
    }

    @Test
    fun `coolant temperature is offset by 40 degrees`() {
        assertEquals(90.0, decode(0x05, 130)!!, 0.001)
        assertEquals(-40.0, decode(0x05, 0)!!, 0.001)
    }

    @Test
    fun `vehicle speed is a plain byte in km per hour`() {
        assertEquals(112.0, decode(0x0D, 112)!!, 0.001)
    }

    @Test
    fun `fuel trim spans minus 100 to plus 99 percent`() {
        assertEquals(0.0, decode(0x06, 128)!!, 0.01)
        assertEquals(-100.0, decode(0x06, 0)!!, 0.01)
        assertEquals(25.0, decode(0x06, 160)!!, 0.01)
    }

    @Test
    fun `engine load is a byte scaled across 0 to 100 percent`() {
        assertEquals(100.0, decode(0x04, 255)!!, 0.01)
        assertEquals(50.0, decode(0x04, 128)!!, 0.5)
    }

    @Test
    fun `mass air flow is hundredths of a gram per second`() {
        // 0x0F A0 = 4000, /100 = 40 g/s
        assertEquals(40.0, decode(0x10, 0x0F, 0xA0)!!, 0.001)
    }

    @Test
    fun `control module voltage is in millivolts`() {
        // 0x36 0x1A = 13850 mV
        assertEquals(13.85, decode(0x42, 0x36, 0x1A)!!, 0.001)
    }

    @Test
    fun `timing advance is signed around 64 degrees`() {
        assertEquals(0.0, decode(0x0E, 128)!!, 0.001)
        assertEquals(-64.0, decode(0x0E, 0)!!, 0.001)
        assertEquals(10.0, decode(0x0E, 148)!!, 0.001)
    }

    @Test
    fun `catalyst temperature is tenths of a degree offset by 40`() {
        // 0x15 0xB3 = 5555, /10 = 555.5, -40 = 515.5 degrees
        assertEquals(515.5, decode(0x3C, 0x15, 0xB3)!!, 0.001)
    }

    @Test
    fun `odometer is a 32-bit value in tenths of a kilometre`() {
        // 148233.0 km is 1482330 tenths, which is 0x169E5A
        assertEquals(148233.0, decode(0xA6, 0x00, 0x16, 0x9E, 0x5A)!!, 0.001)
    }

    @Test
    fun `evap vapour pressure is signed`() {
        // 0xFFFC as a signed word is -4, /4 = -1 Pa
        assertEquals(-1.0, decode(0x32, 0xFF, 0xFC)!!, 0.001)
    }

    @Test
    fun `oxygen sensor reports voltage and its associated trim`() {
        val readings = PidRegistry[0x14]!!.decode(intArrayOf(0x20, 0x80))
        assertEquals(2, readings.size)
        assertEquals(0.16, readings[0].value, 0.001)
        assertEquals(0.0, readings[1].value, 0.01)
    }

    @Test
    fun `oxygen sensor omits the trim when the ECU marks it unused`() {
        val readings = PidRegistry[0x14]!!.decode(intArrayOf(0x20, 0xFF))
        assertEquals(1, readings.size)
    }

    @Test
    fun `wideband sensor reports equivalence ratio and voltage`() {
        val readings = PidRegistry[0x24]!!.decode(intArrayOf(0x80, 0x00, 0x20, 0x00))
        assertEquals(1.0, readings[0].value, 0.001)
        assertEquals(1.0, readings[1].value, 0.001)
    }

    @Test
    fun `a short response decodes to nothing rather than a wrong number`() {
        assertTrue(PidRegistry[0x0C]!!.decode(intArrayOf(0x1A)).isEmpty())
    }

    @Test
    fun `enumerated PIDs carry text rather than a bare number`() {
        val reading = PidRegistry[0x51]!!.decode(intArrayOf(4)).first()
        assertEquals("Diesel", reading.text)
    }

    @Test
    fun `every registered PID has a unique id and a sane range`() {
        val ids = PidRegistry.ALL.map { it.id }
        assertEquals("duplicate PID definitions", ids.size, ids.distinct().size)
        PidRegistry.ALL.forEach { pid ->
            assertTrue("PID ${pid.hex} has an inverted range", pid.max > pid.min)
            assertTrue("PID ${pid.hex} claims zero bytes", pid.bytes >= 1)
        }
    }

    @Test
    fun `the default dashboard only references PIDs that exist`() {
        PidRegistry.DEFAULT_DASHBOARD.forEach { assertNotNull(PidRegistry[it]) }
    }
}
