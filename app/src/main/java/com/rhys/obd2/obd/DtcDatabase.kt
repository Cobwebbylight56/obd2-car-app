package com.rhys.obd2.obd

/**
 * Plain-English descriptions for diagnostic trouble codes.
 *
 * Covers the SAE J2012 generic set (the "0" and "2" codes), which is what any car sold
 * since 2001 in Europe or 1996 in the US must implement. Manufacturer-specific codes
 * (the "1" and "3" ranges) are deliberately not guessed at — they mean different things
 * on different marques, and a confident wrong description is worse than an honest
 * "look this up for your car". Those fall through to [structuralDescription], which says
 * what the code's range covers without pretending to know the specific fault.
 *
 * Severities are a triage aid, not a safety ruling. They answer "does this need a garage
 * this week or this month", and they are conservative where a fault can escalate — a
 * misfire is rated seriously because raw fuel in the exhaust destroys catalytic
 * converters, not because the car will necessarily stop.
 */
object DtcDatabase {

    data class Entry(
        val description: String,
        val severity: DtcSeverity,
        val advice: String? = null,
    )

    fun lookup(code: String): Entry? = table[code.uppercase()]

    /** How many codes carry a real description rather than a structural fallback. */
    val size: Int get() = table.size

    /** A well-formed DTC: one system letter, then four hex digits. */
    private val CODE_SHAPE = Regex("^[PCBU][0-3][0-9A-F]{3}$")

    fun isWellFormed(code: String): Boolean = CODE_SHAPE.matches(code.trim().uppercase())

    /**
     * Searches the offline database.
     *
     * Matches on the code itself when the query looks like one — so "P01" lists the whole
     * P01xx family — and on the description otherwise, so "catalyst" or "misfire" finds
     * the relevant codes without knowing the number. Results are capped because the
     * matching is done on every keystroke.
     */
    fun search(query: String, limit: Int = 60): List<Pair<String, Entry>> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val upper = trimmed.uppercase()

        val byCode = if (upper.first() in "PCBU") {
            table.entries.filter { it.key.startsWith(upper) }
        } else {
            emptyList()
        }
        if (byCode.isNotEmpty()) {
            return byCode.sortedBy { it.key }.take(limit).map { it.key to it.value }
        }

        return table.entries
            .filter { it.value.description.contains(trimmed, ignoreCase = true) }
            .sortedBy { it.key }
            .take(limit)
            .map { it.key to it.value }
    }

    /**
     * Fallback when a code isn't in the table. Decodes what the code's structure alone
     * tells us — the system, whether it's generic or manufacturer-defined, and which
     * subsystem the number falls in.
     */
    fun structuralDescription(code: String): String {
        val upper = code.uppercase()
        val letter = upper.firstOrNull() ?: return "Unrecognised code"
        val second = upper.getOrNull(1)
        val manufacturerSpecific = second in listOf('1', '3')

        val system = when (letter) {
            'P' -> powertrainGroup(upper)
            'C' -> "Chassis system — brakes, ABS, steering or suspension"
            'B' -> "Body system — airbags, lighting, climate or interior electronics"
            'U' -> "Network communication between control modules"
            else -> "Unknown system"
        }

        return if (manufacturerSpecific) {
            "$system. Manufacturer-specific code — the exact meaning depends on the make, " +
                "so check it against your car's own service data."
        } else {
            "$system. This generic code isn't in the offline database; the range above is " +
                "what it covers."
        }
    }

    private fun powertrainGroup(code: String): String {
        val number = code.drop(1).toIntOrNull(16) ?: return "Powertrain fault"
        return when (number) {
            in 0x000..0x099 -> "Fuel and air metering, or an auxiliary emission control"
            in 0x100..0x199 -> "Fuel and air metering — sensors and mixture"
            in 0x200..0x299 -> "Fuel and air metering — injector circuits"
            in 0x300..0x399 -> "Ignition system or engine misfire"
            in 0x400..0x499 -> "Auxiliary emission controls — EGR, EVAP, catalyst or secondary air"
            in 0x500..0x599 -> "Vehicle speed, idle control or auxiliary inputs"
            in 0x600..0x699 -> "Engine control module itself, or its output circuits"
            in 0x700..0x899 -> "Transmission"
            in 0x900..0x999 -> "Transmission or hybrid drive"
            in 0xA00..0xAFF -> "Hybrid or electric propulsion system"
            else -> "Powertrain fault"
        }
    }

    /** Best-effort severity for codes without a table entry. */
    fun inferSeverity(code: String): DtcSeverity {
        val upper = code.uppercase()
        val number = upper.drop(1).toIntOrNull(16) ?: return DtcSeverity.UNKNOWN
        return when (upper.firstOrNull()) {
            'P' -> when (number) {
                in 0x300..0x312 -> DtcSeverity.SERIOUS   // misfire
                in 0x217..0x219 -> DtcSeverity.CRITICAL  // overheat / overspeed
                in 0x440..0x45F -> DtcSeverity.MINOR     // evaporative emissions
                in 0x420..0x439 -> DtcSeverity.MODERATE  // catalyst efficiency
                in 0x600..0x60F -> DtcSeverity.SERIOUS   // ECM internal
                in 0x700..0x999 -> DtcSeverity.SERIOUS   // transmission
                else -> DtcSeverity.MODERATE
            }
            'C' -> DtcSeverity.SERIOUS
            'B' -> DtcSeverity.MINOR
            'U' -> DtcSeverity.MODERATE
            else -> DtcSeverity.UNKNOWN
        }
    }

    private val table: Map<String, Entry> by lazy { buildTable() }

    private fun buildTable(): Map<String, Entry> {
        val map = HashMap<String, Entry>(1200)

        fun parse(block: String) {
            block.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val parts = line.split('|')
                    if (parts.size < 3) return@forEach
                    map[parts[0].trim().uppercase()] = Entry(
                        description = parts[2].trim(),
                        severity = severityOf(parts[1].trim()),
                        advice = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
        }

        parse(FUEL_AND_AIR)
        parse(INJECTORS_AND_BOOST)
        parse(MISFIRE_AND_IGNITION)
        parse(EMISSION_CONTROLS)
        parse(SPEED_IDLE_AND_AUX)
        parse(MODULE_AND_OUTPUT)
        parse(TRANSMISSION)
        parse(P2_RANGE)
        parse(NETWORK_AND_CHASSIS)

        // Per-cylinder families are regular enough to generate, which keeps the tables
        // above readable and guarantees no cylinder gets missed.
        for (cylinder in 1..12) {
            map["P%04d".format(300 + cylinder)] = Entry(
                "Cylinder $cylinder misfire detected",
                DtcSeverity.SERIOUS,
                "A single named cylinder points at that cylinder's own parts: spark plug, coil pack, " +
                    "or injector. The cheapest real test is to swap the coil and plug with a neighbouring " +
                    "cylinder — if the code follows the parts to cylinder ${if (cylinder == 1) 2 else cylinder - 1}, " +
                    "you've found it. If it stays, suspect a compression or injector problem.",
            )
        }
        // Injector circuits run in threes from P0261: low, high, then contribution/balance.
        for (cylinder in 1..8) {
            val base = 261 + (cylinder - 1) * 3
            map["P%04d".format(base)] = Entry(
                "Cylinder $cylinder injector circuit low", DtcSeverity.SERIOUS,
                "Wiring or connector fault on that injector, or the injector's winding has gone open circuit.",
            )
            map["P%04d".format(base + 1)] = Entry(
                "Cylinder $cylinder injector circuit high", DtcSeverity.SERIOUS,
                "Short to voltage on the injector circuit, or a failed driver inside the ECU.",
            )
            map["P%04d".format(base + 2)] = Entry(
                "Cylinder $cylinder contribution/balance fault", DtcSeverity.SERIOUS,
                "That cylinder is producing less than the others. A clogged or leaking injector is the " +
                    "usual cause, but low compression gives the same symptom — a compression test tells them apart.",
            )
        }

        // Ignition coils run one per cylinder from P0351.
        for (cylinder in 1..12) {
            map["P%04d".format(350 + cylinder)] = Entry(
                "Ignition coil $cylinder primary/secondary circuit", DtcSeverity.SERIOUS,
                "Usually the coil pack on cylinder $cylinder, or the connector to it. " +
                    "Often appears alongside a misfire code for the same cylinder.",
            )
        }

        return map
    }

    private fun severityOf(token: String): DtcSeverity = when (token.uppercase()) {
        "C" -> DtcSeverity.CRITICAL
        "S" -> DtcSeverity.SERIOUS
        "M" -> DtcSeverity.MODERATE
        "N" -> DtcSeverity.MINOR
        else -> DtcSeverity.UNKNOWN
    }

    // Format: code | severity (C/S/M/N) | description | optional advice

    private const val FUEL_AND_AIR = """
P0010|M|Camshaft position actuator A circuit, bank 1|Variable valve timing solenoid or its wiring. Often triggered by dirty oil or an overdue oil change.
P0011|M|Camshaft position A — timing over-advanced, bank 1|Commonly low or dirty engine oil starving the VVT actuator. Change the oil and filter before replacing parts.
P0012|M|Camshaft position A — timing over-retarded, bank 1|As above: check oil level and condition first, then the VVT solenoid screen for debris.
P0013|M|Camshaft position actuator B circuit, bank 1
P0014|M|Camshaft position B — timing over-advanced, bank 1
P0015|M|Camshaft position B — timing over-retarded, bank 1
P0016|S|Crankshaft and camshaft position correlation, bank 1 sensor A|The crank and cam are out of sync. Can be a stretched timing chain, a slipped belt, or a failed VVT phaser. Worth investigating promptly — a jumped belt can wreck the engine.
P0017|S|Crankshaft and camshaft position correlation, bank 1 sensor B|As P0016 — timing chain or belt wear is the usual cause.
P0018|S|Crankshaft and camshaft position correlation, bank 2 sensor A
P0019|S|Crankshaft and camshaft position correlation, bank 2 sensor B
P0020|M|Camshaft position actuator A circuit, bank 2
P0021|M|Camshaft position A — timing over-advanced, bank 2
P0022|M|Camshaft position A — timing over-retarded, bank 2
P0030|N|O2 sensor heater control circuit, bank 1 sensor 1|The sensor's internal heater. Usually the sensor itself, occasionally its fuse or relay.
P0031|N|O2 sensor heater circuit low, bank 1 sensor 1
P0032|N|O2 sensor heater circuit high, bank 1 sensor 1
P0036|N|O2 sensor heater control circuit, bank 1 sensor 2
P0037|N|O2 sensor heater circuit low, bank 1 sensor 2
P0038|N|O2 sensor heater circuit high, bank 1 sensor 2
P0050|N|O2 sensor heater control circuit, bank 2 sensor 1
P0051|N|O2 sensor heater circuit low, bank 2 sensor 1
P0052|N|O2 sensor heater circuit high, bank 2 sensor 1
P0056|N|O2 sensor heater control circuit, bank 2 sensor 2
P0068|M|MAP, MAF and throttle position correlation|The three air measurements disagree. Very often a vacuum leak or a split intake hose rather than a failed sensor.
P0070|N|Ambient air temperature sensor circuit
P0071|N|Ambient air temperature sensor range/performance
P0072|N|Ambient air temperature sensor circuit low
P0073|N|Ambient air temperature sensor circuit high
P0087|S|Fuel rail/system pressure too low|On a direct injection or diesel engine this is significant: a failing high-pressure pump, blocked filter, or weak lift pump. Can leave you stranded.
P0088|S|Fuel rail/system pressure too high|Usually a stuck pressure regulator or a blocked return line.
P0100|M|Mass air flow circuit malfunction|MAF sensor or its wiring. Try cleaning the sensor element with dedicated MAF cleaner before buying one.
P0101|M|Mass air flow circuit range/performance|Classic causes: a dirty MAF element, a split intake boot after the sensor, or a clogged air filter.
P0102|M|Mass air flow circuit low input|Often an air leak between the MAF and the throttle body, or a contaminated sensor.
P0103|M|Mass air flow circuit high input
P0104|M|Mass air flow circuit intermittent
P0105|M|Manifold absolute pressure circuit malfunction|MAP sensor, or the vacuum hose feeding it. Check the hose for cracks first — it's free.
P0106|M|Manifold absolute pressure range/performance|Frequently a vacuum leak rather than a bad sensor.
P0107|M|Manifold absolute pressure circuit low input
P0108|M|Manifold absolute pressure circuit high input
P0109|M|Manifold absolute pressure circuit intermittent
P0110|N|Intake air temperature sensor circuit
P0111|N|Intake air temperature sensor range/performance
P0112|N|Intake air temperature sensor circuit low
P0113|N|Intake air temperature sensor circuit high|Very often just a disconnected or corroded plug at the sensor, which lives in the MAF housing on many cars.
P0114|N|Intake air temperature sensor circuit intermittent
P0115|M|Engine coolant temperature sensor circuit
P0116|M|Engine coolant temperature sensor range/performance
P0117|M|Engine coolant temperature sensor circuit low
P0118|M|Engine coolant temperature sensor circuit high|The ECU thinks the engine is freezing, so it over-fuels. Cheap sensor, big effect on running and economy.
P0119|M|Engine coolant temperature sensor circuit intermittent
P0120|M|Throttle/pedal position sensor A circuit
P0121|M|Throttle/pedal position sensor A range/performance|On a drive-by-wire car this can put it into limp mode. Cleaning the throttle body sometimes cures it.
P0122|M|Throttle/pedal position sensor A circuit low
P0123|M|Throttle/pedal position sensor A circuit high
P0124|M|Throttle/pedal position sensor A circuit intermittent
P0125|M|Insufficient coolant temperature for closed loop fuel control|Nearly always a thermostat stuck open, so the engine never reaches operating temperature. Cheap to fix and it will be costing you fuel.
P0128|M|Coolant thermostat below regulating temperature|A stuck-open thermostat. Symptoms are a slow-warming temperature gauge and poor economy. Straightforward repair.
P0130|M|O2 sensor circuit, bank 1 sensor 1
P0131|M|O2 sensor circuit low voltage, bank 1 sensor 1|The upstream sensor is reading permanently lean. Could be the sensor, an exhaust leak before it, or a genuine lean condition.
P0132|M|O2 sensor circuit high voltage, bank 1 sensor 1
P0133|M|O2 sensor circuit slow response, bank 1 sensor 1|A lazy upstream sensor. They wear out — typically at 100,000 miles or so. Replacing it usually fixes economy too.
P0134|M|O2 sensor circuit no activity detected, bank 1 sensor 1|No signal at all: failed sensor, cut wire, or a blown heater fuse.
P0135|N|O2 sensor heater circuit, bank 1 sensor 1
P0136|M|O2 sensor circuit, bank 1 sensor 2
P0137|M|O2 sensor circuit low voltage, bank 1 sensor 2
P0138|M|O2 sensor circuit high voltage, bank 1 sensor 2
P0139|M|O2 sensor circuit slow response, bank 1 sensor 2
P0140|M|O2 sensor circuit no activity, bank 1 sensor 2
P0141|N|O2 sensor heater circuit, bank 1 sensor 2
P0150|M|O2 sensor circuit, bank 2 sensor 1
P0151|M|O2 sensor circuit low voltage, bank 2 sensor 1
P0152|M|O2 sensor circuit high voltage, bank 2 sensor 1
P0153|M|O2 sensor circuit slow response, bank 2 sensor 1
P0154|M|O2 sensor circuit no activity, bank 2 sensor 1
P0155|N|O2 sensor heater circuit, bank 2 sensor 1
P0156|M|O2 sensor circuit, bank 2 sensor 2
P0157|M|O2 sensor circuit low voltage, bank 2 sensor 2
P0158|M|O2 sensor circuit high voltage, bank 2 sensor 2
P0160|M|O2 sensor circuit no activity, bank 2 sensor 2
P0161|N|O2 sensor heater circuit, bank 2 sensor 2
P0170|M|Fuel trim malfunction, bank 1
P0171|M|System too lean, bank 1|The engine is getting more air than the ECU expects. In order of likelihood: a vacuum leak (perished hoses, intake gasket, PCV), a dirty or failing MAF sensor, or a weak fuel pump. Check the Live Data screen — if long term fuel trim sits above +10% at idle but drops at higher revs, that points squarely at a vacuum leak.
P0172|M|System too rich, bank 1|Too much fuel. Common causes: leaking injector, failed fuel pressure regulator, or a clogged air filter. Left alone it will foul plugs and eventually damage the catalytic converter.
P0173|M|Fuel trim malfunction, bank 2
P0174|M|System too lean, bank 2|Same causes as P0171 but on the other cylinder bank. When P0171 and P0174 appear together, the cause is almost always shared — a leak at the intake or a bad MAF — rather than two separate faults.
P0175|M|System too rich, bank 2
P0180|N|Fuel temperature sensor A circuit
P0181|N|Fuel temperature sensor A range/performance
P0190|S|Fuel rail pressure sensor circuit
P0191|S|Fuel rail pressure sensor range/performance
P0192|S|Fuel rail pressure sensor circuit low
P0193|S|Fuel rail pressure sensor circuit high
"""

    private const val INJECTORS_AND_BOOST = """
P0200|S|Injector circuit malfunction
P0201|S|Injector circuit — cylinder 1
P0202|S|Injector circuit — cylinder 2
P0203|S|Injector circuit — cylinder 3
P0204|S|Injector circuit — cylinder 4
P0205|S|Injector circuit — cylinder 5
P0206|S|Injector circuit — cylinder 6
P0207|S|Injector circuit — cylinder 7
P0208|S|Injector circuit — cylinder 8
P0217|C|Engine over-temperature condition|The engine has overheated. Stop driving it. Continuing risks a warped head or blown head gasket, which is an order of magnitude more expensive than whatever caused the overheat.
P0218|S|Transmission over-temperature condition|Usually towing or heavy traffic. If it recurs, the transmission cooler or fluid needs attention.
P0219|S|Engine over-speed condition|The engine was revved past its limit, often from a missed downshift.
P0220|M|Throttle/pedal position sensor B circuit
P0221|M|Throttle/pedal position sensor B range/performance
P0222|M|Throttle/pedal position sensor B circuit low
P0223|M|Throttle/pedal position sensor B circuit high
P0230|S|Fuel pump primary circuit|Fuel pump relay or its wiring. Can cause a no-start.
P0231|S|Fuel pump secondary circuit low
P0232|S|Fuel pump secondary circuit high
P0234|S|Turbocharger/supercharger overboost condition|A stuck wastegate or a failed boost control solenoid. Damaging if ignored — back off and get it checked.
P0235|M|Turbocharger boost sensor A circuit
P0236|M|Turbocharger boost sensor A range/performance
P0237|M|Turbocharger boost sensor A circuit low
P0238|M|Turbocharger boost sensor A circuit high
P0243|M|Turbocharger wastegate solenoid A
P0244|M|Turbocharger wastegate solenoid A range/performance
P0245|M|Turbocharger wastegate solenoid A low
P0246|M|Turbocharger wastegate solenoid A high
P0299|M|Turbocharger/supercharger underboost|The engine isn't making the boost it asked for. Look for a split intercooler hose or boost pipe first — it's the most common cause and often audible. Also check the wastegate actuator and for a clogged air filter.
"""

    private const val MISFIRE_AND_IGNITION = """
P0300|S|Random or multiple cylinder misfire detected|Misfiring across several cylinders points at something shared: ignition timing, fuel pressure, a vacuum leak, or a failing crank sensor. Get it fixed rather than driven — unburnt fuel reaching the catalytic converter will destroy it, turning a cheap job into an expensive one.
P0313|M|Misfire detected with low fuel level|Often exactly what it says. Fill the tank and see if it clears.
P0314|S|Single cylinder misfire, cylinder not identified
P0315|M|Crankshaft position system variation not learned|Needs a relearn procedure after crank sensor or engine work.
P0316|S|Misfire detected on startup
P0320|S|Ignition/distributor engine speed input circuit|Crank position sensor circuit. Can cause intermittent stalling and no-starts.
P0321|S|Ignition/distributor engine speed input range/performance
P0322|S|Ignition/distributor engine speed input no signal
P0325|M|Knock sensor 1 circuit, bank 1|The ECU can no longer hear detonation, so it retards timing defensively. You'll notice reduced power and worse economy.
P0326|M|Knock sensor 1 range/performance, bank 1
P0327|M|Knock sensor 1 circuit low, bank 1
P0328|M|Knock sensor 1 circuit high, bank 1
P0330|M|Knock sensor 2 circuit, bank 2
P0332|M|Knock sensor 2 circuit low, bank 2
P0333|M|Knock sensor 2 circuit high, bank 2
P0335|S|Crankshaft position sensor A circuit|The engine's primary timing reference. A failing one causes intermittent stalling that gets worse when hot, and eventually a no-start.
P0336|S|Crankshaft position sensor A range/performance|Can also be a damaged reluctor ring rather than the sensor.
P0337|S|Crankshaft position sensor A circuit low
P0338|S|Crankshaft position sensor A circuit high
P0339|S|Crankshaft position sensor A circuit intermittent|Intermittent faults here are notorious for causing stalls that leave no other trace. Check the connector and the sensor's air gap.
P0340|S|Camshaft position sensor A circuit, bank 1
P0341|S|Camshaft position sensor A range/performance, bank 1
P0342|S|Camshaft position sensor A circuit low, bank 1
P0343|S|Camshaft position sensor A circuit high, bank 1
P0344|S|Camshaft position sensor A circuit intermittent, bank 1
P0345|S|Camshaft position sensor A circuit, bank 2
P0350|S|Ignition coil primary/secondary circuit
P0365|M|Camshaft position sensor B circuit, bank 1
P0369|M|Camshaft position sensor B circuit intermittent, bank 1
P0390|M|Camshaft position sensor B circuit, bank 2
"""

    private const val EMISSION_CONTROLS = """
P0400|M|Exhaust gas recirculation flow malfunction|Carbon build-up in the EGR valve or its passages is the usual culprit, especially on diesels and on cars used mostly for short journeys.
P0401|M|Exhaust gas recirculation flow insufficient|Blocked EGR passages or a stuck-shut valve. Cleaning often works and costs far less than replacement.
P0402|M|Exhaust gas recirculation flow excessive|Usually an EGR valve stuck open, which makes the engine idle roughly or stall.
P0403|M|Exhaust gas recirculation circuit malfunction
P0404|M|Exhaust gas recirculation circuit range/performance
P0405|M|Exhaust gas recirculation sensor A circuit low
P0406|M|Exhaust gas recirculation sensor A circuit high
P0409|M|Exhaust gas recirculation sensor A circuit
P0410|M|Secondary air injection system malfunction
P0411|M|Secondary air injection incorrect flow detected|Common on cold-climate cars. The air pump or its check valve seizes from moisture.
P0412|M|Secondary air injection switching valve A circuit
P0413|M|Secondary air injection switching valve A open
P0414|M|Secondary air injection switching valve A shorted
P0418|M|Secondary air injection pump relay A circuit
P0420|M|Catalyst system efficiency below threshold, bank 1|The catalytic converter isn't cleaning the exhaust as well as it should. Before condemning it — cats are expensive — rule out a lazy rear O2 sensor and any exhaust leak upstream, both of which fake this code. Also check whether a misfire or rich-running fault caused the damage, or the new cat will fail the same way.
P0421|M|Warm-up catalyst efficiency below threshold, bank 1
P0422|M|Main catalyst efficiency below threshold, bank 1
P0430|M|Catalyst system efficiency below threshold, bank 2|Same as P0420 but the other bank. Check the rear O2 sensor and for exhaust leaks before replacing the converter.
P0431|M|Warm-up catalyst efficiency below threshold, bank 2
P0432|M|Main catalyst efficiency below threshold, bank 2
P0440|N|Evaporative emission control system malfunction|The fuel vapour system. Start with the fuel cap — a worn seal or one that wasn't clicked shut is the single most common cause.
P0441|N|Evaporative emission control incorrect purge flow|Usually the purge valve stuck open or closed.
P0442|N|Evaporative emission system leak detected (small leak)|A small vapour leak. Check the fuel cap seal first, then the EVAP hoses for perishing. It affects nothing you can feel while driving, but it will fail an emissions test.
P0443|N|Evaporative emission purge control valve circuit
P0446|N|Evaporative emission vent control circuit|Often the vent valve, which sits near the rear axle and corrodes.
P0447|N|Evaporative emission vent control circuit open
P0448|N|Evaporative emission vent control circuit shorted
P0449|N|Evaporative emission vent valve/solenoid circuit
P0451|N|Evaporative emission pressure sensor range/performance
P0452|N|Evaporative emission pressure sensor low input
P0453|N|Evaporative emission pressure sensor high input
P0455|N|Evaporative emission system leak detected (large leak)|A large vapour leak — usually a missing, loose or failed fuel cap, or a disconnected EVAP hose. Check the cap before spending anything.
P0456|N|Evaporative emission system leak detected (very small leak)|A pinhole-sized leak. Fuel cap seal, or a hairline crack in a vapour hose. Harmless to drive with, but an MOT/emissions failure.
P0457|N|Evaporative emission leak detected (fuel cap loose or off)|Exactly what it says. Refit the cap until it clicks, then drive a few cycles for the light to clear.
P0460|N|Fuel level sensor circuit
P0461|N|Fuel level sensor circuit range/performance
P0462|N|Fuel level sensor circuit low input
P0463|N|Fuel level sensor circuit high input
P0464|N|Fuel level sensor circuit intermittent
P0470|M|Exhaust pressure sensor malfunction
P0471|M|Exhaust pressure sensor range/performance
P0475|M|Exhaust pressure control valve malfunction
P0480|N|Cooling fan 1 control circuit
P0481|N|Cooling fan 2 control circuit
P0485|N|Cooling fan power/ground circuit
P0489|M|Exhaust gas recirculation control circuit low
P0490|M|Exhaust gas recirculation control circuit high
"""

    private const val SPEED_IDLE_AND_AUX = """
P0500|M|Vehicle speed sensor malfunction|Affects the speedometer, cruise control and gear shifts. Often the ABS wheel sensor on modern cars, since speed is derived from it.
P0501|M|Vehicle speed sensor range/performance
P0502|M|Vehicle speed sensor circuit low input
P0503|M|Vehicle speed sensor intermittent/erratic
P0505|M|Idle air control system malfunction|A dirty throttle body or idle control valve. Cleaning usually fixes a hunting or stalling idle.
P0506|M|Idle air control system RPM lower than expected
P0507|M|Idle air control system RPM higher than expected|Almost always a vacuum leak letting unmetered air in, or a throttle plate that needs cleaning.
P0510|N|Closed throttle position switch malfunction
P0520|S|Engine oil pressure sensor/switch circuit|If the oil pressure warning light is also on, stop the engine. If it's only the sensor circuit at fault the engine is fine, but you can't tell the difference from the driver's seat — check the level and the actual pressure before driving on.
P0521|S|Engine oil pressure sensor range/performance
P0522|S|Engine oil pressure sensor low voltage
P0523|S|Engine oil pressure sensor high voltage
P0524|C|Engine oil pressure too low|Stop the engine now and check the oil level. Running an engine without oil pressure destroys the bearings within minutes.
P0530|N|A/C refrigerant pressure sensor circuit
P0532|N|A/C refrigerant pressure sensor circuit low
P0533|N|A/C refrigerant pressure sensor circuit high
P0560|M|System voltage malfunction
P0561|M|System voltage unstable|Charging system: alternator, its regulator, or a tired battery. Check the Live Data screen for control module voltage — it should sit around 13.5–14.5 V with the engine running.
P0562|S|System voltage low|The battery isn't being charged. Expect a breakdown. Check the alternator and drive belt.
P0563|M|System voltage high|Overcharging, usually a failed voltage regulator. It will cook the battery and can damage electronics.
P0571|N|Brake switch A circuit malfunction|Often stops cruise control working and can prevent the car shifting out of park.
P0572|N|Brake switch A circuit low
P0573|N|Brake switch A circuit high
P0575|N|Cruise control input circuit
P0577|N|Cruise control input circuit high
"""

    private const val MODULE_AND_OUTPUT = """
P0600|S|Serial communication link malfunction
P0601|S|Internal control module memory checksum error|The ECU's own memory has failed its self-check. Sometimes cured by a reflash; often means a replacement module.
P0602|S|Control module programming error
P0603|S|Internal control module keep-alive memory error|Frequently follows a flat or disconnected battery. Clear it and see if it returns.
P0604|S|Internal control module RAM error
P0605|S|Internal control module ROM error
P0606|S|ECM/PCM processor fault
P0607|S|Control module performance
P0615|M|Starter relay circuit
P0616|M|Starter relay circuit low
P0617|M|Starter relay circuit high
P0620|M|Generator control circuit
P0621|M|Generator lamp/L terminal circuit
P0622|M|Generator field/F terminal circuit
P0627|S|Fuel pump control circuit open
P0628|S|Fuel pump control circuit low
P0629|S|Fuel pump control circuit high
P0645|N|A/C clutch relay control circuit
P0650|N|Malfunction indicator lamp control circuit|The warning light circuit itself. The light may not come on when it should, which hides other faults.
P0685|S|ECM power relay control circuit open
P0686|S|ECM power relay control circuit low
P0687|S|ECM power relay control circuit high
P0691|N|Cooling fan 1 control circuit low
P0692|N|Cooling fan 1 control circuit high
"""

    private const val TRANSMISSION = """
P0700|S|Transmission control system malfunction|A pointer code: the transmission module has its own fault stored. Read the transmission module's codes to get the real detail.
P0701|S|Transmission control system range/performance
P0702|S|Transmission control system electrical
P0703|M|Torque converter/brake switch B circuit
P0705|S|Transmission range sensor circuit|The gear selector position sensor. Symptoms include no-start in park and wrong gear indication.
P0706|S|Transmission range sensor range/performance
P0707|S|Transmission range sensor circuit low
P0708|S|Transmission range sensor circuit high
P0710|M|Transmission fluid temperature sensor circuit
P0711|M|Transmission fluid temperature sensor range/performance
P0712|M|Transmission fluid temperature sensor circuit low
P0713|M|Transmission fluid temperature sensor circuit high
P0715|S|Input/turbine speed sensor circuit|Causes harsh or missed shifts and can put the box into limp mode.
P0716|S|Input/turbine speed sensor range/performance
P0717|S|Input/turbine speed sensor no signal
P0720|S|Output speed sensor circuit
P0721|S|Output speed sensor range/performance
P0722|S|Output speed sensor no signal
P0730|S|Incorrect gear ratio|The box isn't achieving the ratio it commanded. Check fluid level and condition first — low or burnt fluid causes this far more often than mechanical failure.
P0731|S|Gear 1 incorrect ratio
P0732|S|Gear 2 incorrect ratio
P0733|S|Gear 3 incorrect ratio
P0734|S|Gear 4 incorrect ratio
P0735|S|Gear 5 incorrect ratio
P0736|S|Reverse incorrect ratio
P0740|S|Torque converter clutch circuit malfunction|A shuddering feeling around 40–50 mph is the classic symptom. Often the solenoid rather than the converter.
P0741|S|Torque converter clutch performance or stuck off
P0742|S|Torque converter clutch stuck on|Can stall the engine when coming to a stop.
P0743|S|Torque converter clutch circuit electrical
P0745|S|Pressure control solenoid A malfunction
P0746|S|Pressure control solenoid A performance or stuck off
P0748|S|Pressure control solenoid A electrical
P0750|S|Shift solenoid A malfunction
P0751|S|Shift solenoid A performance or stuck off
P0753|S|Shift solenoid A electrical
P0755|S|Shift solenoid B malfunction
P0756|S|Shift solenoid B performance or stuck off
P0758|S|Shift solenoid B electrical
P0760|S|Shift solenoid C malfunction
P0763|S|Shift solenoid C electrical
P0765|S|Shift solenoid D malfunction
P0770|S|Shift solenoid E malfunction
P0780|S|Shift malfunction
P0781|S|1-2 shift malfunction
P0782|S|2-3 shift malfunction
P0783|S|3-4 shift malfunction
P0795|S|Pressure control solenoid C malfunction
P0850|N|Park/neutral switch input circuit
P0868|S|Transmission fluid pressure low
"""

    private const val P2_RANGE = """
P2000|M|NOx trap efficiency below threshold, bank 1
P2002|M|Diesel particulate filter efficiency below threshold, bank 1|The DPF is blocked or worn. Regular motorway runs allow it to regenerate; repeated short journeys are what clog it.
P2003|M|Diesel particulate filter efficiency below threshold, bank 2
P2004|M|Intake manifold runner control stuck open, bank 1|Swirl flaps. Carbon build-up jams them, particularly on diesels.
P2005|M|Intake manifold runner control stuck open, bank 2
P2006|M|Intake manifold runner control stuck closed, bank 1
P2007|M|Intake manifold runner control stuck closed, bank 2
P2008|M|Intake manifold runner control circuit open, bank 1
P2009|M|Intake manifold runner control circuit low, bank 1
P2010|M|Intake manifold runner control circuit high, bank 1
P2014|M|Intake manifold runner position sensor circuit, bank 1
P2015|M|Intake manifold runner position sensor range/performance, bank 1|A very common failure on VAG group diesels — the plastic swirl flap linkage breaks.
P2031|N|Exhaust gas temperature sensor circuit, bank 1 sensor 2
P2032|N|Exhaust gas temperature sensor circuit low, bank 1 sensor 2
P2033|N|Exhaust gas temperature sensor circuit high, bank 1 sensor 2
P2036|N|Exhaust gas temperature sensor circuit, bank 2 sensor 2
P2080|N|Exhaust gas temperature sensor range/performance, bank 1 sensor 1
P2096|M|Post-catalyst fuel trim system too lean, bank 1|Often an exhaust leak between the catalytic converter and the rear O2 sensor rather than a fuelling fault.
P2097|M|Post-catalyst fuel trim system too rich, bank 1
P2098|M|Post-catalyst fuel trim system too lean, bank 2
P2099|M|Post-catalyst fuel trim system too rich, bank 2
P2100|S|Throttle actuator control motor circuit open|Drive-by-wire throttle. Expect limp mode.
P2101|S|Throttle actuator control motor range/performance|Try cleaning the throttle body and performing a throttle relearn before replacing it.
P2102|S|Throttle actuator control motor circuit low
P2103|S|Throttle actuator control motor circuit high
P2104|S|Throttle actuator control system forced idle|The car has deliberately limited you to idle. It will need diagnosing before it drives normally again.
P2105|S|Throttle actuator control system forced engine shutdown
P2106|S|Throttle actuator control system limited power|Limp mode. The car is protecting itself.
P2107|S|Throttle actuator control module processor fault
P2108|S|Throttle actuator control module performance
P2109|M|Throttle/pedal position sensor A minimum stop performance
P2110|S|Throttle actuator control system forced limited RPM
P2111|S|Throttle actuator control system stuck open
P2112|S|Throttle actuator control system stuck closed
P2119|M|Throttle actuator control throttle body range/performance|Carbon build-up on the throttle plate. Cleaning is usually the fix.
P2122|M|Pedal position sensor D circuit low input
P2123|M|Pedal position sensor D circuit high input
P2127|M|Pedal position sensor E circuit low input
P2128|M|Pedal position sensor E circuit high input
P2135|S|Throttle position sensor A/B voltage correlation|The two redundant throttle sensors disagree, so the ECU can't trust either. Limp mode is normal until it's fixed.
P2138|S|Pedal position sensor D/E voltage correlation|As above but at the accelerator pedal. Often the pedal assembly itself.
P2146|S|Fuel injector group A supply voltage circuit open
P2147|S|Fuel injector group A supply voltage circuit low
P2149|S|Fuel injector group B supply voltage circuit open
P2177|M|System too lean off idle, bank 1|Lean only away from idle points more at fuel delivery — a weak pump or restricted filter — than at a vacuum leak.
P2178|M|System too rich off idle, bank 1
P2179|M|System too lean off idle, bank 2
P2180|M|System too rich off idle, bank 2
P2187|M|System too lean at idle, bank 1|Lean only at idle strongly suggests a vacuum leak, since a fixed-size leak matters most when airflow is lowest.
P2188|M|System too rich at idle, bank 1
P2189|M|System too lean at idle, bank 2
P2190|M|System too rich at idle, bank 2
P2195|M|O2 sensor signal stuck lean, bank 1 sensor 1
P2196|M|O2 sensor signal stuck rich, bank 1 sensor 1
P2197|M|O2 sensor signal stuck lean, bank 2 sensor 1
P2198|M|O2 sensor signal stuck rich, bank 2 sensor 1
P2200|M|NOx sensor circuit, bank 1
P2201|M|NOx sensor range/performance, bank 1
P2237|M|O2 sensor positive current control circuit open, bank 1 sensor 1
P2270|M|O2 sensor signal stuck lean, bank 1 sensor 2
P2271|M|O2 sensor signal stuck rich, bank 1 sensor 2
P2279|M|Intake air system leak|A genuine unmetered air leak. Look for split hoses and loose clamps between the airbox and the throttle.
P2299|S|Brake pedal position and accelerator position incompatible
P2404|N|Evaporative emission leak detection pump sense circuit range/performance
P2413|M|Exhaust gas recirculation system performance
P2414|M|O2 sensor exhaust sample error, bank 1 sensor 1
P2418|N|Evaporative emission switching valve circuit open
P2422|N|Evaporative emission vent valve stuck closed
P2431|M|Secondary air injection sensor circuit range/performance, bank 1
P2440|M|Secondary air injection switching valve stuck open, bank 1
P2445|M|Secondary air injection pump stuck off, bank 1
P2452|M|Diesel particulate filter pressure sensor circuit
P2453|M|Diesel particulate filter pressure sensor range/performance|A blocked or disconnected DPF pressure pipe is a common cause, and cheaper than the sensor.
P2454|M|Diesel particulate filter pressure sensor circuit low
P2455|M|Diesel particulate filter pressure sensor circuit high
P2459|M|Diesel particulate filter regeneration frequency|The DPF is regenerating too often, usually because of short journeys. A sustained motorway run may clear it.
P2463|M|Diesel particulate filter soot accumulation|The filter is loading up faster than it can burn off. Left too long it needs forced regeneration or replacement.
P2564|M|Turbocharger boost control position sensor circuit
P2565|M|Turbocharger boost control position sensor range/performance
P2635|S|Fuel pump A low flow/performance
"""

    private const val NETWORK_AND_CHASSIS = """
U0001|M|High speed CAN communication bus|A bus-level fault. Check for a module that has lost power, and for damaged wiring, before suspecting any one control unit.
U0016|M|Medium speed CAN communication bus A
U0073|S|Control module communication bus A off|The bus has shut down. Usually one failed module dragging the whole network down, or a wiring short.
U0100|S|Lost communication with ECM/PCM A|The engine control module has stopped talking. Check its power, ground and connectors — a corroded plug is more common than a dead module.
U0101|S|Lost communication with transmission control module
U0102|S|Lost communication with transfer case control module
U0103|S|Lost communication with gear shift module
U0104|S|Lost communication with cruise control module
U0105|S|Lost communication with fuel injector control module
U0106|S|Lost communication with glow plug control module
U0107|S|Lost communication with throttle actuator control module
U0109|S|Lost communication with fuel pump control module
U0110|S|Lost communication with drive motor control module
U0111|S|Lost communication with battery energy control module A
U0114|S|Lost communication with four-wheel-drive clutch control module
U0121|S|Lost communication with ABS control module|Expect the ABS and stability control lights on, and those systems disabled. Ordinary braking still works.
U0122|S|Lost communication with vehicle dynamics control module
U0123|S|Lost communication with yaw rate sensor module
U0124|S|Lost communication with lateral acceleration sensor module
U0125|S|Lost communication with multi-axis acceleration sensor module
U0126|S|Lost communication with steering angle sensor module
U0128|S|Lost communication with park brake control module
U0131|S|Lost communication with power steering control module|On an electric power steering car this can mean losing assistance. Get it checked before driving far.
U0140|M|Lost communication with body control module
U0141|M|Lost communication with body control module A
U0151|S|Lost communication with restraints control module|The airbag system may be disabled. Have it looked at promptly.
U0155|M|Lost communication with instrument panel cluster
U0164|N|Lost communication with HVAC control module
U0167|N|Lost communication with vehicle immobiliser module
U0184|N|Lost communication with radio
U0199|N|Lost communication with door control module A
U0208|M|Lost communication with seat control module
U0300|M|Internal control module software incompatibility|Usually follows a module replacement or a botched software update. Needs the correct software loading.
U0401|M|Invalid data received from ECM/PCM A|A module is transmitting, but with data the receiver rejects. Often accompanies another fault upstream.
U0402|M|Invalid data received from transmission control module
U0415|M|Invalid data received from ABS control module
U0416|M|Invalid data received from vehicle dynamics control module
U0418|M|Invalid data received from brake system control module
U0422|M|Invalid data received from body control module
C0035|S|Left front wheel speed sensor circuit|Disables ABS and stability control. Ordinary braking is unaffected.
C0040|S|Right front wheel speed sensor circuit
C0045|S|Left rear wheel speed sensor circuit
C0050|S|Right rear wheel speed sensor circuit
C0051|S|Steering wheel position sensor|May need a calibration after an alignment or steering repair.
C0061|S|ABS control valve or solenoid circuit
C0121|S|ABS valve relay circuit
C0131|S|ABS control module internal fault
C0161|S|ABS/traction control system brake switch circuit
C0265|S|ABS motor relay circuit
B0001|S|Driver airbag deployment control|Airbag faults disable the system. It won't fire in a crash while this code is stored.
B0012|S|Passenger airbag deployment control
B0022|S|Driver side airbag deployment control
B0028|S|Passenger side airbag deployment control
B0081|S|Driver seatbelt pretensioner deployment control
B0092|S|Passenger seatbelt pretensioner deployment control
B1000|N|Electronic control unit fault
"""
}
