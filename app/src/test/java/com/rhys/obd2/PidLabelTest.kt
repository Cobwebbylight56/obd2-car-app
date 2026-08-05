package com.rhys.obd2

import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.obd.abbreviate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gauge labels.
 *
 * Half a phone's width fits roughly twenty-six characters over two lines. Fifty parameter
 * names are longer, and on a tile they cut mid-word — "Catalyst temperature — Bank 1 Se…"
 * is indistinguishable from its Bank 2 twin, which is exactly when a dashboard gets read.
 */
class PidLabelTest {

    @Test
    fun `bank and sensor numbers collapse the way a manual writes them`() {
        assertEquals("Catalyst temp B1 S1", abbreviate("Catalyst temperature — Bank 1 Sensor 1"))
        assertEquals("Short fuel trim B1", abbreviate("Short term fuel trim — Bank 1"))
        assertEquals("Long sec. O2 trim B2/4", abbreviate("Long term secondary O2 trim — Bank 2/4"))
    }

    @Test
    fun `the longest names come down to something that fits`() {
        assertEquals("Cmd air-fuel lambda", abbreviate("Commanded air-fuel equivalence ratio"))
        assertEquals("Intake man. abs. press.", abbreviate("Intake manifold absolute pressure"))
        assertEquals("Engine run time", abbreviate("Run time since engine start"))
    }

    @Test
    fun `a name that already fits is left alone`() {
        // Abbreviating something short would make it harder to read for no gain.
        assertEquals("Engine RPM", abbreviate("Engine RPM"))
        assertEquals("Vehicle speed", abbreviate("Vehicle speed"))
        assertEquals("Fuel level", abbreviate("Fuel level"))
    }

    @Test
    fun `abbreviating never produces an empty or broken label`() {
        PidRegistry.ALL.forEach { pid ->
            assertTrue("PID ${pid.hex} abbreviated to nothing", pid.shortName.isNotBlank())
            assertTrue("PID ${pid.hex} has doubled spaces", !pid.shortName.contains("  "))
            assertTrue(
                "PID ${pid.hex} got longer: '${pid.name}' -> '${pid.shortName}'",
                pid.shortName.length <= pid.name.length,
            )
        }
    }

    @Test
    fun `no two parameters end up with the same label`() {
        // The first attempt dropped "(sensors)" and "(extended)", which were the only
        // things separating PID 05 from 67 and 0B from 87 — four pairs of gauges became
        // identical. A shorter label that cannot be told apart is worse than a long one.
        val byLabel = PidRegistry.ALL.groupBy { it.shortName }.filterValues { it.size > 1 }
        assertTrue(
            "labels collide: " + byLabel.map { (l, p) -> "$l <- ${p.map { it.hex }}" },
            byLabel.isEmpty(),
        )
    }

    @Test
    fun `banks stay distinguishable after shortening`() {
        // The failure that mattered: two tiles reading the same because the part that told
        // them apart was the part that got cut.
        val cats = PidRegistry.ALL.filter { it.name.startsWith("Catalyst temperature") }
        assertTrue("expected several catalyst sensors", cats.size >= 2)
        assertEquals("each must stay unique", cats.size, cats.map { it.shortName }.distinct().size)
    }

    @Test
    fun `no gauge label is still absurdly long`() {
        // Not every name can reach 26, but nothing should remain near the original length.
        val stillLong = PidRegistry.ALL.filter { it.shortName.length > 32 }.map { it.shortName }
        assertTrue("still too long for a tile: $stillLong", stillLong.isEmpty())
    }

    @Test
    fun `the full name is kept for lists and reports`() {
        val cat = PidRegistry[0x3C]!!
        assertEquals("Catalyst temperature — Bank 1 Sensor 1", cat.name)
        assertTrue(cat.shortName != cat.name)
    }
}
