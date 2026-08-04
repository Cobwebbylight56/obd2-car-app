package com.rhys.obd2

import com.rhys.obd2.transport.AdapterDevice
import com.rhys.obd2.transport.AdapterKind
import com.rhys.obd2.transport.BleTransport
import com.rhys.obd2.transport.DeviceRelevance
import com.rhys.obd2.transport.DeviceScanner
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * The classification decides what the connect screen shows by default, so a mistake here
 * either buries the user's adapter under other people's earbuds or hides it completely.
 * The false-negative direction is the expensive one: an adapter that never appears looks
 * like an unsupported adapter.
 */
class DeviceScannerTest {

    private fun classify(
        name: String?,
        services: List<UUID> = emptyList(),
        bonded: Boolean = false,
    ) = DeviceScanner.classify(name, services, bonded)

    @Test
    fun `an adapter that names itself is a candidate`() {
        assertEquals(DeviceRelevance.LIKELY, classify("OBDII"))
        assertEquals(DeviceRelevance.LIKELY, classify("V-LINK"))
        assertEquals(DeviceRelevance.LIKELY, classify("Vgate iCar Pro"))
        assertEquals(DeviceRelevance.LIKELY, classify("ELM327-BLE"))
    }

    @Test
    fun `the name match ignores case and surrounding text`() {
        assertEquals(DeviceRelevance.LIKELY, classify("my obd dongle"))
        assertEquals(DeviceRelevance.LIKELY, classify("FRIENCITY SCAN TOOL"))
    }

    @Test
    fun `a nameless beacon is not a candidate`() {
        // This is the case that filled the screen: no advertised name, random address.
        assertEquals(DeviceRelevance.OTHER, classify(null))
    }

    @Test
    fun `a named device that is plainly something else is not a candidate`() {
        assertEquals(DeviceRelevance.OTHER, classify("Galaxy Buds"))
        assertEquals(DeviceRelevance.OTHER, classify("LG TV"))
        assertEquals(DeviceRelevance.OTHER, classify("Tile"))
    }

    @Test
    fun `an unnamed dongle is still found by its advertised service`() {
        // Some dongles advertise a serial-over-GATT service and no name at all. Without
        // this rule they would be indistinguishable from the beacons and unreachable.
        val service = BleTransport.SERVICE_HINTS.first()
        assertEquals(DeviceRelevance.LIKELY, classify(null, services = listOf(service)))
    }

    @Test
    fun `an unrelated service UUID does not promote a device`() {
        val heartRate = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        assertEquals(DeviceRelevance.OTHER, classify("Chest strap", listOf(heartRate)))
    }

    @Test
    fun `anything the owner paired is shown even if the name means nothing to us`() {
        // Cheap classic dongles often advertise as bare module names. Pairing was a
        // deliberate act, so it earns a place in the list on its own.
        assertEquals(DeviceRelevance.PAIRED, classify("CBT", bonded = true))
    }

    @Test
    fun `a paired adapter is ranked above a paired pair of headphones`() {
        assertEquals(DeviceRelevance.LIKELY, classify("OBDII", bonded = true))
    }

    @Test
    fun `ordering puts adapters first, then paired devices, then noise`() {
        val ordered = DeviceScanner.order(
            listOf(
                device("Unnamed (AB:CD)", DeviceRelevance.OTHER, rssi = -40),
                device("Car speakers", DeviceRelevance.PAIRED, rssi = -80),
                device("OBDII", DeviceRelevance.LIKELY, rssi = -70),
            )
        )
        assertEquals(listOf("OBDII", "Car speakers", "Unnamed (AB:CD)"), ordered.map { it.name })
    }

    @Test
    fun `signal strength breaks ties within a group`() {
        val ordered = DeviceScanner.order(
            listOf(
                device("Weak adapter", DeviceRelevance.LIKELY, rssi = -90),
                device("Near adapter", DeviceRelevance.LIKELY, rssi = -45),
            )
        )
        assertEquals(listOf("Near adapter", "Weak adapter"), ordered.map { it.name })
    }

    @Test
    fun `relevance only improves as more adverts arrive`() {
        // Adverts from one device vary between packets; a named one followed by an
        // anonymous one must not demote the device back into the hidden group.
        assertEquals(DeviceRelevance.LIKELY, DeviceRelevance.OTHER.or(DeviceRelevance.LIKELY))
        assertEquals(DeviceRelevance.LIKELY, DeviceRelevance.LIKELY.or(DeviceRelevance.OTHER))
        assertEquals(DeviceRelevance.PAIRED, DeviceRelevance.OTHER.or(DeviceRelevance.PAIRED))
    }

    private fun device(name: String, relevance: DeviceRelevance, rssi: Int) = AdapterDevice(
        name = name,
        address = "00:11:22:33:44:55",
        kind = AdapterKind.BLE,
        rssi = rssi,
        relevance = relevance,
    )
}
