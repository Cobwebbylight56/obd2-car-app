package com.rhys.obd2.transport

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * A simulated ELM327 attached to a simulated car.
 *
 * This exists so the whole app — discovery, protocol negotiation, gauges, DTC reading,
 * clearing, logging — can be exercised on a desk with no adapter and no vehicle. It
 * speaks the same line protocol as the real chip, including the '>' prompt, so nothing
 * upstream knows the difference.
 */
class DemoTransport : ObdTransport {

    override val name: String get() = "Demo vehicle"

    @Volatile
    private var connected = false
    override val isConnected: Boolean get() = connected

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var echo = true
    private var headers = false
    private var spaces = true
    private val startedAt = System.currentTimeMillis()

    /** Cleared by mode 04, which is how the UI proves the "clear codes" path works. */
    private var storedDtcs = mutableListOf("P0301", "P0420", "P0171")
    private var pendingDtcs = mutableListOf("P0133")
    private var permanentDtcs = mutableListOf("P0420")
    private var milOn = true

    override suspend fun connect() {
        connected = true
    }

    override suspend fun write(data: String) {
        if (!connected) throw ObdConnectionException("Not connected")
        val command = data.trim().uppercase()
        scope.launch {
            // Real adapters take a few milliseconds; without this the UI never shows
            // its loading states and timing bugs stay hidden.
            delay(Random.nextLong(15, 45))
            if (echo) _incoming.emit(command + "\r")
            val body = respond(command)
            if (body.isNotEmpty()) _incoming.emit(body + "\r")
            _incoming.emit("\r>")
        }
    }

    private fun respond(command: String): String {
        if (command.startsWith("AT")) return respondAt(command)
        return respondObd(command.replace(" ", ""))
    }

    private fun respondAt(command: String): String = when {
        command == "ATZ" || command == "AT Z" -> "ELM327 v1.5"
        command.startsWith("ATE") -> { echo = command.endsWith("1"); "OK" }
        command.startsWith("ATH") -> { headers = command.endsWith("1"); "OK" }
        command.startsWith("ATS") && command.length == 4 && command[3] in "01" -> {
            spaces = command.endsWith("1"); "OK"
        }
        command == "ATI" -> "ELM327 v1.5"
        command == "ATRV" -> String.format("%.1fV", 13.8 + sin(seconds() / 7.0) * 0.3)
        command == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        command == "ATDPN" -> "A6"
        else -> "OK"
    }

    private fun respondObd(command: String): String {
        val mode = command.take(2)
        val rest = command.drop(2)
        val payload = when (mode) {
            "01" -> mode01(rest)
            "02" -> mode02(rest)
            "03" -> dtcResponse("43", storedDtcs)
            "04" -> { storedDtcs.clear(); pendingDtcs.clear(); milOn = false; "44" }
            "06" -> mode06(rest)
            "07" -> dtcResponse("47", pendingDtcs)
            "09" -> mode09(rest)
            "0A" -> dtcResponse("4A", permanentDtcs)
            else -> null
        } ?: return "NO DATA"
        return withHeaders(format(payload))
    }

    private fun mode01(pidHex: String): String? {
        val pid = pidHex.take(2).toIntOrNull(16) ?: return null
        val t = seconds()
        // A simulated engine: idles, revs, and warms up over the first two minutes.
        val rpm = (820 + abs(sin(t / 3.1)) * 2600).roundToInt().coerceIn(0, 8000)
        val speed = (abs(sin(t / 11.0)) * 105).roundToInt().coerceIn(0, 160)
        val coolant = (20 + minOf(t, 120.0) * 0.61).roundToInt().coerceIn(-40, 120)
        val load = (18 + abs(sin(t / 4.3)) * 62).roundToInt()

        val bytes: IntArray = when (pid) {
            0x00 -> intArrayOf(0xBE, 0x3F, 0xA8, 0x13)
            0x01 -> intArrayOf(if (milOn) 0x80 or storedDtcs.size else storedDtcs.size, 0x07, 0xE5, 0x00)
            0x03 -> intArrayOf(0x02, 0x00)
            0x04 -> intArrayOf(pct(load))
            0x05 -> intArrayOf(coolant + 40)
            0x06 -> intArrayOf(trim(2.3 + sin(t / 5.0) * 4.0))
            0x07 -> intArrayOf(trim(-1.6))
            0x08 -> intArrayOf(trim(1.9 + sin(t / 5.4) * 3.5))
            0x09 -> intArrayOf(trim(-0.8))
            0x0B -> intArrayOf((28 + abs(sin(t / 4.3)) * 68).roundToInt())
            0x0C -> intArrayOf((rpm * 4) shr 8, (rpm * 4) and 0xFF)
            0x0D -> intArrayOf(speed)
            0x0E -> intArrayOf(((12.5 + sin(t / 6.0) * 8) * 2 + 64).roundToInt().coerceIn(0, 255))
            0x0F -> intArrayOf(24 + 40)
            0x10 -> {
                val maf = ((2.5 + abs(sin(t / 3.1)) * 38) * 100).roundToInt()
                intArrayOf(maf shr 8, maf and 0xFF)
            }
            0x11 -> intArrayOf(pct(14 + abs(sin(t / 4.3)) * 70))
            0x13 -> intArrayOf(0x03)
            0x1C -> intArrayOf(0x06)
            0x1F -> { val s = t.roundToInt(); intArrayOf(s shr 8, s and 0xFF) }
            0x20 -> intArrayOf(0x80, 0x07, 0xB0, 0x11)
            0x21 -> intArrayOf(0x00, 0x2A)
            0x2F -> intArrayOf(pct(63.0))
            0x30 -> intArrayOf(11)
            0x31 -> intArrayOf(0x01, 0x5E)
            0x33 -> intArrayOf(101)
            0x3C -> { val c = ((520 + sin(t / 8.0) * 60 + 40) * 10).roundToInt(); intArrayOf(c shr 8, c and 0xFF) }
            0x40 -> intArrayOf(0xFA, 0xDC, 0x80, 0x15)
            0x42 -> { val mv = (13850 + sin(t / 7.0) * 220).roundToInt(); intArrayOf(mv shr 8, mv and 0xFF) }
            0x43 -> { val v = (load * 255 / 100); intArrayOf(v shr 8, v and 0xFF) }
            0x44 -> intArrayOf(0x80, 0x21)
            0x45 -> intArrayOf(pct(12 + abs(sin(t / 4.3)) * 60))
            0x46 -> intArrayOf(18 + 40)
            0x47 -> intArrayOf(pct(15.0))
            0x49 -> intArrayOf(pct(11 + abs(sin(t / 4.3)) * 64))
            0x4C -> intArrayOf(pct(13 + abs(sin(t / 4.3)) * 66))
            0x4D -> intArrayOf(0x00, if (milOn) 0x2C else 0x00)
            0x4E -> intArrayOf(0x01, 0x90)
            0x51 -> intArrayOf(0x01)
            0x5C -> intArrayOf((coolant + 12).coerceIn(-40, 210) + 40)
            0x5E -> { val f = (3.4 * 20).roundToInt(); intArrayOf(f shr 8, f and 0xFF) }
            0x60 -> intArrayOf(0x00, 0x00, 0x00, 0x01)
            0x80 -> intArrayOf(0x00, 0x00, 0x00, 0x01)
            0xA0 -> intArrayOf(0x04, 0x00, 0x00, 0x00)
            0xA6 -> {
                val km = 148_233 * 10
                intArrayOf(km ushr 24 and 0xFF, km ushr 16 and 0xFF, km ushr 8 and 0xFF, km and 0xFF)
            }
            else -> return null
        }
        return "41" + hex(pid) + bytes.joinToString("") { hex(it) }
    }

    private fun mode02(pidHex: String): String? {
        // Freeze frame: same shape as mode 01 but frame number appended.
        val live = mode01(pidHex.take(2)) ?: return null
        return "42" + live.drop(2).take(2) + "00" + live.drop(4)
    }

    private fun mode06(pidHex: String): String? {
        val mid = pidHex.take(2).toIntOrNull(16) ?: return null
        return when (mid) {
            // Support bitmaps. Bit 0 of the last byte means "another block follows",
            // which is how the app walks up to monitor 0x71.
            0x00 -> "4600C0000001"
            0x20 -> "462000000001"
            0x40 -> "464000000001"
            0x60 -> "466000008000"
            // Records are MID, TID, unit-and-scaling, then value, min and max as words.
            // Note there is no separate PID echo here: the record's own monitor ID is the
            // echo, which is exactly why the parser must not strip it.
            // These two O2 sensor switch-time tests sit comfortably inside their limits.
            0x01 -> "46" + "010B0B003E00000096"
            0x02 -> "46" + "020B0B004B00000096"
            // Catalyst efficiency, deliberately near its ceiling so the margin bar in the
            // UI shows the "passing, but only just" case.
            0x71 -> "46" + "718102008C00320096"
            else -> null
        }
    }

    private fun mode09(pidHex: String): String? = when (pidHex.take(2)) {
        // Supported: PIDs 02 (VIN), 04 (calibration ID), 06 (CVN) and 0A (ECU name).
        "00" -> "490254400000"
        "02" -> {
            // Count byte of 01, then the 17 VIN characters as ASCII.
            val vin = "WVWZZZ1KZAW123456"
            "490201" + vin.map { hex(it.code) }.joinToString("")
        }
        // Count byte, then one 16-byte calibration ID, null-padded as the standard requires.
        "04" -> "490401" + ascii("06A906032HG", 16)
        "06" -> "49060117E4A3B2"
        // Count byte, then one 20-byte ECU name.
        "0A" -> "490A01" + ascii("ECM-EngineControl", 20)
        else -> null
    }

    /** ASCII, null-padded to the fixed block width service 09 uses. */
    private fun ascii(text: String, width: Int): String =
        text.take(width).map { hex(it.code) }.joinToString("") + "00".repeat((width - text.length).coerceAtLeast(0))

    private fun dtcResponse(prefix: String, codes: List<String>): String {
        if (codes.isEmpty()) return prefix + "00"
        val encoded = codes.joinToString("") { encodeDtc(it) }
        return prefix + hex(codes.size) + encoded
    }

    /** Inverse of the DTC decoder: "P0301" -> "0301". */
    private fun encodeDtc(code: String): String {
        val letter = when (code[0]) {
            'P' -> 0; 'C' -> 1; 'B' -> 2; else -> 3
        }
        val digits = code.drop(1).toInt(16)
        val value = (letter shl 14) or digits
        return hex(value shr 8) + hex(value and 0xFF)
    }

    private fun withHeaders(body: String): String =
        if (headers) "7E8 ${body.take(2)} $body" else body

    private fun format(payload: String): String =
        if (spaces) payload.chunked(2).joinToString(" ") else payload

    private fun seconds(): Double = (System.currentTimeMillis() - startedAt) / 1000.0
    private fun pct(v: Double): Int = (v * 255 / 100).roundToInt().coerceIn(0, 255)
    private fun pct(v: Int): Int = pct(v.toDouble())
    private fun trim(v: Double): Int = ((v + 100) * 1.28).roundToInt().coerceIn(0, 255)
    private fun hex(v: Int): String = "%02X".format(v and 0xFF)

    override fun close() {
        connected = false
        scope.cancel()
    }
}
