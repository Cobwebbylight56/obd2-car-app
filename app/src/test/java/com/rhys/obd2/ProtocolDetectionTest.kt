package com.rhys.obd2

import com.rhys.obd2.elm.Elm327
import com.rhys.obd2.elm.ObdError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol detection, which is where a working adapter gets reported as a broken one.
 *
 * A single `ATSP0` followed by a single `0100` looks like it detects the protocol and
 * mostly does — on CAN. On the pre-CAN protocols the search needs a 5-baud initialisation
 * taking seconds per attempt, and clone chips routinely give up before reaching it. The
 * symptom is an adapter that is plainly alive announcing that it cannot reach a car it is
 * perfectly capable of reaching, which is what a 2003 vehicle produced.
 */
class ProtocolDetectionTest {

    @Test
    fun `a positive response to the probe is recognised`() {
        // 0x41 is the positive response to service 01, so the reply to 0100 starts 4100.
        assertTrue(Elm327.respondedToProbe("41 00 BE 3F A8 13", null))
        assertTrue(Elm327.respondedToProbe("4100BE3FA813", null))
    }

    @Test
    fun `the searching notice alongside a real answer still counts`() {
        // Adapters print this while negotiating and it can share the buffer with the answer.
        assertTrue(Elm327.respondedToProbe("SEARCHING...\r41 00 BE 3F A8 13", null))
    }

    @Test
    fun `headers and echo do not defeat the check`() {
        assertTrue(Elm327.respondedToProbe("7E8 06 41 00 BE 3F A8 13", null))
        assertTrue(Elm327.respondedToProbe("0100\r41 00 BE 3F A8 13\r>", null))
    }

    @Test
    fun `an error means no answer even if the text looks plausible`() {
        assertFalse(Elm327.respondedToProbe("41 00 BE 3F A8 13", ObdError.UNABLE_TO_CONNECT))
    }

    @Test
    fun `an unrelated or empty response is not an answer`() {
        assertFalse(Elm327.respondedToProbe("UNABLE TO CONNECT", null))
        assertFalse(Elm327.respondedToProbe("NO DATA", null))
        assertFalse(Elm327.respondedToProbe("", null))
        // A reply to a different service must not be mistaken for one to 0100.
        assertFalse(Elm327.respondedToProbe("43 01 33", null))
    }

    @Test
    fun `the walk covers every protocol the chip supports`() {
        val codes = Elm327.PROTOCOL_WALK.map { it.code }.toSet()
        assertEquals(
            "protocols 1 to 9 must all be reachable by hand",
            setOf("1", "2", "3", "4", "5", "6", "7", "8", "9"),
            codes,
        )
    }

    @Test
    fun `the pre-CAN protocols are tried before CAN`() {
        // Reaching the walk at all means automatic detection failed, and automatic
        // detection is good at CAN — so CAN is the unlikely answer by then.
        val order = Elm327.PROTOCOL_WALK.map { it.code }
        val preCan = listOf("3", "4", "5").map { order.indexOf(it) }
        val can = listOf("6", "7", "8", "9").map { order.indexOf(it) }
        assertTrue(
            "pre-CAN protocols should come first, order was $order",
            preCan.max() < can.min(),
        )
    }

    @Test
    fun `fast init is tried before the slow inits it can save`() {
        val order = Elm327.PROTOCOL_WALK.map { it.code }
        assertTrue(
            "KWP fast init costs 2s against 12s for a 5-baud init, so it goes first",
            order.indexOf("5") < order.indexOf("3") && order.indexOf("5") < order.indexOf("4"),
        )
    }

    @Test
    fun `slow init protocols get a longer budget than fast ones`() {
        val byCode = Elm327.PROTOCOL_WALK.associateBy { it.code }
        val slowInit = listOf("3", "4").mapNotNull { byCode[it]?.timeoutMs }
        val fast = listOf("6", "7", "8", "9").mapNotNull { byCode[it]?.timeoutMs }
        assertTrue(
            "a 5-baud initialisation takes seconds and must not be cut off",
            slowInit.min() > fast.max(),
        )
    }

    @Test
    fun `the whole walk stays within a tolerable wait`() {
        // This only runs when nothing else worked, but it still has to end. A minute and a
        // half of "trying" with no result is indistinguishable from a hang.
        val worstCase = Elm327.PROTOCOL_WALK.sumOf { it.timeoutMs }
        assertTrue("worst case was ${worstCase}ms", worstCase <= 75_000)
    }

    @Test
    fun `every candidate is labelled for the progress display`() {
        // The label is shown while it runs. Without it a long search looks like a freeze.
        Elm327.PROTOCOL_WALK.forEach {
            assertTrue("protocol ${it.code} needs a human-readable label", it.label.length > 5)
        }
    }
}
