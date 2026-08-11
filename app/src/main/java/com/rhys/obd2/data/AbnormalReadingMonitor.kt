package com.rhys.obd2.data

import kotlin.math.abs

enum class AbnormalSeverity(val label: String, val rank: Int) {
    /**
     * Outside normal but not yet wrong — the tier that catches something going bad rather
     * than something already broken. A trim of 12% sets no code and fails no test, and it
     * is what a vacuum leak looks like six months before it strands anyone.
     */
    WATCH("Developing", 1),
    NOTABLE("Worth noting", 2),
    SERIOUS("Needs attention", 3),
}

/** What the reading should have been, and under what circumstances that applies. */
data class Expectation(
    val low: Double?,
    val high: Double?,
    val unit: String,
    /** "once warm and idling", "with the engine running" — when this range is the right one. */
    val whenApplies: String,
) {
    val text: String
        get() = when {
            low != null && high != null -> "${fmt(low)} to ${fmt(high)} $unit"
            low != null -> "above ${fmt(low)} $unit"
            high != null -> "below ${fmt(high)} $unit"
            else -> "—"
        }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else "%.1f".format(java.util.Locale.UK, v)
}

/**
 * A parameter that is reading wrongly, tracked across the whole session rather than reported
 * once and forgotten.
 *
 * [firstSeen] against [lastSeen] and [occurrences] is what separates "it did that once
 * pulling out of a junction" from "it has done that for twenty minutes", and that
 * distinction is usually the diagnosis. [active] going false is kept rather than deleted for
 * the same reason: a fault that comes and goes is a different problem from one that stays,
 * and only a record shows which this is.
 */
data class Abnormality(
    val pid: Int,
    val label: String,
    val value: Double,
    val unit: String,
    val severity: AbnormalSeverity,
    val message: String,
    val expected: Expectation? = null,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val occurrences: Int = 1,
    val active: Boolean = true,
    /** Set when a declared modification accounts for this, instead of it being a fault. */
    val explainedBy: String? = null,
    /** A related reading that suggests this is a consequence rather than a cause. */
    val corroboration: String? = null,
) {
    val suppressed: Boolean get() = explainedBy != null
    val durationMs: Long get() = lastSeen - firstSeen
}

/** Everything the rules are allowed to know about the engine when judging a reading. */
data class EngineContext(
    val rpm: Double? = null,
    val coolant: Double? = null,
    val speed: Double? = null,
    val load: Double? = null,
    val intakeAir: Double? = null,
    val ambient: Double? = null,
    val map: Double? = null,
    val throttle: Double? = null,
    val runMinutes: Double? = null,
) {
    val running: Boolean get() = (rpm ?: 0.0) > 400
    val warm: Boolean get() = (coolant ?: -100.0) > 70
    val idling: Boolean get() = running && (rpm ?: 0.0) < 1_200 && (speed ?: 0.0) <= 0.0
    val cruising: Boolean get() = running && (speed ?: 0.0) > 30
    val underLoad: Boolean get() = (load ?: 0.0) > 55 || (throttle ?: 0.0) > 40

    /**
     * How long the engine has plausibly had to warm up.
     *
     * Used to avoid calling a cold engine faulty. A thermostat rule that fires two minutes
     * after a cold start on a winter morning is a rule nobody will trust by March.
     */
    val hadTimeToWarm: Boolean get() = (runMinutes ?: 0.0) > 12
}

/**
 * Watches every live reading against what it ought to be, in context, and keeps a record.
 *
 * The difficulty here is not choosing thresholds, it is not crying wolf. A monitor that logs
 * something every session teaches you to ignore it, at which point it is worse than nothing.
 * Four things keep it honest:
 *
 * **Context gating.** Most readings only mean something under particular conditions.
 * Battery voltage of 12.3 V is a healthy resting battery with the ignition on and a failing
 * alternator with the engine running. Fuel trims mean nothing until the engine is warm and
 * in closed loop. Coolant at 60 °C is a fault after half an hour and completely normal after
 * two minutes. Every rule states the conditions it needs and stays silent outside them.
 *
 * **Declared modifications.** A de-catted car sets P0420 forever and a blanked EGR reports
 * an error it cannot help. Where the owner has said what was changed, the reading is still
 * shown — going quiet would be its own kind of lying — but marked as explained rather than
 * faulty, with the modification named.
 *
 * **Corroboration.** One reading out of range is a hypothesis. High intake air temperature
 * with the engine stone cold is a hot day; with the engine hot and stationary it is heat
 * soak; under boost on a modified car it is the turbo doing its job. Where a related reading
 * changes the interpretation, the rule looks at it before deciding how serious this is.
 *
 * **Hysteresis.** A value hovering on a threshold must not produce an event every second.
 * A fault has to persist for [CONFIRM_READS] readings before it is raised, and has to come
 * good for [CLEAR_READS] before it is called cleared.
 */
class AbnormalReadingMonitor {

    private val tracked = mutableMapOf<Int, Abnormality>()
    private val pendingRaise = mutableMapOf<Int, Int>()
    private val pendingClear = mutableMapOf<Int, Int>()
    private var context = EngineContext()
    private var modifications = VehicleModifications()
    private var startedAt = System.currentTimeMillis()

    /** Everything seen this session, still-active first and most serious first. */
    val findings: List<Abnormality>
        get() = tracked.values.sortedWith(
            compareByDescending<Abnormality> { it.active }
                .thenByDescending { it.severity.rank }
                .thenByDescending { it.lastSeen }
        )

    fun reset() {
        tracked.clear()
        pendingRaise.clear()
        pendingClear.clear()
        context = EngineContext()
        startedAt = System.currentTimeMillis()
    }

    fun setModifications(mods: VehicleModifications) {
        modifications = mods
    }

    /**
     * Records a reading and returns an event worth writing to the car's history.
     *
     * A return of null is the normal case — the value is fine, or out of context, or it is
     * the four-hundredth reading of a fault already raised. Something comes back only when
     * the state genuinely changed: a fault confirmed, or a fault cleared.
     */
    fun observe(pid: Int, value: Double): Abnormality? {
        remember(pid, value)

        val judgement = judge(pid, value)

        if (judgement == null) {
            pendingRaise.remove(pid)
            return handleGoodReading(pid)
        }

        pendingClear.remove(pid)
        val existing = tracked[pid]

        if (existing != null && existing.active) {
            // Already raised. Keep the record current but stay quiet, unless it has got
            // materially worse — a coolant temperature climbing from 108 to 120 is new
            // information and deserves to be said again.
            val worse = judgement.severity.rank > existing.severity.rank
            tracked[pid] = existing.copy(
                value = value,
                // Enum order is WATCH, NOTABLE, SERIOUS, so the natural maximum is the
                // worse of the two and severity never quietly walks back down.
                severity = maxOf(existing.severity, judgement.severity),
                message = if (worse) judgement.message else existing.message,
                expected = judgement.expected,
                lastSeen = System.currentTimeMillis(),
                occurrences = existing.occurrences + 1,
                corroboration = judgement.corroboration,
            )
            return if (worse) tracked[pid] else null
        }

        val confirmed = (pendingRaise[pid] ?: 0) + 1
        pendingRaise[pid] = confirmed
        if (confirmed < CONFIRM_READS) return null
        pendingRaise.remove(pid)

        // Recurrence within a session is worth carrying across, so a value that keeps
        // flickering in and out of range reads as one intermittent problem.
        val previous = tracked[pid]
        val raised = judgement.copy(
            firstSeen = previous?.firstSeen ?: System.currentTimeMillis(),
            occurrences = (previous?.occurrences ?: 0) + 1,
            active = true,
        )
        tracked[pid] = raised
        return raised
    }

    /** Returns a "cleared" event the first time a raised fault comes properly good. */
    private fun handleGoodReading(pid: Int): Abnormality? {
        val existing = tracked[pid] ?: return null
        if (!existing.active) return null

        val clear = (pendingClear[pid] ?: 0) + 1
        pendingClear[pid] = clear
        if (clear < CLEAR_READS) return null
        pendingClear.remove(pid)

        val cleared = existing.copy(active = false, lastSeen = System.currentTimeMillis())
        tracked[pid] = cleared
        return cleared
    }

    private fun remember(pid: Int, value: Double) {
        val minutes = (System.currentTimeMillis() - startedAt) / 60_000.0
        context = when (pid) {
            PID_RPM -> context.copy(rpm = value)
            PID_COOLANT -> context.copy(coolant = value)
            PID_SPEED -> context.copy(speed = value)
            PID_LOAD -> context.copy(load = value)
            PID_INTAKE_AIR -> context.copy(intakeAir = value)
            PID_AMBIENT -> context.copy(ambient = value)
            PID_MAP -> context.copy(map = value)
            PID_THROTTLE -> context.copy(throttle = value)
            else -> context
        }.copy(runMinutes = minutes)
    }

    // -----------------------------------------------------------------------------------
    // The rules
    // -----------------------------------------------------------------------------------

    private fun judge(pid: Int, value: Double): Abnormality? {
        val raw = rule(pid, value) ?: return null

        // A declared modification does not silence the reading, it re-labels it. The number
        // is still shown and still recorded; what changes is whether it counts as a fault.
        val explanation = modifications.explains(pid)
        return if (explanation != null) {
            val (mod, component) = explanation
            raw.copy(
                severity = AbnormalSeverity.WATCH,
                explainedBy = modifications.describe(mod, component),
                message = buildString {
                    append(raw.message)
                    if (component.expectedInstead.isNotBlank()) {
                        append("\n\nOn this car that is expected: ")
                        append(component.expectedInstead)
                    }
                },
            )
        } else {
            raw
        }
    }

    private fun rule(pid: Int, value: Double): Abnormality? = when (pid) {

        PID_COOLANT -> coolant(value)
        PID_OIL_TEMP -> oilTemperature(value)
        PID_VOLTAGE, PID_ADAPTER_VOLTAGE -> charging(pid, value)
        PID_STFT_1, PID_STFT_2, PID_LTFT_1, PID_LTFT_2 -> fuelTrim(pid, value)
        PID_RPM -> engineSpeed(value)
        PID_INTAKE_AIR -> intakeAir(value)
        PID_CAT_TEMP_1, PID_CAT_TEMP_2 -> catalyst(pid, value)
        PID_EGR_ERROR -> egrError(value)
        PID_FUEL_PRESSURE -> fuelPressure(value)
        PID_MAP -> manifoldPressure(value)
        else -> null
    }

    private fun coolant(value: Double): Abnormality? = when {
        value > 115 -> flag(
            PID_COOLANT, "Coolant temperature", value, "°C", AbnormalSeverity.SERIOUS,
            "Well above normal running temperature. Stop and let it cool rather than " +
                "pressing on — continuing risks a warped head or a failed gasket, which " +
                "costs an order of magnitude more than whatever caused the overheat.",
            Expectation(82.0, 105.0, "°C", "once warmed up"),
        )
        value > 105 -> flag(
            PID_COOLANT, "Coolant temperature", value, "°C", AbnormalSeverity.NOTABLE,
            "Hotter than a healthy engine normally runs. Worth checking the coolant level, " +
                "the radiator fan and the thermostat.",
            Expectation(82.0, 105.0, "°C", "once warmed up"),
            corroboration = context.speed?.let {
                if (it <= 0.0) "Stationary, so the cooling fan is doing all the work — " +
                    "check it is running." else null
            },
        )
        // A thermostat stuck open, which costs fuel and wear and sets no code on many cars.
        context.hadTimeToWarm && context.running && value < 70 -> flag(
            PID_COOLANT, "Coolant temperature", value, "°C", AbnormalSeverity.NOTABLE,
            "The engine has been running long enough to be warm and has not got there. " +
                "Usually a thermostat stuck open. It is not going to break anything today, " +
                "but the engine wears faster, uses more fuel and never reaches closed loop.",
            Expectation(82.0, 105.0, "°C", "after about fifteen minutes running"),
        )
        else -> null
    }

    private fun oilTemperature(value: Double): Abnormality? = when {
        value > 135 -> flag(
            PID_OIL_TEMP, "Oil temperature", value, "°C", AbnormalSeverity.SERIOUS,
            "High enough to thin the oil and accelerate wear. Common after sustained hard " +
                "driving, but worth investigating if it happens in normal use.",
            Expectation(80.0, 120.0, "°C", "once warmed up"),
        )
        value > 125 -> flag(
            PID_OIL_TEMP, "Oil temperature", value, "°C", AbnormalSeverity.WATCH,
            "Warmer than usual. Normal on a hot day towing or driving hard; worth watching " +
                "if it happens gently.",
            Expectation(80.0, 120.0, "°C", "once warmed up"),
        )
        else -> null
    }

    // Charging rules only make sense with the engine turning. With the ignition on and the
    // engine off, a perfectly healthy battery reads about 12.4 V and would look like a fault.
    private fun charging(pid: Int, value: Double): Abnormality? = when {
        !context.running -> when {
            value < 11.8 -> flag(
                pid, "Battery voltage", value, "V", AbnormalSeverity.NOTABLE,
                "Low for a battery at rest. A healthy one sits around 12.4 to 12.7 V with " +
                    "the engine off. It may still start the car today.",
                Expectation(12.2, 12.8, "V", "engine off"),
            )
            else -> null
        }
        value < 12.6 -> flag(
            pid, "Charging voltage", value, "V", AbnormalSeverity.SERIOUS,
            "The alternator does not appear to be charging. Expect the battery to go flat " +
                "and the car to stop. Check the drive belt and the alternator.",
            Expectation(13.5, 14.8, "V", "with the engine running"),
        )
        value < 13.2 -> flag(
            pid, "Charging voltage", value, "V", AbnormalSeverity.WATCH,
            "Lower than a healthy charging system, which usually sits between 13.5 and " +
                "14.8 V with the engine running. Worth watching — a diode failing inside an " +
                "alternator reads exactly like this before it fails altogether.",
            Expectation(13.5, 14.8, "V", "with the engine running"),
        )
        value > 15.2 -> flag(
            pid, "Charging voltage", value, "V", AbnormalSeverity.SERIOUS,
            "Overcharging, normally a failed voltage regulator. It will boil the battery and " +
                "can damage electronics.",
            Expectation(13.5, 14.8, "V", "with the engine running"),
        )
        else -> null
    }

    private fun fuelTrim(pid: Int, value: Double): Abnormality? {
        if (!context.warm || !context.running) return null
        val label = trimLabel(pid)
        val expected = Expectation(-10.0, 10.0, "%", "warm and in closed loop")
        return when {
            abs(value) > 25 -> flag(
                pid, label, value, "%", AbnormalSeverity.SERIOUS,
                if (value > 0) {
                    "The ECU is adding a lot of fuel to compensate for a lean mixture — " +
                        "typically a vacuum leak, a weak fuel pump or a blocked filter."
                } else {
                    "The ECU is cutting a lot of fuel because the mixture is rich — " +
                        "typically a leaking injector or a failing airflow sensor."
                },
                expected,
                corroboration = leanRichCorroboration(value),
            )
            abs(value) > 15 -> flag(
                pid, label, value, "%", AbnormalSeverity.NOTABLE,
                "Outside the ±10% a healthy engine usually stays within.",
                expected,
                corroboration = leanRichCorroboration(value),
            )
            abs(value) > 10 -> flag(
                pid, label, value, "%", AbnormalSeverity.WATCH,
                "Just outside the usual band. This is what a developing vacuum leak looks " +
                    "like months before it sets a code.",
                expected,
            )
            else -> null
        }
    }

    /** Whether another reading supports the lean-or-rich story the trim is telling. */
    private fun leanRichCorroboration(trim: Double): String? {
        val load = context.load ?: return null
        return when {
            trim > 0 && context.idling && load > 45 ->
                "Engine load is also high for an idle, which fits an unmetered air leak."
            trim < 0 && (context.intakeAir ?: 0.0) > 60 ->
                "Intake air is also hot, which can push the mixture rich."
            else -> null
        }
    }

    private fun engineSpeed(value: Double): Abnormality? = when {
        value > 6500 -> flag(
            PID_RPM, "Engine speed", value, "rpm", AbnormalSeverity.NOTABLE,
            "Above the usual redline. Often a missed downshift.",
            Expectation(null, 6500.0, "rpm", "at any time"),
        )
        // A hunting or high idle on a warm engine, which is a real symptom and sets no code.
        context.warm && context.idling && value > 1_100 -> flag(
            PID_RPM, "Idle speed", value, "rpm", AbnormalSeverity.WATCH,
            "The engine is idling faster than it should once warm. Often an air leak, a " +
                "sticking idle control valve, or an electrical load the alternator is " +
                "working against.",
            Expectation(600.0, 1_000.0, "rpm", "warm and stationary"),
        )
        else -> null
    }

    private fun intakeAir(value: Double): Abnormality? {
        val ambient = context.ambient
        return when {
            value > 90 -> flag(
                PID_INTAKE_AIR, "Intake air temperature", value, "°C", AbnormalSeverity.NOTABLE,
                "Very hot intake air reduces power and can indicate a heat-soaked engine bay " +
                    "or a blocked intake.",
                Expectation(null, 60.0, "°C", "moving"),
                corroboration = when {
                    context.idling -> "Stationary, so some heat soak is normal — worth " +
                        "re-checking once moving."
                    context.underLoad -> "Under load, where a turbocharged engine will " +
                        "always run hotter intake air."
                    else -> null
                },
            )
            // Much hotter than the air outside, while moving, means the intake is drawing
            // engine-bay heat rather than outside air.
            ambient != null && context.cruising && value - ambient > 35 -> flag(
                PID_INTAKE_AIR, "Intake air temperature", value, "°C", AbnormalSeverity.WATCH,
                "Intake air is running well above the outside air while the car is moving, " +
                    "which suggests it is drawing warm air from the engine bay rather than " +
                    "from outside.",
                Expectation(null, ambient + 25, "°C", "moving"),
                corroboration = "Outside air is ${"%.0f".format(java.util.Locale.UK, ambient)} °C.",
            )
            else -> null
        }
    }

    private fun catalyst(pid: Int, value: Double): Abnormality? = when {
        value > 900 -> flag(
            pid, "Catalyst temperature", value, "°C", AbnormalSeverity.SERIOUS,
            "Hot enough to damage the catalytic converter. Usually caused by unburnt fuel " +
                "reaching it, which points at a misfire or an over-rich mixture.",
            Expectation(300.0, 800.0, "°C", "warm and running"),
        )
        else -> null
    }

    /**
     * EGR error — how far the valve is from where the ECU asked it to be.
     *
     * Almost always suppressed in practice, because anyone who has blanked the EGR will have
     * said so and [judge] re-labels it. The rule exists for the car that has not been
     * modified, where a large error is a sticking valve worth knowing about early.
     */
    private fun egrError(value: Double): Abnormality? = when {
        abs(value) > 50 -> flag(
            PID_EGR_ERROR, "EGR error", value, "%", AbnormalSeverity.NOTABLE,
            "The EGR valve is a long way from the position the ECU asked for. On a diesel " +
                "that is usually soot holding it partly open or partly shut.",
            Expectation(-20.0, 20.0, "%", "warm and running"),
        )
        abs(value) > 25 -> flag(
            PID_EGR_ERROR, "EGR error", value, "%", AbnormalSeverity.WATCH,
            "The EGR valve is drifting from where it is asked to sit — the early stage of " +
                "one gumming up.",
            Expectation(-20.0, 20.0, "%", "warm and running"),
        )
        else -> null
    }

    private fun fuelPressure(value: Double): Abnormality? = when {
        context.running && value < 100 -> flag(
            PID_FUEL_PRESSURE, "Fuel rail pressure", value, "kPa", AbnormalSeverity.NOTABLE,
            "Lower than a running engine normally holds. A blocked filter or a tiring pump " +
                "shows up here before it shows up as a misfire.",
            Expectation(200.0, null, "kPa", "with the engine running"),
        )
        else -> null
    }

    /**
     * Manifold pressure at idle, which is a surprisingly good health check.
     *
     * A naturally aspirated engine pulls a strong vacuum at idle, so manifold pressure sits
     * well below atmospheric. Close to atmospheric at idle means something is letting air in
     * that should not be — a leak, a stuck valve, or a throttle plate not closing.
     */
    private fun manifoldPressure(value: Double): Abnormality? = when {
        context.warm && context.idling && value > 60 -> flag(
            PID_MAP, "Manifold pressure", value, "kPa", AbnormalSeverity.WATCH,
            "Higher than a healthy engine usually pulls at idle. On a petrol engine that " +
                "points at an air leak or a throttle not fully closing. On a diesel, which " +
                "is unthrottled, a high reading at idle is normal and can be ignored.",
            Expectation(20.0, 50.0, "kPa", "warm, stationary, petrol engine"),
        )
        else -> null
    }

    private fun trimLabel(pid: Int): String = when (pid) {
        PID_STFT_1 -> "Short term fuel trim, bank 1"
        PID_STFT_2 -> "Short term fuel trim, bank 2"
        PID_LTFT_1 -> "Long term fuel trim, bank 1"
        else -> "Long term fuel trim, bank 2"
    }

    private fun flag(
        pid: Int,
        label: String,
        value: Double,
        unit: String,
        severity: AbnormalSeverity,
        message: String,
        expected: Expectation? = null,
        corroboration: String? = null,
    ) = Abnormality(
        pid = pid,
        label = label,
        value = value,
        unit = unit,
        severity = severity,
        message = message,
        expected = expected,
        corroboration = corroboration,
    )

    companion object {
        /** Consecutive bad readings before a fault is raised, so noise never sets one off. */
        const val CONFIRM_READS = 3

        /** Consecutive good readings before a raised fault is called cleared. */
        const val CLEAR_READS = 8

        const val PID_LOAD = 0x04
        const val PID_COOLANT = 0x05
        const val PID_STFT_1 = 0x06
        const val PID_LTFT_1 = 0x07
        const val PID_STFT_2 = 0x08
        const val PID_LTFT_2 = 0x09
        const val PID_FUEL_PRESSURE = 0x0A
        const val PID_MAP = 0x0B
        const val PID_RPM = 0x0C
        const val PID_SPEED = 0x0D
        const val PID_INTAKE_AIR = 0x0F
        const val PID_THROTTLE = 0x11
        const val PID_EGR_ERROR = 0x2D
        const val PID_CAT_TEMP_1 = 0x3C
        const val PID_CAT_TEMP_2 = 0x3D
        const val PID_VOLTAGE = 0x42
        const val PID_AMBIENT = 0x46
        const val PID_OIL_TEMP = 0x5C

        /** Battery voltage measured by the adapter; judged by the same charging rules. */
        const val PID_ADAPTER_VOLTAGE = 0x1042
    }
}
