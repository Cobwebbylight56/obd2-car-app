package com.rhys.obd2

import com.rhys.obd2.elm.Elm327
import com.rhys.obd2.obd.Dtc
import com.rhys.obd2.obd.DtcStatus
import com.rhys.obd2.obd.Mode06
import com.rhys.obd2.obd.Mode09
import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.obd.Readiness
import com.rhys.obd2.transport.DemoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end tests through the real protocol stack against the simulated adapter.
 *
 * The unit tests above check each decoder in isolation; these check the seams between
 * them — command framing, the prompt-based response boundary, and whether a request built
 * by [Elm327] actually round-trips into the value a screen would show. Those seams are
 * where the awkward bugs live, and they're the part that can't be checked without a car.
 */
class DemoVehicleTest {

    private lateinit var transport: DemoTransport
    private lateinit var elm: Elm327
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        transport = DemoTransport()
        transport.connect()
        elm = Elm327(transport, scope)
        elm.start()
        elm.initialise()
        Unit
    }

    @After
    fun tearDown() {
        elm.stop()
        transport.close()
        scope.cancel()
    }

    @Test
    fun `initialisation reaches the vehicle and reports the protocol`() = runBlocking {
        assertEquals("ELM327 v1.5", elm.adapterIdentity)
        assertTrue(elm.protocolDescription!!.contains("CAN"))
    }

    @Test
    fun `reads a live value through the full stack`() = runBlocking {
        val result = elm.obd(0x01, 0x0C, expectedResponses = 1)
        assertTrue(result.raw, result.isSuccess)
        val rpm = PidRegistry[0x0C]!!.decodeSingle(result.data)!!
        assertTrue("rpm was $rpm", rpm in 800.0..3500.0)
    }

    @Test
    fun `discovers the supported PID set`() = runBlocking {
        val result = elm.obd(0x01, 0x00, expectedResponses = 1)
        val supported = com.rhys.obd2.elm.ObdParser.decodeSupportedPids(result.data, 0)
        assertTrue(0x0C in supported)
        assertTrue(0x0D in supported)
    }

    @Test
    fun `reads stored fault codes`() = runBlocking {
        val result = elm.obd(0x03)
        assertTrue(result.isSuccess)
        val codes = Dtc.decodeList(result.data, DtcStatus.STORED).map { it.code }
        assertEquals(listOf("P0301", "P0420", "P0171"), codes)
    }

    @Test
    fun `reads pending and permanent codes from their own services`() = runBlocking {
        val pending = Dtc.decodeList(elm.obd(0x07).data, DtcStatus.PENDING).map { it.code }
        assertEquals(listOf("P0133"), pending)

        val permanent = Dtc.decodeList(elm.obd(0x0A).data, DtcStatus.PERMANENT).map { it.code }
        assertEquals(listOf("P0420"), permanent)
    }

    @Test
    fun `clearing codes empties the stored list and turns the light off`() = runBlocking {
        val before = Readiness.decode(elm.obd(0x01, 0x01, expectedResponses = 1).data)!!
        assertTrue(before.milOn)
        assertEquals(3, before.dtcCount)

        elm.obd(0x04)

        val after = Readiness.decode(elm.obd(0x01, 0x01, expectedResponses = 1).data)!!
        assertFalse(after.milOn)
        assertEquals(0, after.dtcCount)
        assertTrue(Dtc.decodeList(elm.obd(0x03).data, DtcStatus.STORED).isEmpty())
    }

    @Test
    fun `permanent codes survive a clear, as the standard requires`() = runBlocking {
        elm.obd(0x04)
        val permanent = Dtc.decodeList(elm.obd(0x0A).data, DtcStatus.PERMANENT).map { it.code }
        assertEquals(listOf("P0420"), permanent)
    }

    @Test
    fun `reads the VIN and strips the leading item count`() = runBlocking {
        val result = elm.obd(0x09, Mode09.PID_VIN)
        assertTrue(result.isSuccess)
        assertEquals("WVWZZZ1KZAW123456", Mode09.parseVin(result.data))
    }

    @Test
    fun `reads the ECU calibration identity`() = runBlocking {
        assertEquals(
            listOf("06A906032HG"),
            Mode09.parseCalibrationIds(elm.obd(0x09, Mode09.PID_CALIBRATION_ID).data),
        )
        assertEquals("ECM-EngineControl", Mode09.parseEcuName(elm.obd(0x09, Mode09.PID_ECU_NAME).data))
    }

    @Test
    fun `reads service 06 records with the monitor id intact`() = runBlocking {
        // echoesPid = false, because service 06 repeats the monitor ID inside each record.
        val result = elm.obd(0x06, 0x01, echoesPid = false)
        assertTrue(result.isSuccess)
        val tests = Mode06.decode(result.data)
        assertEquals(1, tests.size)
        assertEquals(0x01, tests[0].mid)
        assertEquals(0x0B, tests[0].tid)
        assertEquals(0.062, tests[0].value, 0.0001)
        assertTrue(tests[0].passed)
    }

    @Test
    fun `service 06 support bitmap has the monitor id stripped`() = runBlocking {
        val result = elm.obd(0x06, 0x00)
        val monitors = com.rhys.obd2.elm.ObdParser.decodeSupportedPids(result.data, 0)
        assertTrue(0x01 in monitors)
        assertTrue(0x02 in monitors)
    }

    @Test
    fun `reads a freeze frame`() = runBlocking {
        val result = elm.obd(0x02, 0x05, expectedResponses = 1)
        assertTrue(result.isSuccess)
        // Mode 02 prefixes the payload with the frame number.
        assertEquals(0x00, result.data[0])
        assertNotNull(PidRegistry[0x05]!!.decodeSingle(result.data.drop(1).toIntArray()))
    }

    @Test
    fun `an unsupported PID reports no data rather than a wrong value`() = runBlocking {
        val result = elm.obd(0x01, 0xEE, expectedResponses = 1)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `raw AT commands still work alongside OBD requests`() = runBlocking {
        assertTrue(elm.command("ATRV").contains("V"))
        assertTrue(elm.command("ATDP").contains("CAN"))
    }

    @Test
    fun `many sequential requests stay in sync`() = runBlocking {
        // The prompt-based framing is the thing most likely to drift; a long run catches
        // an off-by-one that a single request would hide.
        repeat(40) {
            val result = elm.obd(0x01, 0x0D, expectedResponses = 1)
            assertTrue("request $it returned ${result.raw}", result.isSuccess)
            assertEquals(1, result.data.size)
        }
    }
}
