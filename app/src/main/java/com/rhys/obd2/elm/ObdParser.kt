package com.rhys.obd2.elm

/**
 * Turns the ELM327's text output into payload bytes.
 *
 * This is messier than it sounds. Depending on the adapter, the protocol, and whether
 * headers are on, the same logical response can arrive as any of:
 *
 *   41055A                          single frame, spaces off
 *   41 05 5A                        single frame, spaces on
 *   7E8 03 41 05 5A                 headers on
 *   014                             ISO-TP multi-frame: total length, then
 *   0: 49 02 01 31 44 34            indexed continuation lines
 *   1: 47 50 30 30 52 35 35
 *   SEARCHING...                    noise the chip emits while negotiating
 *   41055A                          and multiple ECUs each answering separately
 *   41055C
 *
 * Rather than special-casing each shape, everything is normalised to one hex string and
 * then scanned for the expected response marker (service byte + 0x40, plus the echoed
 * PID). Anything before the marker — headers, length prefixes, other ECUs' noise — is
 * discarded, which handles all of the above uniformly.
 */
object ObdParser {

    private val INDEXED_LINE = Regex("^([0-9A-F]):\\s*(.*)$")

    private val NOISE = listOf(
        "SEARCHING", "BUS INIT", "OK", "ELM327", "ATZ", "NODATA", "?",
    )

    /**
     * @param raw everything the adapter printed before the prompt
     * @param mode the OBD service that was requested (1, 3, 9, …)
     * @param pid the PID that was requested, or null for services that take none
     * @param headersOn whether ATH1 is active, which changes how much junk precedes the payload
     * @return payload bytes after the service byte and echoed PID, or null if no valid response
     */
    fun parse(raw: String, mode: Int, pid: Int?, headersOn: Boolean = false): IntArray? {
        val payload = normalise(raw) ?: return null
        val expectedService = "%02X".format((mode + 0x40) and 0xFF)
        val expectedPid = pid?.let { "%02X".format(it and 0xFF) }

        val marker = expectedService + (expectedPid ?: "")
        val start = findMarker(payload, marker) ?: return null

        val body = payload.substring(start + marker.length)
        return hexToBytes(body)
    }

    /**
     * Every complete message in a response, for services whose reply can span several.
     *
     * This exists for the pre-CAN protocols. ISO 9141-2 and KWP2000 carry at most three
     * DTCs per message and simply send more messages when there are more codes, so a car
     * with five faults answers with two lines:
     *
     *   43 01 33 04 20 01 71
     *   43 02 15 00 00 00 00
     *
     * [parse] deliberately keeps only one line, because on CAN several lines means
     * several ECUs answering the same question and concatenating them would corrupt the
     * value. For the DTC services that rule is wrong and loses every code after the
     * third. CAN's own multi-line form is the indexed ISO-TP one, which is reassembled
     * into a single payload and returned here as one message.
     */
    fun parseMessages(raw: String, mode: Int): List<IntArray> {
        val expected = "%02X".format((mode + 0x40) and 0xFF)

        // Indexed continuation lines are one logical message split by the adapter, so the
        // normal reassembly path is right and per-line splitting would be wrong.
        if (cleanLines(raw).any { INDEXED_LINE.matches(it) }) {
            return listOfNotNull(parse(raw, mode, null))
        }

        return cleanLines(raw).mapNotNull { line ->
            val hex = line.replace(Regex("[^0-9A-F]"), "")
            val start = findMarker(hex, expected) ?: return@mapNotNull null
            hexToBytes(hex.substring(start + expected.length)).takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Same as [parse] but keeps every ECU's answer separately, which matters for cars
     * with more than one controller responding to the same request.
     */
    fun parsePerEcu(raw: String, mode: Int, pid: Int?): List<IntArray> {
        val expectedService = "%02X".format((mode + 0x40) and 0xFF)
        val expectedPid = pid?.let { "%02X".format(it and 0xFF) }
        val marker = expectedService + (expectedPid ?: "")

        return cleanLines(raw).mapNotNull { line ->
            val hex = line.replace(Regex("[^0-9A-F]"), "")
            val start = findMarker(hex, marker) ?: return@mapNotNull null
            hexToBytes(hex.substring(start + marker.length))
        }.filter { it.isNotEmpty() }
    }

    /**
     * Collapses the response to a single uppercase hex string, reassembling ISO-TP
     * continuation frames if the adapter split them.
     */
    fun normalise(raw: String): String? {
        val lines = cleanLines(raw)
        if (lines.isEmpty()) return null

        val indexed = lines.mapNotNull { line ->
            INDEXED_LINE.find(line)?.let { match ->
                match.groupValues[1].toInt(16) to match.groupValues[2].replace(Regex("[^0-9A-F]"), "")
            }
        }

        if (indexed.isNotEmpty()) {
            // Frame indices are a single hex digit and wrap past F on long responses,
            // so trust arrival order rather than sorting by index.
            return indexed.joinToString("") { it.second }
        }

        // Single-frame, possibly repeated once per responding ECU. Concatenating would
        // corrupt the payload, so keep the longest line that still looks like hex.
        //
        // Note there is no parity requirement here: with headers on, an 11-bit CAN reply
        // is prefixed by a three-nibble identifier such as 7E8, leaving the line an odd
        // number of characters. Rejecting those would break header mode entirely.
        val hexLines = lines
            .map { it.replace(Regex("[^0-9A-F]"), "") }
            .filter { it.isNotEmpty() }

        if (hexLines.isEmpty()) return null
        return hexLines.maxByOrNull { it.length }
    }

    private fun cleanLines(raw: String): List<String> =
        raw.split('\r', '\n')
            .map { it.trim().uppercase() }
            .filter { line ->
                line.isNotEmpty() &&
                    line != ">" &&
                    NOISE.none { line.startsWith(it) } &&
                    line.any { it.isDigit() || it in 'A'..'F' || it == ':' }
            }

    /**
     * Finds the response marker on an even byte boundary.
     *
     * The boundary check is what stops a CAN header like 7E8 or a length prefix from
     * being mistaken for payload: a real service byte always starts on a byte boundary
     * relative to the start of the frame we kept.
     */
    private fun findMarker(hex: String, marker: String): Int? {
        var index = hex.indexOf(marker)
        while (index >= 0) {
            if (index % 2 == 0) return index
            index = hex.indexOf(marker, index + 1)
        }
        // Odd-boundary match: better to use it than to report nothing, since some
        // clones emit an odd number of header nibbles.
        return hex.indexOf(marker).takeIf { it >= 0 }
    }

    fun hexToBytes(hex: String): IntArray {
        val clean = hex.replace(Regex("[^0-9A-Fa-f]"), "")
        val usable = if (clean.length % 2 == 0) clean else clean.dropLast(1)
        if (usable.isEmpty()) return IntArray(0)
        return IntArray(usable.length / 2) { i ->
            usable.substring(i * 2, i * 2 + 2).toInt(16)
        }
    }

    fun bytesToHex(bytes: IntArray): String = bytes.joinToString(" ") { "%02X".format(it and 0xFF) }

    /**
     * Decodes a "supported PIDs" bitmask response into the concrete PID numbers.
     *
     * Service 01 PID 00 returns four bytes where the most significant bit of the first
     * byte means "PID 01 is supported", the next bit means PID 02, and so on through
     * PID 20. [base] shifts that window for the 0x20/0x40/0x60/… follow-up requests.
     */
    fun decodeSupportedPids(data: IntArray, base: Int): Set<Int> {
        if (data.size < 4) return emptySet()
        val supported = mutableSetOf<Int>()
        for (byteIndex in 0 until 4) {
            val byte = data[byteIndex]
            for (bit in 0 until 8) {
                if (byte and (0x80 shr bit) != 0) {
                    supported += base + byteIndex * 8 + bit + 1
                }
            }
        }
        return supported
    }
}
