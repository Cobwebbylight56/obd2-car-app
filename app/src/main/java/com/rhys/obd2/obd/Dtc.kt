package com.rhys.obd2.obd

/** Where a code was read from, which changes what it means for the driver. */
enum class DtcStatus(val label: String, val explanation: String) {
    STORED(
        "Confirmed",
        "The fault has happened enough times for the car to commit to it. This is what turns the engine light on.",
    ),
    PENDING(
        "Pending",
        "Seen once but not yet confirmed. If it doesn't recur over the next few drive cycles it will clear itself.",
    ),
    PERMANENT(
        "Permanent",
        "Recorded by the emissions system and cannot be cleared with a scan tool. It only clears once the car has verified the repair itself.",
    ),
}

/** Rough triage so the list can be sorted by what actually matters. */
enum class DtcSeverity(val label: String) {
    CRITICAL("Stop driving"),
    SERIOUS("Get it looked at"),
    MODERATE("Worth fixing"),
    MINOR("Low priority"),
    UNKNOWN("Unknown"),
}

/** Which of the car's systems the code belongs to, from the code's first letter. */
enum class DtcSystem(val letter: Char, val label: String) {
    POWERTRAIN('P', "Powertrain — engine and transmission"),
    CHASSIS('C', "Chassis — brakes, steering, suspension"),
    BODY('B', "Body — interior, airbags, comfort"),
    NETWORK('U', "Network — module communication"),
}

data class Dtc(
    val code: String,
    val description: String,
    val status: DtcStatus,
    val system: DtcSystem,
    val severity: DtcSeverity,
    /** True when the code is manufacturer-defined, so the description is a best guess. */
    val manufacturerSpecific: Boolean,
    /** Practical next steps, where we have something useful to say. */
    val advice: String? = null,
) {
    companion object {

        /**
         * Decodes the two-byte on-the-wire form into a code string like "P0301".
         *
         * The top two bits select the system letter, the next two are the first digit,
         * and the remaining twelve bits are the last three hex digits. 0x0000 is the
         * standard's padding value and means "no code here".
         */
        fun decodePair(first: Int, second: Int): String? {
            if (first == 0 && second == 0) return null
            val letter = when ((first shr 6) and 0x03) {
                0 -> 'P'
                1 -> 'C'
                2 -> 'B'
                else -> 'U'
            }
            val firstDigit = (first shr 4) and 0x03
            val remainder = ((first and 0x0F) shl 8) or (second and 0xFF)
            return "%c%d%03X".format(letter, firstDigit, remainder)
        }

        /**
         * Pulls every code out of a service 03/07/0A payload.
         *
         * The payload is a run of two-byte codes, but CAN responses prefix it with a
         * count byte and the older protocols (ISO 9141-2, KWP2000, J1850) don't. Guessing
         * wrong shifts every code by one byte and produces plausible-looking nonsense —
         * P0301 read as C3304 — so the alignment is decided by parity, which is exact:
         * with a count byte the payload length is odd, without one it is even.
         */
        fun decodeList(data: IntArray, status: DtcStatus): List<Dtc> {
            if (data.isEmpty()) return emptyList()

            val primary = if (data.size % 2 == 1) 1 else 0
            val codes = extractCodes(data, primary)
            // If that alignment yields nothing, the response was malformed in some other
            // way; the opposite alignment is a better answer than none.
            val chosen = codes.ifEmpty { extractCodes(data, 1 - primary) }

            return chosen.distinct().map { describe(it, status) }
        }

        private fun extractCodes(data: IntArray, offset: Int): List<String> {
            val codes = mutableListOf<String>()
            var i = offset
            while (i + 1 < data.size) {
                decodePair(data[i], data[i + 1])?.let { codes += it }
                i += 2
            }
            return codes
        }

        fun describe(code: String, status: DtcStatus): Dtc {
            val system = when (code.firstOrNull()) {
                'C' -> DtcSystem.CHASSIS
                'B' -> DtcSystem.BODY
                'U' -> DtcSystem.NETWORK
                else -> DtcSystem.POWERTRAIN
            }
            // The second character says who defined the code: 0 and 2 are SAE-generic,
            // 1 and 3 are the manufacturer's own.
            val manufacturerSpecific = code.getOrNull(1) in listOf('1', '3')
            val known = DtcDatabase.lookup(code)

            return Dtc(
                code = code,
                description = known?.description ?: DtcDatabase.structuralDescription(code),
                status = status,
                system = system,
                severity = known?.severity ?: DtcDatabase.inferSeverity(code),
                manufacturerSpecific = manufacturerSpecific,
                advice = known?.advice,
            )
        }
    }
}
