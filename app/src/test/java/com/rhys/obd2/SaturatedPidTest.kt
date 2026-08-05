package com.rhys.obd2

import com.rhys.obd2.obd.PidRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A parameter the car reports as supported but answers with FF every time.
 *
 * This is worse than a parameter that fails, and the reason is that it does not look like
 * a failure. FF decodes to a legal full-scale value — 100% engine load — so the gauge
 * shows a confident number and nothing about it appears broken. On a 2003 Freelander this
 * pinned calculated engine load at 100% at idle and at 28 mph alike.
 *
 * The discriminator has to be persistence rather than the value itself, because a genuine
 * reading can touch full scale under hard acceleration. These tests pin both directions:
 * the fiction is caught, and a real maximum is not thrown away.
 */
class SaturatedPidTest {

    private val load = PidRegistry[0x04]!!

    /** Mirrors the repository rule, kept here so the arithmetic is checkable. */
    private fun isStub(reads: List<IntArray>, threshold: Int = 20): Boolean {
        var run = 0
        reads.forEach { data ->
            if (data.isNotEmpty() && data.all { it == 0xFF }) run++ else run = 0
            if (run >= threshold) return true
        }
        return false
    }

    @Test
    fun `FF decodes to a legal full-scale value, which is why it is deceptive`() {
        // Confirms the premise: nothing about the decode is wrong.
        assertEquals(100.0, load.decode(intArrayOf(0xFF)).first().value, 0.01)
    }

    @Test
    fun `a normal reading decodes sensibly`() {
        // 0x4B is 75, and 75/255 is a little under 30% — a plausible idle load.
        assertEquals(29.4, load.decode(intArrayOf(0x4B)).first().value, 0.2)
    }

    @Test
    fun `an unchanging full-scale answer is treated as unsupported`() {
        assertTrue(isStub(List(25) { intArrayOf(0xFF) }))
    }

    @Test
    fun `a genuine burst at full scale is not`() {
        // Hard acceleration: a handful of maximum readings, then back to normal. Must not
        // be mistaken for a stub, or the gauge would vanish exactly when it matters most.
        val reads = List(6) { intArrayOf(0xFF) } + List(20) { intArrayOf(0x5A) }
        assertTrue(!isStub(reads))
    }

    @Test
    fun `the run has to be consecutive`() {
        // Alternating values are a working sensor at its limit, not a stub.
        val reads = (1..40).map { if (it % 2 == 0) intArrayOf(0xFF) else intArrayOf(0x80) }
        assertTrue(!isStub(reads))
    }

    @Test
    fun `a multi-byte parameter needs every byte saturated`() {
        // FFFF on engine speed is 16383.75 rpm, equally impossible; one FF byte in a real
        // reading is not.
        assertTrue(isStub(List(25) { intArrayOf(0xFF, 0xFF) }))
        assertTrue(!isStub(List(25) { intArrayOf(0xFF, 0x3A) }))
    }

    @Test
    fun `zero is never treated as saturated`() {
        // The opposite end of the scale is a perfectly ordinary reading — a closed
        // throttle, a stationary car — and must never be hidden.
        assertTrue(!isStub(List(40) { intArrayOf(0x00) }))
    }
}
