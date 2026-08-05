package com.rhys.obd2.obd

/**
 * How sure the entry is, stated rather than implied.
 *
 * A note about a car's known weaknesses is only worth having if the reader can tell a
 * documented pattern from a rumour. Someone deciding whether to spend eight hundred pounds
 * on a head gasket deserves to know which of the two they are reading.
 */
enum class IssueConfidence(val label: String, val explanation: String) {
    WELL_DOCUMENTED(
        "Well documented",
        "A pattern fault reported across many cars of this model, recognised by " +
            "independent specialists and in most cases by the manufacturer.",
    ),
    COMMON(
        "Commonly reported",
        "Reported often enough by owners and garages to be worth checking, though not " +
            "every car is affected.",
    ),
    WORTH_CHECKING(
        "Worth checking",
        "A known weak point on this model. Less universal than the above, but cheap to " +
            "rule out before spending money elsewhere.",
    ),
}

/**
 * One thing that is known to go wrong on a particular model.
 *
 * [codes] lists the OBD-II codes the fault can set, which is often none. Most of what goes
 * wrong with a car is invisible to this app: corrosion, suspension, gearboxes and braking
 * systems are not emissions-related and never appear on the codes screen. Those entries are
 * still worth carrying — they are what an owner wants to know about their car — but the
 * distinction is kept explicit so nothing implies the app can detect them.
 */
data class KnownIssue(
    val title: String,
    val summary: String,
    val detail: String,
    val confidence: IssueConfidence,
    /** OBD-II codes this fault can set. Empty when the fault is invisible to OBD-II. */
    val codes: List<String> = emptyList(),
    /** Roughly when it tends to appear, in miles, where there is a pattern. */
    val typicalMileage: String? = null,
) {
    val detectableOverObd: Boolean get() = codes.isNotEmpty()
}

/** A model or engine variant with a known service history. */
data class VehicleModel(
    val id: String,
    val make: String,
    val model: String,
    val years: String,
    val engine: String? = null,
    val issues: List<KnownIssue>,
) {
    val displayName: String get() = buildString {
        append("$make $model")
        engine?.let { append(" $it") }
        append(" ($years)")
    }
}

/**
 * Model-specific known faults.
 *
 * Deliberately not an attempt to cover every car built since OBD-II arrived. That would be
 * tens of thousands of model, year and engine combinations, and what is actually known
 * about them is wildly uneven — a few hundred well-documented pattern faults, a long tail
 * of models where anything written would be guesswork. Guesswork and knowledge render
 * identically on a screen, which is what makes partial coverage worse than narrow coverage:
 * the reader cannot tell which one they are looking at.
 *
 * So this is a curated list, every entry carries its confidence, and a model that is not
 * here says so plainly rather than producing something plausible. The same principle the
 * code database already applies to manufacturer-specific codes, which are flagged as
 * unknowable rather than given an invented description.
 *
 * The UK car parc is concentrated enough that a few dozen model families cover most of what
 * is actually on the roads, which is why a list this size is worth having at all.
 */
object KnownIssues {

    val models: List<VehicleModel> = listOf(

        // -------------------------------------------------------------------------------
        // Land Rover
        // -------------------------------------------------------------------------------
        VehicleModel(
            id = "lr-discovery2-td5",
            make = "Land Rover", model = "Discovery 2", engine = "Td5", years = "1998–2004",
            issues = listOf(
                KnownIssue(
                    title = "Oil in the ECU via the injector harness",
                    summary = "Engine oil wicks up the injector wiring into the engine ECU.",
                    detail = "The injector harness passes through the rocker cover, and oil " +
                        "travels along the wire cores by capillary action until it reaches " +
                        "the ECU connector and fills it. Symptoms are erratic running, " +
                        "misfires and starting problems that come and go. Pull the ECU " +
                        "connector and look for oil — it is unmistakable. Fixing the ECU " +
                        "without replacing the harness means it simply fills again.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0201", "P0202", "P0203", "P0204", "P0205", "P0300"),
                ),
                KnownIssue(
                    title = "Three amigos — ABS, traction control and hill descent",
                    summary = "All three warning lights on together, usually the shuttle valve switch.",
                    detail = "Almost a rite of passage on this model. Most often the shuttle " +
                        "valve switch in the ABS modulator, sometimes a wheel speed sensor or " +
                        "its ring. Not emissions-related, so this app cannot read it — it " +
                        "needs a tool that talks to the ABS module.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                ),
                KnownIssue(
                    title = "Head gasket",
                    summary = "Td5 head gasket failure, often after overheating.",
                    detail = "Watch coolant temperature. A Td5 that creeps above its normal " +
                        "running temperature under load, or pressurises its expansion tank, " +
                        "wants investigating before it becomes a head job.",
                    confidence = IssueConfidence.COMMON,
                    typicalMileage = "120,000+",
                ),
                KnownIssue(
                    title = "Rear self-levelling suspension",
                    summary = "Air bags perish and the compressor burns out trying to keep up.",
                    detail = "The car sits low at the back or refuses to level. Many owners " +
                        "convert to coil springs rather than repair. Not visible to OBD-II.",
                    confidence = IssueConfidence.COMMON,
                ),
                KnownIssue(
                    title = "Chassis and body corrosion",
                    summary = "Rear chassis rails, boot floor and sills.",
                    detail = "The single biggest reason these are scrapped. Worth inspecting " +
                        "properly regardless of how the engine behaves.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                ),
            ),
        ),

        VehicleModel(
            id = "lr-defender-td5",
            make = "Land Rover", model = "Defender", engine = "Td5", years = "1998–2007",
            issues = listOf(
                KnownIssue(
                    title = "Oil in the ECU via the injector harness",
                    summary = "Same fault as the Discovery 2 Td5 — oil travels up the injector wiring.",
                    detail = "Erratic running and hot-start problems. Check the ECU connector " +
                        "for oil before chasing anything else.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0201", "P0202", "P0203", "P0204", "P0205", "P0300"),
                ),
                KnownIssue(
                    title = "Bulkhead corrosion",
                    summary = "Footwells and the bulkhead behind the dash.",
                    detail = "A structural repair and an MOT failure point. Not detectable " +
                        "electronically.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                ),
                KnownIssue(
                    title = "Chassis rust, particularly the rear crossmember",
                    summary = "Check the rear crossmember and outriggers carefully.",
                    detail = "Galvanised replacements are common. Inspect rather than assume.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                ),
            ),
        ),

        VehicleModel(
            id = "lr-freelander1",
            make = "Land Rover", model = "Freelander 1", years = "1997–2006",
            issues = listOf(
                KnownIssue(
                    title = "K-series head gasket (1.8 petrol)",
                    summary = "The defining fault of the 1.8 K-series engine.",
                    detail = "Overheating, mayonnaise under the oil cap, pressurised coolant. " +
                        "The original composite gasket and plastic dowels were the weakness; " +
                        "uprated multi-layer steel gaskets and steel dowels are the fix. " +
                        "Watch coolant temperature — the gauge in this app now reads blue " +
                        "when cold and reddens above the working range for exactly this kind " +
                        "of reason.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0217", "P1181"),
                ),
                KnownIssue(
                    title = "Viscous coupling unit and IRD failure",
                    summary = "Four-wheel-drive transmission wear, often from mismatched tyres.",
                    detail = "A seizing VCU loads the intermediate reduction drive and rear " +
                        "differential until something breaks, usually expensively. Uneven tyre " +
                        "wear across axles is the usual cause. Not visible to OBD-II.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                ),
                KnownIssue(
                    title = "Td4 (BMW M47) turbo and EGR",
                    summary = "Sticking EGR and turbo actuator on the 2.0 diesel.",
                    detail = "Loss of power and black smoke. The Td4 is a BMW engine and uses " +
                        "BMW-pattern parts. Note that diesel EOBD only became mandatory around " +
                        "2003–2004, so a Td4 of this age may report little or nothing.",
                    confidence = IssueConfidence.COMMON,
                    codes = listOf("P0401", "P0402", "P0299"),
                ),
            ),
        ),

        // -------------------------------------------------------------------------------
        // Volkswagen
        // -------------------------------------------------------------------------------
        VehicleModel(
            id = "vw-14tsi-ea111",
            make = "Volkswagen", model = "Golf, Touran, Polo, Passat", engine = "1.4 TSI (EA111)",
            years = "2006–2013",
            issues = listOf(
                KnownIssue(
                    title = "Timing chain tensioner failure",
                    summary = "The tensioner backs off and the chain jumps teeth.",
                    detail = "Probably the best-known fault on this engine. A rattle on " +
                        "start-up is the warning; if the chain jumps, valves meet pistons and " +
                        "the engine is scrap. The early tensioner was superseded more than " +
                        "once — check which part is fitted. Do not ignore a start-up rattle.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0016", "P0011", "P0341"),
                    typicalMileage = "60,000+",
                ),
                KnownIssue(
                    title = "Pistons and rings on the twincharged 1.4",
                    summary = "High oil consumption, sometimes piston failure.",
                    detail = "The supercharged-and-turbocharged version is the more fragile. " +
                        "Heavy oil consumption is the early sign.",
                    confidence = IssueConfidence.COMMON,
                    codes = listOf("P0301", "P0302", "P0303", "P0304"),
                ),
                KnownIssue(
                    title = "Water pump and thermostat housing",
                    summary = "Plastic housing leaks coolant.",
                    detail = "Often replaced together with the timing work since the labour " +
                        "overlaps.",
                    confidence = IssueConfidence.COMMON,
                    codes = listOf("P0128"),
                ),
            ),
        ),

        VehicleModel(
            id = "vw-20tdi-pd",
            make = "Volkswagen", model = "Golf, Passat, Touran", engine = "2.0 TDI PD",
            years = "2003–2010",
            issues = listOf(
                KnownIssue(
                    title = "Camshaft and follower wear",
                    summary = "The pump-duse injectors load the camshaft heavily.",
                    detail = "Worn lobes and collapsed followers cause rough running and poor " +
                        "starting. A known weakness of the pumpe-duse design specifically.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0341", "P0016"),
                    typicalMileage = "80,000+",
                ),
                KnownIssue(
                    title = "EGR valve and cooler",
                    summary = "Sooting up, causing power loss and the light coming on.",
                    detail = "Very common on any diesel of this era used for short journeys.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0401", "P0402", "P0409"),
                ),
                KnownIssue(
                    title = "Diesel particulate filter blocking",
                    summary = "Short journeys prevent the filter regenerating.",
                    detail = "A weekly run at sustained speed is the cheapest prevention. " +
                        "Removing it is an MOT failure and illegal on the road.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P2002", "P242F", "P1471"),
                ),
            ),
        ),

        VehicleModel(
            id = "vw-dsg",
            make = "Volkswagen", model = "Golf, Touran, Passat and related", engine = "DSG gearbox",
            years = "2003–2016",
            issues = listOf(
                KnownIssue(
                    title = "Mechatronic unit failure",
                    summary = "Jerky shifts, refusal to select gear, warning lights.",
                    detail = "The hydraulic and electronic control unit inside the gearbox. " +
                        "The dry-clutch seven-speed had the worse reputation. Fluid and filter " +
                        "changes at the specified interval matter more than owners expect. " +
                        "Gearbox faults are not emissions-related, so this app will not read " +
                        "them.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                ),
            ),
        ),

        VehicleModel(
            id = "vw-plenum",
            make = "Volkswagen", model = "Golf, Touran", years = "2003–2015",
            issues = listOf(
                KnownIssue(
                    title = "Blocked plenum drains flooding the footwells",
                    summary = "Leaves block the scuttle drains and water enters the cabin.",
                    detail = "Soaks the carpet and, on the Touran especially, can reach control " +
                        "modules under the seats. Causes electrical faults that look baffling " +
                        "until the damp is found. Clear the drains each autumn.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                ),
            ),
        ),

        // -------------------------------------------------------------------------------
        // Fiat
        // -------------------------------------------------------------------------------
        VehicleModel(
            id = "fiat-500-panda-twinair",
            make = "Fiat", model = "500, Panda", engine = "0.9 TwinAir", years = "2010–2020",
            issues = listOf(
                KnownIssue(
                    title = "High oil consumption",
                    summary = "Uses oil at a rate owners find alarming.",
                    detail = "Check the level often. The published economy figures were never " +
                        "achievable in normal driving either.",
                    confidence = IssueConfidence.COMMON,
                ),
                KnownIssue(
                    title = "Uniair / Multiair actuator unit",
                    summary = "The electro-hydraulic valve unit fails.",
                    detail = "Rough running, loss of power, light on. An expensive part " +
                        "relative to the value of the car.",
                    confidence = IssueConfidence.COMMON,
                    codes = listOf("P0300", "P1078", "P0016"),
                ),
            ),
        ),

        VehicleModel(
            id = "fiat-500-panda-general",
            make = "Fiat", model = "500, Panda, 500L", years = "2007–2023",
            issues = listOf(
                KnownIssue(
                    title = "Electric power steering column",
                    summary = "Steering assistance cuts out, warning light on.",
                    detail = "The motor and control unit are in the column. A very common " +
                        "failure and subject to recall action on some cars — worth checking " +
                        "the vehicle's recall status by registration.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                ),
                KnownIssue(
                    title = "Gear selector cables and bushes",
                    summary = "Vague or jammed gear selection.",
                    detail = "Cheap to fix, often misdiagnosed as a gearbox problem.",
                    confidence = IssueConfidence.COMMON,
                ),
                KnownIssue(
                    title = "1.3 Multijet EGR and DPF",
                    summary = "The small diesel suffers on short journeys.",
                    detail = "Same story as every small modern diesel used for town driving.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0401", "P2002", "P0299"),
                ),
            ),
        ),

        // -------------------------------------------------------------------------------
        // MG / Rover
        // -------------------------------------------------------------------------------
        VehicleModel(
            id = "mg-rover-kseries",
            make = "MG, Rover", model = "ZR, ZS, 25, 45, MGF, TF", engine = "K-series",
            years = "1995–2005",
            issues = listOf(
                KnownIssue(
                    title = "Head gasket failure",
                    summary = "The fault the K-series is known for above all others.",
                    detail = "Overheating, coolant loss with no visible leak, mayonnaise under " +
                        "the filler cap. The original gasket and plastic dowels were the " +
                        "weakness. A car repaired with an uprated multi-layer steel gasket and " +
                        "steel dowels is a different proposition from one that has not been " +
                        "done. Coolant temperature is the thing to watch.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0217", "P0128"),
                ),
            ),
        ),

        VehicleModel(
            id = "mg-modern",
            make = "MG", model = "MG3, ZS, HS, MG5", years = "2013–present",
            issues = listOf(
                KnownIssue(
                    title = "Limited independent fault history",
                    summary = "These are recent enough that pattern faults are still emerging.",
                    detail = "The modern SAIC-built MGs have not been on the road long enough " +
                        "for a settled picture of what fails, and this app would rather say so " +
                        "than invent one. Generic fault codes are still decoded normally, and " +
                        "the history recorded for your own car is the more useful signal here.",
                    confidence = IssueConfidence.WORTH_CHECKING,
                ),
            ),
        ),

        // -------------------------------------------------------------------------------
        // The rest of what is actually on UK roads
        // -------------------------------------------------------------------------------
        VehicleModel(
            id = "psa-16hdi",
            make = "Peugeot, Citroën, Ford, Mini, Volvo", model = "208, 308, C3, C4, Focus, Cooper D",
            engine = "1.6 HDi / TDCi (DV6)", years = "2004–2016",
            issues = listOf(
                KnownIssue(
                    title = "Turbo failure from a blocked oil feed",
                    summary = "The oil feed pipe to the turbo blocks with carbon and the turbo seizes.",
                    detail = "One of the most common expensive failures on any small European " +
                        "diesel. Frequent oil changes with the correct specification oil, and " +
                        "replacing the feed pipe when the turbo is changed, are the prevention. " +
                        "Fitting a new turbo without clearing the feed destroys the new one.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0299", "P0234", "P0045"),
                    typicalMileage = "80,000+",
                ),
            ),
        ),

        VehicleModel(
            id = "bmw-n47",
            make = "BMW", model = "1 Series, 3 Series, X1, X3", engine = "N47 diesel",
            years = "2007–2015",
            issues = listOf(
                KnownIssue(
                    title = "Timing chain failure",
                    summary = "The chain is at the back of the engine and its guides wear out.",
                    detail = "A rattle from the rear of the engine, worst on a cold start. " +
                        "Because the chain sits at the gearbox end, replacement means removing " +
                        "the engine or gearbox, which is why it costs what it does. Do not " +
                        "ignore the rattle — a snapped chain destroys the engine.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0016", "P0017"),
                    typicalMileage = "90,000+",
                ),
            ),
        ),

        VehicleModel(
            id = "ford-13-ecoboost",
            make = "Ford", model = "Fiesta, Focus, EcoSport", engine = "1.0 EcoBoost",
            years = "2012–2021",
            issues = listOf(
                KnownIssue(
                    title = "Coolant loss and degas hose failure",
                    summary = "Overheating from a split hose, sometimes leading to head damage.",
                    detail = "Subject to recall and extended warranty action on many cars — " +
                        "worth checking the recall status by registration. Watch coolant " +
                        "temperature closely.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P0217", "P0128"),
                ),
                KnownIssue(
                    title = "Wet cambelt",
                    summary = "The belt runs in oil and degrades, shedding material into the pump.",
                    detail = "Replacement intervals were revised downward. Debris from a " +
                        "breaking-up belt can block the oil pump pickup and destroy the engine.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    typicalMileage = "100,000 or 10 years",
                ),
            ),
        ),

        VehicleModel(
            id = "vauxhall-14turbo",
            make = "Vauxhall", model = "Corsa, Astra, Mokka", engine = "1.4 Turbo",
            years = "2009–2019",
            issues = listOf(
                KnownIssue(
                    title = "Timing chain wear",
                    summary = "Rattle on start-up, stretched chain.",
                    detail = "Regular oil changes materially affect how long the chain lasts.",
                    confidence = IssueConfidence.COMMON,
                    codes = listOf("P0016", "P0011"),
                ),
                KnownIssue(
                    title = "Water pump and thermostat housing",
                    summary = "Coolant leaks from the plastic housing.",
                    detail = "Common enough to be worth checking whenever coolant level drops.",
                    confidence = IssueConfidence.COMMON,
                    codes = listOf("P0128"),
                ),
            ),
        ),

        VehicleModel(
            id = "nissan-qashqai-15dci",
            make = "Nissan", model = "Qashqai, Juke", engine = "1.5 dCi", years = "2007–2018",
            issues = listOf(
                KnownIssue(
                    title = "DPF and EGR on short journeys",
                    summary = "The filter cannot regenerate on town driving.",
                    detail = "Extremely common on this engine in urban use.",
                    confidence = IssueConfidence.WELL_DOCUMENTED,
                    codes = listOf("P2002", "P0401", "P242F"),
                ),
                KnownIssue(
                    title = "Turbo failure",
                    summary = "Often follows oil starvation or a blocked DPF.",
                    detail = "A blocked filter raises back pressure and shortens turbo life, so " +
                        "the two faults tend to arrive together.",
                    confidence = IssueConfidence.COMMON,
                    codes = listOf("P0299", "P0234"),
                ),
            ),
        ),
    )

    /** Everything known about a model, by its stable id. */
    fun byId(id: String): VehicleModel? = models.firstOrNull { it.id == id }

    /**
     * Known issues that can set a given fault code, for the model the owner selected.
     *
     * This is the half of the feature that connects to the adapter: it turns a generic
     * description — "camshaft position correlation" — into the thing that usually causes it
     * on this particular engine.
     */
    fun issuesForCode(modelId: String?, code: String): List<KnownIssue> {
        val model = modelId?.let { byId(it) } ?: return emptyList()
        return model.issues.filter { issue -> issue.codes.any { it.equals(code, ignoreCase = true) } }
    }

    /** Makes present in the list, for the picker. */
    val makes: List<String> get() = models.map { it.make }.distinct().sorted()
}
