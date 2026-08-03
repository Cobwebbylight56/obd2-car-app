package com.rhys.obd2.data

enum class AbnormalSeverity(val label: String) {
    NOTABLE("Worth noting"),
    SERIOUS("Needs attention"),
}

/** A reading that fell outside its sane operating range, with the reason spelled out. */
data class Abnormality(
    val pid: Int,
    val label: String,
    val value: Double,
    val unit: String,
    val severity: AbnormalSeverity,
    val message: String,
)

/**
 * Watches live readings for values outside a healthy range and reports them once.
 *
 * The difficulty here is not choosing thresholds, it is not crying wolf. A monitor that
 * logs something every session teaches you to ignore it, at which point it is worse than
 * nothing. Two things keep it honest:
 *
 * **Context gating.** Many readings are only meaningful under particular conditions.
 * Battery voltage of 12.3 V is a healthy resting battery with the ignition on and a
 * failing alternator with the engine running — so the charging rules only apply above a
 * cranking RPM. Fuel trims are meaningless until the engine is warm and in closed loop,
 * so they only apply once coolant is up to temperature. Without that gating, every
 * ignition-on-engine-off session would log a charging fault that isn't there.
 *
 * **Once per session.** A fault that persists for ten minutes is one event, not four
 * hundred. The first crossing is recorded with its value and time; the rest are silent
 * until the next connection.
 */
class AbnormalReadingMonitor {

    private val reported = mutableSetOf<Int>()
    private var lastRpm: Double? = null
    private var lastCoolant: Double? = null

    /** Call when a new connection begins, so a recurring fault is logged again. */
    fun reset() {
        reported.clear()
        lastRpm = null
        lastCoolant = null
    }

    /**
     * @return the abnormality to record, or null if the reading is fine, out of context,
     *         or has already been reported this session.
     */
    fun observe(pid: Int, value: Double): Abnormality? {
        when (pid) {
            PID_RPM -> lastRpm = value
            PID_COOLANT -> lastCoolant = value
        }

        if (pid in reported) return null
        val engineRunning = (lastRpm ?: 0.0) > 400
        val engineWarm = (lastCoolant ?: -100.0) > 70

        val abnormality = evaluate(pid, value, engineRunning, engineWarm) ?: return null
        reported += pid
        return abnormality
    }

    private fun evaluate(
        pid: Int,
        value: Double,
        engineRunning: Boolean,
        engineWarm: Boolean,
    ): Abnormality? = when (pid) {

        PID_COOLANT -> when {
            value > 115 -> flag(pid, "Coolant temperature", value, "°C", AbnormalSeverity.SERIOUS,
                "Well above normal running temperature. Stop and let it cool rather than " +
                    "pressing on — continuing risks a warped head or a failed gasket, which " +
                    "costs an order of magnitude more than whatever caused the overheat.")
            value > 105 -> flag(pid, "Coolant temperature", value, "°C", AbnormalSeverity.NOTABLE,
                "Hotter than a healthy engine normally runs. Worth checking the coolant " +
                    "level, the radiator fan and the thermostat.")
            else -> null
        }

        PID_OIL_TEMP -> when {
            value > 135 -> flag(pid, "Oil temperature", value, "°C", AbnormalSeverity.SERIOUS,
                "High enough to thin the oil and accelerate wear. Common after sustained " +
                    "hard driving, but worth investigating if it happens in normal use.")
            else -> null
        }

        // Charging rules only make sense with the engine turning. With the ignition on and
        // the engine off, a perfectly healthy battery reads about 12.4 V.
        PID_VOLTAGE -> when {
            !engineRunning -> null
            value < 12.4 -> flag(pid, "Charging voltage", value, "V", AbnormalSeverity.SERIOUS,
                "The alternator doesn't appear to be charging. Expect the battery to go flat " +
                    "and the car to stop. Check the drive belt and the alternator.")
            value < 13.2 -> flag(pid, "Charging voltage", value, "V", AbnormalSeverity.NOTABLE,
                "Lower than a healthy charging system, which usually sits between 13.5 and " +
                    "14.5 V with the engine running.")
            value > 15.2 -> flag(pid, "Charging voltage", value, "V", AbnormalSeverity.SERIOUS,
                "Overcharging, normally a failed voltage regulator. It will boil the battery " +
                    "and can damage electronics.")
            else -> null
        }

        // Trims are only meaningful once the engine is warm and running closed loop.
        PID_LTFT_1, PID_LTFT_2 -> when {
            !engineWarm || !engineRunning -> null
            kotlin.math.abs(value) > 25 -> flag(pid, trimLabel(pid), value, "%", AbnormalSeverity.SERIOUS,
                "The ECU is making a large correction to the fuel mixture. Positive means it " +
                    "is adding fuel to compensate for a lean condition — typically a vacuum " +
                    "leak or a weak pump; negative means it is cutting fuel because the " +
                    "mixture is rich.")
            kotlin.math.abs(value) > 15 -> flag(pid, trimLabel(pid), value, "%", AbnormalSeverity.NOTABLE,
                "Drifting outside the ±10% a healthy engine usually stays within. Worth " +
                    "watching — this is what a developing vacuum leak looks like before it " +
                    "sets a code.")
            else -> null
        }

        PID_RPM -> when {
            value > 6500 -> flag(pid, "Engine speed", value, "rpm", AbnormalSeverity.NOTABLE,
                "Above the usual redline. Often a missed downshift.")
            else -> null
        }

        PID_INTAKE_AIR -> when {
            value > 90 -> flag(pid, "Intake air temperature", value, "°C", AbnormalSeverity.NOTABLE,
                "Very hot intake air reduces power and can indicate a heat-soaked engine bay " +
                    "or a blocked intake.")
            else -> null
        }

        PID_CAT_TEMP_1, PID_CAT_TEMP_2 -> when {
            value > 900 -> flag(pid, "Catalyst temperature", value, "°C", AbnormalSeverity.SERIOUS,
                "Hot enough to damage the catalytic converter. Usually caused by unburnt " +
                    "fuel reaching it, which points at a misfire or an over-rich mixture.")
            else -> null
        }

        else -> null
    }

    private fun trimLabel(pid: Int): String =
        if (pid == PID_LTFT_1) "Long term fuel trim, bank 1" else "Long term fuel trim, bank 2"

    private fun flag(
        pid: Int,
        label: String,
        value: Double,
        unit: String,
        severity: AbnormalSeverity,
        message: String,
    ) = Abnormality(pid, label, value, unit, severity, message)

    private companion object {
        const val PID_COOLANT = 0x05
        const val PID_LTFT_1 = 0x07
        const val PID_LTFT_2 = 0x09
        const val PID_RPM = 0x0C
        const val PID_INTAKE_AIR = 0x0F
        const val PID_CAT_TEMP_1 = 0x3C
        const val PID_CAT_TEMP_2 = 0x3D
        const val PID_VOLTAGE = 0x42
        const val PID_OIL_TEMP = 0x5C
    }
}
