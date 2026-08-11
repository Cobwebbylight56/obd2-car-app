package com.rhys.obd2.data

/**
 * What has been done to a component that the factory did not do.
 *
 * The distinction matters diagnostically and is not just wording. A blanked EGR is still
 * physically present and still reports a position, it just cannot flow — so its position
 * reading stays believable while its flow-related codes become meaningless. A removed one
 * reports nothing at all. Telling the app which of those happened changes what it should
 * expect to see.
 */
enum class ModKind(val label: String, val detail: String) {
    REMOVED("Removed", "Taken off the car entirely"),
    BLANKED("Blanked off", "Still fitted but plated so nothing flows through it"),
    DISABLED("Disabled", "Still fitted and connected, but switched off in software"),
    MODIFIED("Modified", "Altered from standard but still doing its job"),
    UPGRADED("Upgraded", "Replaced with something bigger or better than standard"),
}

/**
 * A part of the car an owner might have changed, and what changing it does to the data.
 *
 * [affectedPids] and [affectedCodes] are the whole point. Once a component is declared
 * modified, readings and fault codes that exist to police that component stop being
 * evidence of a fault — a de-catted car will set P0420 forever and it is not news. Marking
 * them suppressed rather than deleting them keeps them visible and honest: the app still
 * shows the code, it just says why it is expected.
 *
 * [expectedInstead] is the other half. Suppressing a false alarm is not the same as knowing
 * what the reading should now be, and where a modification has a predictable effect the app
 * says so rather than simply going quiet.
 */
data class ModifiableComponent(
    val id: String,
    val name: String,
    val what: String,
    /** Live parameters whose normal ranges no longer apply. */
    val affectedPids: Set<Int> = emptySet(),
    /** Fault code prefixes this component is responsible for setting. */
    val affectedCodes: List<String> = emptyList(),
    /** Which kinds of change make sense for this part, most likely first. */
    val kinds: List<ModKind> = listOf(ModKind.REMOVED, ModKind.BLANKED, ModKind.DISABLED),
    /** What the app should expect to see once it has been changed. */
    val expectedInstead: String = "",
)

/** One declared change to one component on one car. */
data class Modification(
    val componentId: String,
    val kind: ModKind,
    val note: String = "",
    val recordedAt: Long = System.currentTimeMillis(),
)

/**
 * The parts owners actually change, chosen for what turns up on UK cars.
 *
 * Deliberately finite and specific rather than a free-text box. A component only earns a
 * place here if the app can do something concrete with it — suppress a named code, widen a
 * named parameter's range, or change what it expects a related reading to do. A list of
 * modifications the diagnostic logic ignores would be a form that does nothing.
 */
object ModificationCatalogue {

    val all: List<ModifiableComponent> = listOf(
        ModifiableComponent(
            id = "egr",
            name = "EGR valve",
            what = "Feeds exhaust gas back into the intake to lower combustion temperature " +
                "and NOx. The first thing removed on a diesel, because it sooties up the " +
                "inlet manifold.",
            // Commanded EGR, EGR error, and the EGR temperature sensors.
            affectedPids = setOf(0x2C, 0x2D, 0x69, 0x6A, 0x6B),
            affectedCodes = listOf("P040", "P041", "P0487", "P0488", "P1436", "P1406"),
            expectedInstead = "EGR error will read a large constant value or nothing at all, " +
                "and intake air temperature will run slightly cooler than standard. Neither " +
                "is a fault on a car with the EGR blanked.",
        ),
        ModifiableComponent(
            id = "egr_cooler",
            name = "EGR cooler",
            what = "The heat exchanger that cools recirculated exhaust gas before it " +
                "re-enters the inlet.",
            affectedPids = setOf(0x69, 0x6A, 0x6B),
            affectedCodes = listOf("P040", "P0401", "P0404"),
        ),
        ModifiableComponent(
            id = "swirl_flaps",
            name = "Swirl flaps",
            what = "Butterfly flaps in the inlet manifold that improve low-speed mixing. On " +
                "the BMW M47 — the Freelander Td4 engine — they are routinely removed " +
                "because a failed one can be drawn into a cylinder.",
            affectedPids = setOf(0x11),
            affectedCodes = listOf("P2004", "P2005", "P2006", "P2007", "P2008", "P2009", "P2015"),
            expectedInstead = "Intake manifold runner control codes will set and stay set. " +
                "Throttle or flap position may read a fixed value.",
        ),
        ModifiableComponent(
            id = "dpf",
            name = "Diesel particulate filter",
            what = "Traps soot in the exhaust and burns it off periodically. Removed or " +
                "gutted when regeneration fails or the filter blocks.",
            affectedPids = setOf(0x7A, 0x7B, 0x7C, 0x86, 0x87),
            affectedCodes = listOf("P2002", "P2003", "P242", "P2452", "P2453", "P2454",
                "P2455", "P2456", "P2457", "P2458", "P2459", "P2463", "P1471"),
            expectedInstead = "Differential pressure across the filter will read near zero " +
                "and no regeneration will ever be commanded.",
        ),
        ModifiableComponent(
            id = "catalyst",
            name = "Catalytic converter",
            what = "Converts the nastier exhaust products into less nasty ones. Removed for " +
                "flow, or when it breaks up internally and blocks.",
            affectedPids = setOf(0x3C, 0x3D, 0x3E, 0x3F,
                0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B),
            affectedCodes = listOf("P0420", "P0421", "P0422", "P0423", "P0424",
                "P0430", "P0431", "P0432", "P0433", "P0434"),
            expectedInstead = "Catalyst efficiency codes will set permanently, and the " +
                "downstream oxygen sensor will mirror the upstream one instead of sitting " +
                "steady. Both are expected on a de-catted car and neither indicates a fault.",
        ),
        ModifiableComponent(
            id = "lambda_downstream",
            name = "Downstream oxygen sensor",
            what = "The sensor after the catalyst, which exists to check the catalyst is " +
                "working. Often fitted with a spacer or an electronic simulator after a decat.",
            affectedPids = setOf(0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B),
            affectedCodes = listOf("P0136", "P0137", "P0138", "P0139", "P0140",
                "P0156", "P0157", "P0158", "P0159", "P0160", "P0420", "P0430"),
            kinds = listOf(ModKind.MODIFIED, ModKind.REMOVED, ModKind.DISABLED),
        ),
        ModifiableComponent(
            id = "maf",
            name = "Mass airflow sensor",
            what = "Measures the air entering the engine. Sometimes unplugged deliberately " +
                "on a diesel, which forces the ECU onto a default fuelling map.",
            affectedPids = setOf(0x10),
            affectedCodes = listOf("P0100", "P0101", "P0102", "P0103", "P0104"),
            kinds = listOf(ModKind.REMOVED, ModKind.DISABLED, ModKind.MODIFIED),
            expectedInstead = "No airflow reading, so any figure derived from it — including " +
                "this app's estimated engine load — falls back to something coarser.",
        ),
        ModifiableComponent(
            id = "anti_shudder",
            name = "Anti-shudder valve",
            what = "The throttle flap on a diesel, which exists only to close the intake " +
                "smoothly at shutdown. Often removed along with the EGR it works with.",
            affectedPids = setOf(0x11, 0x45, 0x47, 0x48, 0x49),
            affectedCodes = listOf("P0121", "P0122", "P0123", "P2100", "P2101", "P2103"),
            expectedInstead = "Throttle position will read a fixed value — commonly a flat " +
                "0.00% — however the car is driven.",
        ),
        ModifiableComponent(
            id = "thermostat",
            name = "Thermostat",
            what = "Holds the engine at its operating temperature. Sometimes replaced with a " +
                "cooler-opening one on a mapped car.",
            affectedPids = setOf(0x05, 0x67),
            affectedCodes = listOf("P0128", "P0125", "P0126", "P0116", "P0117", "P0118"),
            kinds = listOf(ModKind.MODIFIED, ModKind.REMOVED, ModKind.UPGRADED),
            expectedInstead = "The engine will settle at a lower coolant temperature than " +
                "standard and may take longer to get there.",
        ),
        ModifiableComponent(
            id = "turbo",
            name = "Turbocharger",
            what = "Hybrid or larger turbo, or a modified actuator.",
            affectedPids = setOf(0x0B, 0x70, 0x71, 0x72, 0x73, 0x0F),
            affectedCodes = listOf("P0234", "P0235", "P0236", "P0237", "P0238",
                "P0243", "P0245", "P0246", "P0299"),
            kinds = listOf(ModKind.UPGRADED, ModKind.MODIFIED),
            expectedInstead = "Manifold pressure will exceed the standard car's range, and " +
                "intake air temperature will run hotter under boost.",
        ),
        ModifiableComponent(
            id = "remap",
            name = "ECU remap",
            what = "The fuelling and boost maps rewritten for more power, or for economy.",
            affectedPids = setOf(0x04, 0x0B, 0x5E, 0x10, 0x43),
            kinds = listOf(ModKind.MODIFIED, ModKind.UPGRADED),
            expectedInstead = "Load, boost and fuel rate will all read above the standard " +
                "car's figures under acceleration. That is the modification working.",
        ),
        ModifiableComponent(
            id = "intake",
            name = "Air filter or intake",
            what = "Induction kit, panel filter or reworked intake pipework.",
            affectedPids = setOf(0x10, 0x0F),
            kinds = listOf(ModKind.MODIFIED, ModKind.UPGRADED),
            expectedInstead = "Airflow readings shift from standard, and intake air " +
                "temperature usually rises because the filter sits in a warmer place.",
        ),
        ModifiableComponent(
            id = "intercooler",
            name = "Intercooler or boost pipes",
            what = "A larger intercooler or uprated pipework.",
            affectedPids = setOf(0x0F, 0x0B),
            kinds = listOf(ModKind.UPGRADED, ModKind.MODIFIED),
            expectedInstead = "Intake air temperature under boost should read lower than " +
                "standard, which is the point of fitting one.",
        ),
        ModifiableComponent(
            id = "exhaust",
            name = "Exhaust system",
            what = "Straight-through, sports or otherwise non-standard exhaust.",
            affectedPids = setOf(0x3C, 0x3D, 0x78, 0x79),
            affectedCodes = listOf("P0420", "P0430"),
            kinds = listOf(ModKind.MODIFIED, ModKind.UPGRADED),
        ),
        ModifiableComponent(
            id = "scr",
            name = "AdBlue / SCR system",
            what = "Urea injection to clean up NOx on a modern diesel.",
            affectedPids = setOf(0x83, 0x85),
            affectedCodes = listOf("P20", "P21"),
            expectedInstead = "Reductant-system codes will set and stay set.",
        ),
        ModifiableComponent(
            id = "secondary_air",
            name = "Secondary air injection",
            what = "Pumps air into the exhaust from cold to light off the catalyst sooner.",
            affectedCodes = listOf("P0410", "P0411", "P0412", "P0413", "P0414",
                "P0415", "P0416", "P0417", "P0418"),
        ),
        ModifiableComponent(
            id = "evap",
            name = "EVAP system",
            what = "Captures fuel vapour from the tank. Petrol only.",
            affectedCodes = listOf("P0440", "P0441", "P0442", "P0443", "P0444", "P0445",
                "P0446", "P0447", "P0448", "P0449", "P0455", "P0456", "P0457"),
        ),
    )

    operator fun get(id: String): ModifiableComponent? = all.firstOrNull { it.id == id }
}

/**
 * Everything declared modified on one car, and the questions the diagnostic logic asks of it.
 *
 * Immutable and cheap to build, so the repository can hold the current car's set and consult
 * it on every reading without caring when it last changed.
 */
data class VehicleModifications(val entries: List<Modification> = emptyList()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    private val components: List<Pair<Modification, ModifiableComponent>> =
        entries.mapNotNull { mod -> ModificationCatalogue[mod.componentId]?.let { mod to it } }

    /**
     * The modification that explains this parameter reading oddly, if one does.
     *
     * Returns the declaration rather than a boolean so the caller can say which change is
     * responsible. "Outside the standard range because the EGR is blanked" is useful;
     * "suppressed" on its own is the app being mysterious about its own reasoning.
     */
    fun explains(pid: Int): Pair<Modification, ModifiableComponent>? =
        components.firstOrNull { (_, component) -> pid in component.affectedPids }

    /**
     * The modification that explains this fault code, if one does.
     *
     * Matched on prefix because manufacturers extend the standard ranges — a Land Rover
     * P1436 is in the same family as the generic P0400s, and listing every variant of every
     * code every manufacturer has ever defined is not a list anyone can keep correct.
     */
    fun explains(code: String): Pair<Modification, ModifiableComponent>? {
        val normalised = code.trim().uppercase()
        return components.firstOrNull { (_, component) ->
            component.affectedCodes.any { normalised.startsWith(it) }
        }
    }

    fun has(componentId: String): Boolean = entries.any { it.componentId == componentId }

    fun kindOf(componentId: String): ModKind? =
        entries.firstOrNull { it.componentId == componentId }?.kind

    /** A sentence naming the change, for the note that goes beside a suppressed reading. */
    fun describe(mod: Modification, component: ModifiableComponent): String =
        "${component.name} ${mod.kind.label.lowercase()}" +
            if (mod.note.isBlank()) "" else " — ${mod.note}"
}
