package com.rhys.obd2.data

/**
 * Works out an engine load figure for cars whose ECU will not provide one.
 *
 * The standard's calculated load is airflow now divided by the most airflow this engine
 * could take at this speed, corrected for pressure and temperature. The second term is the
 * problem: peak airflow per rpm is a property of a particular engine, and nothing on the
 * OBD-II port reports it. So the denominator has to come from somewhere else.
 *
 * It is learned. Air mass per revolution — MAF divided by rpm — is very nearly constant for
 * a given throttle opening, so the largest ratio the engine has ever produced is a fair
 * stand-in for its maximum. Every subsequent reading is expressed as a fraction of that.
 * The consequence is that the figure is meaningless until the engine has been worked at
 * least once, and improves the harder it has been driven, which is why [confidence] is
 * reported alongside the number rather than left implicit.
 *
 * **This is weaker on a diesel, and the Freelander Td4 is a diesel.** A petrol engine
 * controls power with a throttle plate, so airflow tracks load closely. A diesel runs
 * unthrottled and takes in roughly the same air at a given speed whatever it is doing —
 * power comes from how much fuel is injected, and injection quantity is not a generic
 * parameter. On a diesel this reads more like "how hard is it breathing" than true load.
 * That is worth something, and it is not the same thing, so the app says so.
 *
 * Nothing here is presented as the ECU's own figure. It appears under its own name, with
 * its confidence and its inputs stated, so it can never be mistaken for a reading the car
 * supplied.
 */
class LoadEstimator {

    /** How much the learned maximum can be trusted yet. */
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
    private var samples = 0

    /** Highest air-per-revolution seen, exposed for the diagnostic report. */
    val learnedPeak: Double get() = peakAirPerRev

    fun reset() {
        peakAirPerRev = 0.0
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
        if (airPerRev > peakAirPerRev) {
            peakAirPerRev = airPerRev
        }
        samples++

        if (peakAirPerRev <= 0.0) return null
        val percent = (airPerRev / peakAirPerRev * 100.0).coerceIn(0.0, 100.0)

        return Estimate(
            percent = percent,
            confidence = when {
                samples < LEARNING_SAMPLES -> Confidence.LEARNING
                percent >= 95.0 && samples < CONFIDENT_SAMPLES -> Confidence.FAIR
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
     * loads — and on a diesel the reading may not correspond to a throttle plate at all.
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
    }
}
