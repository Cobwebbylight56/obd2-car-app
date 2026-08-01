package com.rhys.obd2.obd

/**
 * One emissions self-test the car runs on itself.
 *
 * [complete] is the bit an MOT or emissions test actually cares about: it means the car
 * has finished running that test since the last time its memory was cleared. Clearing
 * codes resets every monitor to incomplete, which is why "just clear it before the test"
 * fails the test — the tester sees the monitors aren't ready.
 */
data class Monitor(
    val name: String,
    val supported: Boolean,
    val complete: Boolean,
    /** What this test is actually checking, for the detail view. */
    val description: String,
)

/**
 * Decoded emissions readiness, from service 01 PID 01 (since codes were cleared) or
 * PID 41 (this drive cycle).
 */
data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<Monitor>,
) {
    val supportedMonitors: List<Monitor> get() = monitors.filter { it.supported }
    val incomplete: List<Monitor> get() = supportedMonitors.filter { !it.complete }

    /** True when every supported monitor has run — the state a test station wants to see. */
    val readyForTest: Boolean get() = incomplete.isEmpty()

    /**
     * Most testing regimes allow a small number of incomplete monitors. Two is the common
     * allowance for older petrol cars, one for newer ones; we report the count and let the
     * summary text explain rather than asserting a pass.
     */
    val incompleteCount: Int get() = incomplete.size

    companion object {

        /**
         * @param data the four bytes returned by PID 01 or 41
         *
         * Byte A carries the MIL bit and the confirmed-DTC count. Byte B holds the three
         * continuous monitors plus the flag for whether this is a petrol or diesel engine,
         * which changes what bytes C and D mean entirely. In C and D, a set bit in C means
         * "supported" and a set bit in D means "not yet complete" — note the inversion,
         * it's a common source of wrong readings.
         */
        fun decode(data: IntArray): Readiness? {
            if (data.size < 4) return null
            val a = data[0]
            val b = data[1]
            val c = data[2]
            val d = data[3]

            val compression = b and 0x08 != 0

            val monitors = mutableListOf<Monitor>()

            // Continuous monitors, same for petrol and diesel.
            monitors += Monitor(
                "Misfire",
                supported = b and 0x01 != 0,
                complete = b and 0x10 == 0,
                description = "Watches crankshaft speed for the momentary stumble a failed combustion causes.",
            )
            monitors += Monitor(
                "Fuel system",
                supported = b and 0x02 != 0,
                complete = b and 0x20 == 0,
                description = "Checks that fuel trims stay within range while running in closed loop.",
            )
            monitors += Monitor(
                "Comprehensive components",
                supported = b and 0x04 != 0,
                complete = b and 0x40 == 0,
                description = "Continuity and plausibility checks on every emissions-related sensor and actuator.",
            )

            val nonContinuous = if (compression) DIESEL_MONITORS else PETROL_MONITORS
            nonContinuous.forEachIndexed { bit, spec ->
                if (spec == null) return@forEachIndexed
                monitors += Monitor(
                    spec.first,
                    supported = c and (1 shl bit) != 0,
                    complete = d and (1 shl bit) == 0,
                    description = spec.second,
                )
            }

            return Readiness(
                milOn = a and 0x80 != 0,
                dtcCount = a and 0x7F,
                compressionIgnition = compression,
                monitors = monitors,
            )
        }

        /** Bit position in bytes C/D → monitor name and description, spark ignition. */
        private val PETROL_MONITORS: List<Pair<String, String>?> = listOf(
            "Catalyst" to "Compares the front and rear oxygen sensors to judge how much work the catalytic converter is still doing.",
            "Heated catalyst" to "Checks the electrically heated catalyst reaches temperature. Fitted to relatively few cars.",
            "Evaporative system" to "Pressure- or vacuum-tests the fuel tank and vapour lines for leaks. Often needs a part-full tank to run.",
            "Secondary air system" to "Verifies the pump that injects air into the exhaust on cold start.",
            "A/C refrigerant" to "Legacy monitor for air conditioning refrigerant leaks. Reserved on most modern cars.",
            "Oxygen sensor" to "Checks the O2 sensors switch quickly enough between rich and lean.",
            "Oxygen sensor heater" to "Confirms the sensor heaters bring the sensors up to temperature.",
            "EGR system" to "Tests that exhaust gas recirculation flows when commanded.",
        )

        /** Same bit positions, compression ignition. Different tests entirely. */
        private val DIESEL_MONITORS: List<Pair<String, String>?> = listOf(
            "NMHC catalyst" to "Checks the non-methane hydrocarbon catalyst is converting as expected.",
            "NOx/SCR aftertreatment" to "Tests the NOx trap or selective catalytic reduction system, including AdBlue dosing where fitted.",
            null,
            "Boost pressure" to "Verifies the turbocharger delivers the boost the ECU commanded.",
            null,
            "Exhaust gas sensor" to "Checks the exhaust gas sensors respond correctly.",
            "PM filter" to "Monitors diesel particulate filter performance and loading.",
            "EGR/VVT system" to "Tests exhaust gas recirculation and variable valve timing operation.",
        )
    }
}
