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

class TripLogTest {

    private fun write(vararg lines: String): java.io.File {
        val file = java.io.File.createTempFile("trip", ".csv")
        file.writeText(lines.joinToString("\n"))
        file.deleteOnExit()
        return file
    }

    @Test
    fun `parses the shape the logger writes`() {
        val log = com.rhys.obd2.data.TripLog.parse(
            write(
                "timestamp,elapsed_s,Engine RPM (rpm),Vehicle speed (km/h)",
                "1000,0.00,800.000,0.000",
                "1500,0.50,1200.000,10.000",
                "2000,1.00,1600.000,20.000",
            )
        )!!
        assertEquals(3, log.sampleCount)
        assertEquals(1.0f, log.durationSeconds, 0.001f)
        assertEquals(2, log.series.size)
        assertEquals("Engine RPM", log.series[0].label)
        assertEquals("rpm", log.series[0].unit)
        assertEquals(800.0f, log.series[0].min!!, 0.001f)
        assertEquals(1600.0f, log.series[0].max!!, 0.001f)
        assertEquals(1200.0f, log.series[0].mean!!, 0.001f)
    }

    @Test
    fun `treats an empty cell as a gap rather than a zero`() {
        // The logger leaves a cell empty when that PID hasn't been read yet. Reading it
        // as 0 would draw a spike to the floor and invent a fault that never happened.
        val log = com.rhys.obd2.data.TripLog.parse(
            write(
                "timestamp,elapsed_s,Coolant (°C)",
                "1000,0.00,",
                "1500,0.50,90.000",
            )
        )!!
        assertNull(log.series[0].values[0])
        assertEquals(90.0f, log.series[0].values[1]!!, 0.001f)
        assertEquals(90.0f, log.series[0].min!!, 0.001f)
    }

    @Test
    fun `ignores a log with only a header`() {
        assertNull(com.rhys.obd2.data.TripLog.parse(write("timestamp,elapsed_s,Engine RPM (rpm)")))
    }

    @Test
    fun `only offers series that recorded something`() {
        val log = com.rhys.obd2.data.TripLog.parse(
            write(
                "timestamp,elapsed_s,Engine RPM (rpm),Never read (%)",
                "1000,0.00,800.000,",
                "1500,0.50,900.000,",
            )
        )!!
        assertEquals(2, log.series.size)
        assertEquals(1, log.populated.size)
        assertEquals("Engine RPM", log.populated[0].label)
    }
}

class GarageStorageTest {

    @Test
    fun `event round trips through the storage format`() {
        val event = com.rhys.obd2.data.VehicleHistoryEvent(
            timestamp = 1_700_000_000_000L,
            type = com.rhys.obd2.data.EventType.CODES_CLEARED,
            title = "3 codes cleared",
            detail = "P0301 — Cylinder 1 misfire\nP0420 — Catalyst below threshold",
        )
        val decoded = com.rhys.obd2.data.Garage.decode(com.rhys.obd2.data.Garage.encode(event))!!
        assertEquals(event, decoded)
    }

    @Test
    fun `detail containing tabs and newlines survives`() {
        // The format is tab-separated, so an unescaped tab in a detail would silently
        // shift every field after it and lose the record.
        val nasty = "line one\tcolumn\nline two\\backslash"
        val event = com.rhys.obd2.data.VehicleHistoryEvent(
            1L, com.rhys.obd2.data.EventType.ABNORMAL, "Odd\treading", nasty,
        )
        val encoded = com.rhys.obd2.data.Garage.encode(event)
        assertEquals(4, encoded.split('\t').size)
        assertEquals(event, com.rhys.obd2.data.Garage.decode(encoded))
    }

    @Test
    fun `a corrupt line is skipped rather than crashing the history`() {
        assertNull(com.rhys.obd2.data.Garage.decode("not a real line"))
        assertNull(com.rhys.obd2.data.Garage.decode("123\tNOT_A_TYPE\ttitle\tdetail"))
        assertNull(com.rhys.obd2.data.Garage.decode(""))
    }
}

class AbnormalReadingMonitorTest {

    private fun monitor() = com.rhys.obd2.data.AbnormalReadingMonitor()

    /**
     * Feeds a reading until the monitor is willing to commit.
     *
     * A single bad reading deliberately no longer raises anything. A value that dips for a
     * moment on a gear change used to put a permanent entry in the car's history, which is
     * how a monitor teaches you to ignore it; it now has to persist. These tests say so
     * explicitly rather than assuming either behaviour.
     */
    private fun com.rhys.obd2.data.AbnormalReadingMonitor.confirm(
        pid: Int,
        value: Double,
    ) = (1..com.rhys.obd2.data.AbnormalReadingMonitor.CONFIRM_READS)
        .mapNotNull { observe(pid, value) }
        .lastOrNull()

    @Test
    fun `flags a genuine overheat`() {
        val m = monitor()
        assertNull(m.observe(0x05, 90.0))
        val hit = m.confirm(0x05, 118.0)!!
        assertEquals(com.rhys.obd2.data.AbnormalSeverity.SERIOUS, hit.severity)
        assertTrue(hit.message.contains("cool", ignoreCase = true))
    }

    @Test
    fun `a single bad reading is not enough`() {
        val m = monitor()
        assertNull("one blip must never reach the car's history", m.observe(0x05, 118.0))
    }

    @Test
    fun `reports each rule once per session`() {
        val m = monitor()
        assertNotNull(m.confirm(0x05, 120.0))
        // A fault that persists for ten minutes is one event, not four hundred.
        assertNull(m.observe(0x05, 121.0))
        assertNull(m.observe(0x05, 125.0))
        m.reset()
        assertNotNull(m.confirm(0x05, 120.0))
    }

    @Test
    fun `does not call a resting battery a charging fault`() {
        val m = monitor()
        // Ignition on, engine off: 12.4 V is a healthy battery, not a dead alternator.
        assertNull(m.confirm(0x42, 12.4))
        assertNull(m.observe(0x0C, 0.0))
        assertNull(m.confirm(0x42, 12.3))
    }

    @Test
    fun `flags low charging voltage once the engine is running`() {
        val m = monitor()
        m.observe(0x0C, 800.0)
        val hit = m.confirm(0x42, 12.1)!!
        assertEquals(com.rhys.obd2.data.AbnormalSeverity.SERIOUS, hit.severity)
        assertTrue(hit.message.contains("alternator", ignoreCase = true))
    }

    @Test
    fun `ignores fuel trims until the engine is warm`() {
        val m = monitor()
        m.observe(0x0C, 900.0)
        m.observe(0x05, 20.0)
        // Trims swing wildly on a cold engine in open loop; flagging them is noise.
        assertNull(m.confirm(0x07, 30.0))

        val warm = monitor()
        warm.observe(0x0C, 900.0)
        warm.observe(0x05, 88.0)
        assertNotNull(warm.confirm(0x07, 30.0))
    }

    @Test
    fun `leaves healthy readings alone`() {
        val m = monitor()
        m.observe(0x0C, 850.0)
        m.observe(0x05, 90.0)
        assertNull(m.confirm(0x42, 14.1))
        assertNull(m.confirm(0x07, 3.0))
        assertNull(m.confirm(0x0F, 25.0))
        assertNull(m.confirm(0x5C, 95.0))
    }
}
