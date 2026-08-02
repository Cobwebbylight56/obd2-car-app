package com.rhys.obd2

import com.rhys.obd2.obd.Dtc
import com.rhys.obd2.obd.DtcDatabase
import com.rhys.obd2.obd.DtcSeverity
import com.rhys.obd2.obd.DtcStatus
import com.rhys.obd2.obd.DtcSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcTest {

    @Test
    fun `decodes each system letter from the top two bits`() {
        assertEquals("P0301", Dtc.decodePair(0x03, 0x01))
        assertEquals("C0035", Dtc.decodePair(0x40, 0x35))
        assertEquals("B0001", Dtc.decodePair(0x80, 0x01))
        assertEquals("U0001", Dtc.decodePair(0xC0, 0x01))
    }

    @Test
    fun `the low nibble of the first byte is the high digit of the remainder`() {
        // Easy to get backwards: U0100 is 0xC1 0x00, not 0xC0 0x01. Getting it wrong
        // silently renames every code in the second half of a manufacturer's range.
        assertEquals("U0100", Dtc.decodePair(0xC1, 0x00))
        assertEquals("P0420", Dtc.decodePair(0x04, 0x20))
        assertEquals("P0171", Dtc.decodePair(0x01, 0x71))
        assertEquals("C1234", Dtc.decodePair(0x52, 0x34))
    }

    @Test
    fun `decodes the first digit from the next two bits`() {
        // 0x23 0x01 -> P2301: bits 6-7 are 00 (P), bits 4-5 are 10 (digit 2).
        assertEquals("P2301", Dtc.decodePair(0x23, 0x01))
        assertEquals("P1234", Dtc.decodePair(0x12, 0x34))
    }

    @Test
    fun `treats the all-zero pair as padding rather than a code`() {
        assertNull(Dtc.decodePair(0x00, 0x00))
    }

    @Test
    fun `strips the leading count byte on a CAN response`() {
        // 43 03 0133 0420 0171 -> the parser hands us everything after "43".
        val payload = intArrayOf(0x03, 0x01, 0x33, 0x04, 0x20, 0x01, 0x71)
        val codes = Dtc.decodeList(payload, DtcStatus.STORED).map { it.code }
        assertEquals(listOf("P0133", "P0420", "P0171"), codes)
    }

    @Test
    fun `reads a count-less response from the older protocols`() {
        // ISO 9141-2 and KWP send codes with no count byte, so the payload is even.
        val payload = intArrayOf(0x01, 0x33, 0x04, 0x20)
        val codes = Dtc.decodeList(payload, DtcStatus.STORED).map { it.code }
        assertEquals(listOf("P0133", "P0420"), codes)
    }

    @Test
    fun `drops the zero padding that fills an unused code slot`() {
        val payload = intArrayOf(0x02, 0x01, 0x71, 0x00, 0x00, 0x00, 0x00)
        val codes = Dtc.decodeList(payload, DtcStatus.STORED).map { it.code }
        assertEquals(listOf("P0171"), codes)
    }

    @Test
    fun `attaches a description and severity to a known code`() {
        val dtc = Dtc.describe("P0171", DtcStatus.STORED)
        assertTrue(dtc.description.contains("lean", ignoreCase = true))
        assertEquals(DtcSystem.POWERTRAIN, dtc.system)
        assertFalse(dtc.manufacturerSpecific)
        assertTrue(dtc.advice != null)
    }

    @Test
    fun `generates the per-cylinder misfire family`() {
        for (cylinder in 1..12) {
            val dtc = Dtc.describe("P%04d".format(300 + cylinder), DtcStatus.STORED)
            assertTrue(
                "cylinder $cylinder description was '${dtc.description}'",
                dtc.description.contains("Cylinder $cylinder"),
            )
            assertEquals(DtcSeverity.SERIOUS, dtc.severity)
        }
    }

    @Test
    fun `generates the per-cylinder ignition coil family`() {
        val coil4 = Dtc.describe("P0354", DtcStatus.STORED)
        assertTrue(coil4.description.contains("Ignition coil 4"))
        val coil12 = Dtc.describe("P0362", DtcStatus.STORED)
        assertTrue(coil12.description.contains("Ignition coil 12"))
    }

    @Test
    fun `flags manufacturer-specific codes instead of guessing at them`() {
        val dtc = Dtc.describe("P1234", DtcStatus.STORED)
        assertTrue(dtc.manufacturerSpecific)
        assertTrue(dtc.description.contains("Manufacturer-specific"))
    }

    @Test
    fun `falls back to the code's structure for unknown generic codes`() {
        val dtc = Dtc.describe("P0999", DtcStatus.STORED)
        assertFalse(dtc.manufacturerSpecific)
        assertTrue(dtc.description.contains("Transmission", ignoreCase = true))
    }

    @Test
    fun `search finds a code family from a partial code`() {
        val misfires = DtcDatabase.search("P030").map { it.first }
        assertTrue(misfires.contains("P0301"))
        assertTrue(misfires.contains("P0304"))
        // A code-shaped query must not also drag in description matches.
        assertTrue(misfires.all { it.startsWith("P030") })
    }

    @Test
    fun `search falls back to matching the description`() {
        val results = DtcDatabase.search("catalyst").map { it.first }
        assertTrue("expected P0420 among $results", results.contains("P0420"))
    }

    @Test
    fun `search ignores queries too short to be useful`() {
        assertTrue(DtcDatabase.search("P").isEmpty())
    }

    @Test
    fun `well-formed codes are recognised regardless of case or spacing`() {
        assertTrue(DtcDatabase.isWellFormed("P0420"))
        assertTrue(DtcDatabase.isWellFormed(" p0420 "))
        assertTrue(DtcDatabase.isWellFormed("U0100"))
        assertFalse(DtcDatabase.isWellFormed("P042"))
        assertFalse(DtcDatabase.isWellFormed("X0420"))
        // The second character is the code's definer and only runs 0-3.
        assertFalse(DtcDatabase.isWellFormed("P9420"))
    }

    @Test
    fun `classifies network and chassis codes by their letter`() {
        assertEquals(DtcSystem.NETWORK, Dtc.describe("U0100", DtcStatus.STORED).system)
        assertEquals(DtcSystem.CHASSIS, Dtc.describe("C0035", DtcStatus.STORED).system)
        assertEquals(DtcSystem.BODY, Dtc.describe("B0001", DtcStatus.STORED).system)
    }
}
