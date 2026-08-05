package com.rhys.obd2

import com.rhys.obd2.elm.ObdParser
import com.rhys.obd2.obd.Dtc
import com.rhys.obd2.obd.DtcStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the part most likely to break silently on unfamiliar hardware, so the
 * cases here are real response shapes seen from different adapters and protocols rather
 * than invented ones.
 */
class ObdParserTest {

    @Test
    fun `parses a single frame with spaces off`() {
        val data = ObdParser.parse("41055A", mode = 0x01, pid = 0x05)
        assertEquals(1, data!!.size)
        assertEquals(0x5A, data[0])
    }

    @Test
    fun `parses a single frame with spaces on`() {
        val data = ObdParser.parse("41 05 5A", mode = 0x01, pid = 0x05)
        assertEquals(listOf(0x5A), data!!.toList())
    }

    @Test
    fun `strips a CAN header when headers are enabled`() {
        val data = ObdParser.parse("7E8 03 41 05 5A", mode = 0x01, pid = 0x05, headersOn = true)
        assertEquals(listOf(0x5A), data!!.toList())
    }

    @Test
    fun `ignores the searching notice the chip prints while negotiating`() {
        val data = ObdParser.parse("SEARCHING...\r41 0C 1A F8", mode = 0x01, pid = 0x0C)
        assertEquals(listOf(0x1A, 0xF8), data!!.toList())
    }

    @Test
    fun `reassembles an ISO-TP multi frame VIN response`() {
        // The shape an ELM327 emits for service 09 PID 02 with headers off: a total
        // length, then indexed continuation lines.
        val raw = """
            014
            0: 49 02 01 57 56 57
            1: 5A 5A 5A 31 4B 5A 41
            2: 57 31 32 33 34 35 36
        """.trimIndent()

        val data = ObdParser.parse(raw, mode = 0x09, pid = 0x02)!!
        // First byte is the count of data items, then 17 ASCII characters.
        assertEquals(1, data[0])
        val vin = data.drop(1).map { it.toChar() }.joinToString("")
        assertEquals("WVWZZZ1KZAW123456", vin)
    }

    @Test
    fun `keeps one ECU's answer intact when several reply`() {
        // Concatenating these would produce a four-byte value for a two-byte PID.
        val data = ObdParser.parse("41055A\r41055C", mode = 0x01, pid = 0x05)!!
        assertEquals(1, data.size)
    }

    @Test
    fun `separates per-ECU responses when asked to`() {
        val perEcu = ObdParser.parsePerEcu("41055A\r41055C", mode = 0x01, pid = 0x05)
        assertEquals(2, perEcu.size)
        assertEquals(0x5A, perEcu[0][0])
        assertEquals(0x5C, perEcu[1][0])
    }

    @Test
    fun `keeps every message when a pre-CAN car splits its codes across lines`() {
        // ISO 9141-2 and KWP2000 fit three DTCs per message. Five codes therefore arrive
        // as two messages, and keeping only the longest line would lose the last two.
        val raw = "43 01 33 04 20 01 71\r43 02 15 03 01 00 00"
        val messages = ObdParser.parseMessages(raw, mode = 0x03)
        assertEquals(2, messages.size)

        val codes = messages.flatMap { Dtc.decodeList(it, DtcStatus.STORED) }.map { it.code }
        assertEquals(listOf("P0133", "P0420", "P0171", "P0215", "P0301"), codes)
    }

    @Test
    fun `treats an indexed CAN response as the single message it is`() {
        // The indexed form is one logical message the adapter split up, so it must be
        // reassembled rather than treated as several independent messages.
        val raw = """
            00A
            0: 43 04 01 33 04 20
            1: 01 71 02 15 00 00
        """.trimIndent()
        assertEquals(1, ObdParser.parseMessages(raw, mode = 0x03).size)
    }

    @Test
    fun `single-message responses still work through parseMessages`() {
        val messages = ObdParser.parseMessages("43 01 33 04 20 01 71", mode = 0x03)
        assertEquals(1, messages.size)
        assertEquals(listOf(0x01, 0x33, 0x04, 0x20, 0x01, 0x71), messages[0].toList())
    }

    @Test
    fun `returns null when the response is for a different service`() {
        assertNull(ObdParser.parse("43 01 33", mode = 0x01, pid = 0x05))
    }

    @Test
    fun `decodes a supported-PID bitmask`() {
        // 0xBE3FA813: the most significant bit of the first byte is PID 01.
        val supported = ObdParser.decodeSupportedPids(intArrayOf(0xBE, 0x3F, 0xA8, 0x13), base = 0)
        assertTrue(0x01 in supported)
        assertTrue(0x0C in supported)
        assertTrue(0x0D in supported)
        assertTrue(0x20 in supported)
        // Bit for PID 02 is clear in 0xBE.
        assertTrue(0x02 !in supported)
    }

    @Test
    fun `shifts the bitmask window for higher blocks`() {
        val supported = ObdParser.decodeSupportedPids(intArrayOf(0x80, 0x00, 0x00, 0x00), base = 0x20)
        assertEquals(setOf(0x21), supported)
    }

    @Test
    fun `a padding response loses to a real one when both ECUs answer`() {
        // What calculated engine load actually did on a 2003 car: two control units answer,
        // one with a reading and one with FF meaning "not mine". Picking by arrival order
        // pinned the gauge at 100% with a square-wave history while every other reading
        // traced a smooth curve.
        val data = ObdParser.parse("41 04 FF\r41 04 4B", mode = 0x01, pid = 0x04)!!
        assertEquals(0x4B, data[0])
    }

    @Test
    fun `the informative response wins regardless of which arrived first`() {
        val first = ObdParser.parse("41 04 FF\r41 04 4B", mode = 0x01, pid = 0x04)!!
        val second = ObdParser.parse("41 04 4B\r41 04 FF", mode = 0x01, pid = 0x04)!!
        assertEquals("the choice must not depend on arrival order", first.toList(), second.toList())
    }

    @Test
    fun `a genuine full-scale reading survives when it is the only answer`() {
        // Discarding saturated payloads unconditionally would turn a real 100% into no
        // reading at all, so it only applies when there is something else to choose.
        val data = ObdParser.parse("41 04 FF", mode = 0x01, pid = 0x04)!!
        assertEquals(0xFF, data[0])
    }

    @Test
    fun `multi-byte responses are not mistaken for padding`() {
        // 0x0C is two bytes; FF FF is a plausible-looking but saturated pair, while a real
        // reading with one FF byte in it must not be discarded.
        assertTrue(ObdParser.isSaturated("410CFFFF"))
        assertTrue(!ObdParser.isSaturated("410CFF3A"))
        assertTrue(!ObdParser.isSaturated("410C1AF8"))
    }

    @Test
    fun `hex conversion tolerates an odd number of characters`() {
        assertEquals(listOf(0x41, 0x05), ObdParser.hexToBytes("41055").toList())
    }
}
