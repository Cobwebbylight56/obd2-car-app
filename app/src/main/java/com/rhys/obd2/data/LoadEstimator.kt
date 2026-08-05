package com.rhys.obd2.data

/**
 * Works out an engine load figure for cars whose ECU will not provide a usable one.
 *
 * The standard's calculated load is airflow now divided by the most airflow this engine
 * could take at this speed, corrected for pressure and temperature. The second term is the
 * problem: peak airflow per rpm is a property of a particular engine, and nothing on the
 * OBD-II port reports it. So the denominator has to come from somewhere else.
 *
 * It is learned. Air mass per revolution — MAF divided by rpm — is very nearly constant for
 * a given throttle opening, so the largest ratio the engine has ever produced is a fair
 * stand-in for its maximum. Every subsequent reading is expressed as a fraction of that.
 *
 * **The bottom of the scale has to be learned too, and getting that wrong is what made the
 * first version of this useless.** Expressing a reading as a plain fraction of the peak
 * assumes an engine taking in no air is at zero load, and no running engine takes in no
 * air. On a petrol engine idling that fraction is perhaps a fifth of peak, which reads
 * wrong but not absurdly. On a diesel it is close to the whole of it — a diesel runs
 * unthrottled and swallows nearly as much air per revolution at idle as at full power,
 * because power comes from fuel rather than from air. A Land Rover Td4 at idle would have
 * shown 100%, which is exactly the reading this exists to replace.
 *
 * So the quietest air-per-revolution seen — the idle floor — anchors zero, and the largest
 * anchors a hundred. What is reported is the engine's position between the two, which is a
 * genuine measure of how hard it is breathing relative to its own range.
 *
 * **On a diesel that is still not the same quantity as load, and the app says so.** Fuel
 * injection quantity is what sets a diesel's load, and it is not a generic parameter.
 * Breathing effort correlates with it under acceleration and barely at all under engine
 * braking. That is worth something, and it is not the ECU's figure, so it appears under
 * its own name with its confidence and its inputs stated and can never be mistaken for one.
 */
class LoadEstimator {

    /** How much the learned range can be trusted yet. */
    enum class Confidence(val label: String) {
        LEARNING("Still learning"),
        FAIR("Rough estimate"),
        GOOD("Estimate"),
    }

    data class Estimate(
        val percent: Double,
        val confidence: Confidence,
        val basis: String,
    )

    private var peakAirPerRev = 0.0
    private var idleAirPerRev = Double.MAX_VALUE
    private var samples = 0

    /** Highest air-per-revolution seen, exposed for the diagnostic report. */
    val learnedPeak: Double get() = peakAirPerRev

    /** Lowest air-per-revolution seen, which anchors the bottom of the scale. */
    val learnedIdle: Double get() = if (idleAirPerRev == Double.MAX_VALUE) 0.0 else idleAirPerRev

    fun reset() {
        peakAirPerRev = 0.0
        idleAirPerRev = Double.MAX_VALUE
        samples = 0
    }

    /**
     * @param mafGramsPerSecond mass air flow, PID 10.
     * @param rpm engine speed, PID 0C.
     * @return an estimate, or null when the inputs cannot support one.
     */
    fun fromAirflow(mafGramsPerSecond: Double?, rpm: Double?): Estimate? {
        if (mafGramsPerSecond == null || rpm == null) return null
        // Below a cranking speed the ratio explodes towards infinity and means nothing.
        if (rpm < MIN_RPM || mafGramsPerSecond <= 0.0) return null

        val airPerRev = mafGramsPerSecond / rpm
        if (airPerRev > peakAirPerRev) peakAirPerRev = airPerRev
        if (airPerRev < idleAirPerRev) idleAirPerRev = airPerRev
        samples++

        val span = peakAirPerRev - idleAirPerRev
        // Until the engine has been worked, the floor and the ceiling are the same reading
        // and the fraction is undefined. Reporting zero is honest — nothing has been seen
        // that is harder than the least the engine has done — and, unlike reporting a
        // hundred, it does not look like a fault.
        if (span < MIN_SPAN) {
            return Estimate(0.0, Confidence.LEARNING, "airflow and engine speed")
        }

        val percent = ((airPerRev - idleAirPerRev) / span * 100.0).coerceIn(0.0, 100.0)

        return Estimate(
            percent = percent,
            confidence = when {
                samples < LEARNING_SAMPLES -> Confidence.LEARNING
                samples < CONFIDENT_SAMPLES -> Confidence.FAIR
                else -> Confidence.GOOD
            },
            basis = "airflow and engine speed",
        )
    }

    /**
     * Last resort: the accelerator or throttle position, PID 11.
     *
     * A genuinely poor substitute and labelled as one. Pedal position is a request, not a
     * result — the same pedal at 2000 rpm in top gear and in first is two very different
     * loads — and on a diesel the reading may not correspond to a throttle plate at all,
     * which is why a Freelander sits at a flat 0.00% however it is driven.
     *
     * It is offered only because a rough shape is more use than an empty dial, and only
     * when there is no airflow reading to do better with.
     */
    fun fromThrottle(throttlePercent: Double?): Estimate? {
        if (throttlePercent == null) return null
        return Estimate(
            percent = throttlePercent.coerceIn(0.0, 100.0),
            confidence = Confidence.LEARNING,
            basis = "throttle position only",
        )
    }

    private companion object {
        const val MIN_RPM = 400.0
        const val LEARNING_SAMPLES = 60
        const val CONFIDENT_SAMPLES = 400

        /**
         * How far apart the floor and the ceiling must be before the scale means anything.
         *
         * In grams of air per revolution. A tenth of the idle figure is enough to be
         * outside sensor noise and small enough that one brisk pull-away establishes it.
         */
        const val MIN_SPAN = 0.0005
    }
}
