package com.rhys.obd2

import com.rhys.obd2.data.UnitSystem
import com.rhys.obd2.data.Units
import com.rhys.obd2.obd.Mode06
import com.rhys.obd2.obd.Mode09
import com.rhys.obd2.obd.Readiness
import com.rhys.obd2.obd.VinDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {

    @Test
    fun `reads the MIL bit and stored code count from byte A`() {
        val readiness = Readiness.decode(intArrayOf(0x83, 0x07, 0x00, 0x00))!!
        assertTrue(readiness.milOn)
        assertEquals(3, readiness.dtcCount)
    }

    @Test
    fun `reports the light off when the top bit is clear`() {
        val readiness = Readiness.decode(intArrayOf(0x00, 0x07, 0x00, 0x00))!!
        assertFalse(readiness.milOn)
        assertEquals(0, readiness.dtcCount)
    }

    @Test
    fun `treats a set bit in byte D as not complete`() {
        // Byte C: catalyst and EGR supported. Byte D: catalyst incomplete.
        val readiness = Readiness.decode(intArrayOf(0x00, 0x07, 0x81, 0x01))!!
        val catalyst = readiness.monitors.first { it.name == "Catalyst" }
        assertTrue(catalyst.supported)
        assertFalse(catalyst.complete)

        val egr = readiness.monitors.first { it.name == "EGR system" }
        assertTrue(egr.supported)
        assertTrue(egr.complete)
    }

    @Test
    fun `reports ready only when every supported monitor has run`() {
        val ready = Readiness.decode(intArrayOf(0x00, 0x07, 0xFF, 0x00))!!
        assertTrue(ready.readyForTest)
        assertEquals(0, ready.incompleteCount)

        val notReady = Readiness.decode(intArrayOf(0x00, 0x07, 0xFF, 0x05))!!
        assertFalse(notReady.readyForTest)
        assertEquals(2, notReady.incompleteCount)
    }

    @Test
    fun `switches to the diesel monitor set when the compression bit is set`() {
        val diesel = Readiness.decode(intArrayOf(0x00, 0x0F, 0xFF, 0x00))!!
        assertTrue(diesel.compressionIgnition)
        assertTrue(diesel.monitors.any { it.name == "PM filter" })
        assertFalse(diesel.monitors.any { it.name == "Catalyst" })
    }

    @Test
    fun `continuous monitors follow the low nibble of byte B`() {
        val readiness = Readiness.decode(intArrayOf(0x00, 0x03, 0x00, 0x00))!!
        assertTrue(readiness.monitors.first { it.name == "Misfire" }.supported)
        assertTrue(readiness.monitors.first { it.name == "Fuel system" }.supported)
        assertFalse(readiness.monitors.first { it.name == "Comprehensive components" }.supported)
    }

    @Test
    fun `rejects a truncated response`() {
        assertNull(Readiness.decode(intArrayOf(0x00, 0x07)))
    }
}

class Mode06Test {

    @Test
    fun `decodes a nine-byte test record`() {
        // MID 01, TID 0B, UAS 0x0B (volts), value 0.062 V, min 0.0 V, max 0.15 V
        val data = intArrayOf(0x01, 0x0B, 0x0B, 0x00, 0x3E, 0x00, 0x00, 0x00, 0x96)
        val tests = Mode06.decode(data)
        assertEquals(1, tests.size)
        val test = tests.first()
        assertEquals(0.062, test.value, 0.0001)
        assertEquals(0.150, test.max!!, 0.0001)
        assertEquals("V", test.unit)
        assertTrue(test.passed)
    }

    @Test
    fun `marks a value above its ceiling as failed`() {
        val data = intArrayOf(0x01, 0x0B, 0x0B, 0x00, 0xC8, 0x00, 0x00, 0x00, 0x96)
        assertFalse(Mode06.decode(data).first().passed)
    }

    @Test
    fun `decodes several records from one response`() {
        val data = intArrayOf(
            0x01, 0x0B, 0x0B, 0x00, 0x3E, 0x00, 0x00, 0x00, 0x96,
            0x02, 0x0B, 0x0B, 0x00, 0x4B, 0x00, 0x00, 0x00, 0x96,
        )
        assertEquals(2, Mode06.decode(data).size)
    }

    @Test
    fun `flags an unrecognised scaling code instead of inventing a unit`() {
        val data = intArrayOf(0x01, 0x0B, 0xEE, 0x00, 0x3E, 0x00, 0x00, 0x00, 0x96)
        val test = Mode06.decode(data).first()
        assertTrue(test.rawScaling)
        assertEquals("", test.unit)
        // The comparison still holds even without a known scale.
        assertTrue(test.passed)
    }

    @Test
    fun `names the standard monitor and test identifiers`() {
        assertTrue(Mode06.monitorName(0x01).contains("O2 sensor"))
        assertTrue(Mode06.monitorName(0x53).contains("cylinder 3"))
        assertTrue(Mode06.monitorName(0x71).contains("Catalyst"))
        assertTrue(Mode06.testName(0x05).contains("Rich-to-lean"))
    }

    @Test
    fun `ignores a response too short to hold a record`() {
        assertTrue(Mode06.decode(intArrayOf(0x01, 0x0B)).isEmpty())
    }
}

class VehicleInfoTest {

    @Test
    fun `extracts a VIN from a padded response`() {
        val vin = "WVWZZZ1KZAW123456"
        val payload = intArrayOf(0x01) + vin.map { it.code }.toIntArray()
        assertEquals(vin, Mode09.parseVin(payload))
    }

    @Test
    fun `extracts a VIN even when the ECU pads with nulls`() {
        val vin = "1HGCM82633A004352"
        val payload = intArrayOf(0x00, 0x00, 0x00, 0x01) + vin.map { it.code }.toIntArray()
        assertEquals(vin, Mode09.parseVin(payload))
    }

    @Test
    fun `reads a fixed-width calibration id and strips its padding`() {
        val id = "06A906032HG"
        val payload = intArrayOf(0x01) + id.map { it.code }.toIntArray() + IntArray(16 - id.length)
        assertEquals(listOf(id), Mode09.parseCalibrationIds(payload))
    }

    @Test
    fun `formats calibration verification numbers as hex`() {
        val payload = intArrayOf(0x01, 0x17, 0xE4, 0xA3, 0xB2)
        assertEquals(listOf("17E4A3B2"), Mode09.parseCvns(payload))
    }

    @Test
    fun `decodes the standardised parts of a VIN`() {
        val details = VinDetails.decode("WVWZZZ1KZAW123456")!!
        assertEquals("WVW", details.worldManufacturerId)
        assertEquals("Volkswagen", details.manufacturer)
        assertEquals("Europe", details.region)
        assertNotNull(details.modelYear)
    }

    @Test
    fun `validates the North American check digit`() {
        // A published example VIN with a correct check digit.
        val details = VinDetails.decode("1HGCM82633A004352")!!
        assertTrue(details.valid)
        assertEquals("North America", details.region)
    }

    @Test
    fun `rejects anything that is not seventeen characters`() {
        assertNull(VinDetails.decode("TOOSHORT"))
    }
}

class UnitsTest {

    @Test
    fun `leaves metric values untouched`() {
        val converted = Units.convert(100.0, "km/h", UnitSystem.METRIC)
        assertEquals(100.0, converted.value, 0.001)
        assertEquals("km/h", converted.unit)
    }

    @Test
    fun `converts the units a driver actually reads`() {
        assertEquals(62.137, Units.convert(100.0, "km/h", UnitSystem.IMPERIAL).value, 0.01)
        assertEquals(212.0, Units.convert(100.0, "°C", UnitSystem.IMPERIAL).value, 0.01)
        assertEquals(14.504, Units.convert(100.0, "kPa", UnitSystem.IMPERIAL).value, 0.01)
    }

    @Test
    fun `passes through units with no imperial equivalent`() {
        val converted = Units.convert(2500.0, "rpm", UnitSystem.IMPERIAL)
        assertEquals(2500.0, converted.value, 0.001)
        assertEquals("rpm", converted.unit)
    }

    @Test
    fun `picks decimal places from the magnitude`() {
        assertEquals("2500", Units.format(2500.4, "rpm"))
        assertEquals("0.95", Units.format(0.9532, "V"))
        assertEquals("1.023", Units.format(1.0234, "λ"))
    }
}
