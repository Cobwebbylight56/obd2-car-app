package com.rhys.obd2.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhys.obd2.data.UnitSystem
import com.rhys.obd2.ui.components.EmptyState
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.Gauge
import com.rhys.obd2.ui.components.InfoRow
import com.rhys.obd2.ui.components.RowIcon
import com.rhys.obd2.ui.components.ScreenHeader
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.Sparkline
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.components.TappableRow
import com.rhys.obd2.ui.screens.OdometerHeader
import com.rhys.obd2.ui.theme.MonoBody
import com.rhys.obd2.ui.theme.NumericLarge
import com.rhys.obd2.ui.theme.NumericMedium
import com.rhys.obd2.ui.theme.NumericSmall
import com.rhys.obd2.ui.theme.Radius
import com.rhys.obd2.ui.theme.Space
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.colors
import com.rhys.obd2.ui.theme.status

/**
 * One reviewable thing: a component in a particular state, or a foundation laid out for
 * inspection.
 *
 * [name] becomes the screenshot filename, so it has to be stable — renaming an entry
 * orphans its baseline image and shows up as an addition plus a deletion rather than as
 * a change, which is exactly the diff you don't want when reviewing.
 */
data class GalleryEntry(
    val group: String,
    val name: String,
    val notes: String = "",
    /** Render width. Narrow entries catch truncation that a full-width render hides. */
    val width: Dp = 360.dp,
    val content: @Composable () -> Unit,
)

/**
 * The design review harness.
 *
 * Every component in every state it can be in, in one place, rendered independently of the
 * app and of a car. It exists because reviewing this interface previously meant reading
 * Kotlin and imagining the result — which is how a light theme with 1.6:1 contrast shipped
 * and survived: nothing ever put the two themes side by side where the failure was
 * visible.
 *
 * The same list feeds three consumers, which is the point of it being data rather than a
 * set of `@Preview` functions:
 *
 *  - `GalleryScreenshotTest` renders each entry to a PNG in both themes and commits them,
 *    so a visual change shows up as an image diff in review.
 *  - `GalleryAccessibilityTest` walks each entry asserting the rules that are cheap to
 *    break and expensive to notice — touch target sizes, missing roles, unlabelled
 *    controls.
 *  - The in-app design lab browses it on a real device, where font scaling, dark mode and
 *    a physical thumb are the actual test.
 *
 * States that are hard to reach in the running app — a car with eleven fault codes, an
 * adapter that failed mid-handshake, a value pinned to its danger threshold — matter most
 * here, because those are the ones nobody ever looks at.
 */
object Gallery {

    val entries: List<GalleryEntry> = buildList {
        foundations()
        status()
        containers()
        readouts()
        structure()
        actionRows()
        emptyStates()
    }

    val groups: List<String> get() = entries.map { it.group }.distinct()

    // -----------------------------------------------------------------------------------
    // Foundations
    // -----------------------------------------------------------------------------------

    private fun MutableList<GalleryEntry>.foundations() {
        add(
            GalleryEntry(
                group = "Foundations",
                name = "colour-status-tones",
                notes = "Every status tone with its four roles. The light and dark renders " +
                    "of this one entry are the check that would have caught the unreadable " +
                    "light theme. 'ink' is the drawn-shape tone — held to 3:1 rather than " +
                    "4.5:1, and so allowed the chroma that text cannot have; in the light " +
                    "column it should be visibly more colourful than 'fg', and in the dark " +
                    "column identical to it.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    Tone.entries.forEach { tone ->
                        val c = tone.colors()
                        Column {
                            Text(tone.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(Space.xs))
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                Swatch("fg", c.foreground)
                                Swatch("ink", c.graphic)
                                Swatch("container", c.container)
                                Swatch("outline", c.outline)
                            }
                            Spacer(Modifier.height(Space.xs))
                            // The real test: the foreground actually used as text on its
                            // own container, which is the tightest pairing in the system.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(Radius.control)
                                    .background(c.container)
                                    .border(1.dp, c.outline, Radius.control)
                                    .padding(Space.sm)
                            ) {
                                Text(
                                    "Text on its own container",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.foreground,
                                )
                            }
                        }
                    }
                }
            }
        )

        add(
            GalleryEntry(
                group = "Foundations",
                name = "colour-surfaces",
                notes = "The surface ladder. Each step must be distinguishable from its " +
                    "neighbour or cards stop reading as separate objects.",
            ) {
                val s = MaterialTheme.colorScheme
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf(
                        "background" to s.background,
                        "surface" to s.surface,
                        "surfaceVariant" to s.surfaceVariant,
                        "surfaceContainerHighest" to s.surfaceContainerHighest,
                        "outline" to s.outline,
                        "outlineVariant" to s.outlineVariant,
                    ).forEach { (label, colour) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(Radius.control)
                                    .background(colour)
                                    .border(1.dp, s.outline, Radius.control)
                            )
                            Spacer(Modifier.width(Space.md))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        )

        add(
            GalleryEntry(
                group = "Foundations",
                name = "typography-scale",
                notes = "Every style in the scale at its real size, so steps that are too " +
                    "close to tell apart are visible as a run.",
            ) {
                val t = MaterialTheme.typography
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("Display small 34", style = t.displaySmall)
                    Text("Headline medium 26", style = t.headlineMedium)
                    Text("Headline small 21", style = t.headlineSmall)
                    Text("Title large 18", style = t.titleLarge)
                    Text("Title medium 16", style = t.titleMedium)
                    Text("Title small 14", style = t.titleSmall)
                    Text("Body large 16 — the size running text is set at", style = t.bodyLarge)
                    Text("Body medium 14 — the default for most content", style = t.bodyMedium)
                    Text("Body small 12 — the floor, used for explanations", style = t.bodySmall)
                    Text("LABEL LARGE 14", style = t.labelLarge)
                    Text("LABEL MEDIUM 12", style = t.labelMedium)
                    Text("LABEL SMALL 11", style = t.labelSmall)
                }
            }
        )

        add(
            GalleryEntry(
                group = "Foundations",
                name = "typography-numeric",
                notes = "Monospaced faces. The digits must hold their column — a value " +
                    "flickering between 998 and 1002 should change in place, not shuffle " +
                    "sideways.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("1088", style = NumericLarge)
                    Text("1888", style = NumericLarge)
                    Text("88.8 V", style = NumericMedium)
                    Text("11.1 V", style = NumericMedium)
                    Text("WVWZZZ1KZAW123456", style = NumericSmall)
                    Text("41 0C 1A F8 >", style = MonoBody)
                }
            }
        )

        add(
            GalleryEntry(
                group = "Foundations",
                name = "spacing-scale",
                notes = "The four-point scale, drawn to size.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf(
                        "xxs 2" to Space.xxs, "xs 4" to Space.xs, "sm 8" to Space.sm,
                        "md 12" to Space.md, "lg 16" to Space.lg, "xl 24" to Space.xl,
                        "xxl 32" to Space.xxl,
                    ).forEach { (label, size) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .width(size)
                                    .height(20.dp)
                                    .background(MaterialTheme.status.accent)
                            )
                            Spacer(Modifier.width(Space.md))
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        )
    }

    // -----------------------------------------------------------------------------------
    // Status
    // -----------------------------------------------------------------------------------

    private fun MutableList<GalleryEntry>.status() {
        add(
            GalleryEntry(
                group = "Status",
                name = "status-pill-all-tones",
                notes = "Each pill carries a dot and a word as well as a hue, so it survives " +
                    "being read by someone who can't separate the colours.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    StatusPill("Talking to the car", Tone.ACCENT)
                    StatusPill("2 monitors incomplete", Tone.WARNING)
                    StatusPill("Engine light on", Tone.DANGER)
                    StatusPill("Maker-specific", Tone.INFO)
                    StatusPill("Not connected", Tone.NEUTRAL)
                }
            }
        )

        add(
            GalleryEntry(
                group = "Status",
                name = "status-pill-long-label",
                notes = "A pill with a label long enough to need the full width. Checks it " +
                    "truncates rather than pushing the row out of the card.",
                width = 280.dp,
            ) {
                StatusPill("11 codes reported by the ECU", Tone.DANGER)
            }
        )
    }

    // -----------------------------------------------------------------------------------
    // Containers
    // -----------------------------------------------------------------------------------

    private fun MutableList<GalleryEntry>.containers() {
        add(
            GalleryEntry(group = "Containers", name = "section-card-plain") {
                SectionCard { Text("Body content", style = MaterialTheme.typography.bodyMedium) }
            }
        )

        add(
            GalleryEntry(group = "Containers", name = "section-card-titled") {
                SectionCard(
                    title = "Nearby adapters",
                    subtitle = "Scanning…",
                    trailing = { StatusPill("Live", Tone.ACCENT) },
                ) {
                    Column {
                        InfoRow("Protocol", "ISO 9141-2")
                        InfoRow("Adapter", "ELM327 v1.5")
                        InfoRow("Battery", "14.1 V")
                    }
                }
            }
        )

        add(
            GalleryEntry(
                group = "Containers",
                name = "section-card-long-title",
                notes = "A title long enough to wrap next to a trailing element. The trailing " +
                    "item must stay put rather than being pushed off the card.",
                width = 320.dp,
            ) {
                SectionCard(
                    title = "Catalyst monitor bank 1 sensor 1",
                    subtitle = "Last read 3 minutes ago from the engine control module",
                    trailing = { StatusPill("Passed", Tone.ACCENT) },
                ) {
                    Text("Body", style = MaterialTheme.typography.bodyMedium)
                }
            }
        )

        add(
            GalleryEntry(
                group = "Containers",
                name = "explainer-short",
                notes = "The accent rule measures the paragraph rather than guessing from " +
                    "the string length. One line and many lines both have to line up.",
            ) {
                ExplainerCard(text = "Connect to the car to read fault codes.", tone = Tone.INFO)
            }
        )

        add(
            GalleryEntry(group = "Containers", name = "explainer-long") {
                ExplainerCard(
                    tone = Tone.WARNING,
                    text = "Clearing codes resets every readiness monitor, so clearing them " +
                        "the day before an MOT will fail the test on incomplete monitors even " +
                        "though the original fault is gone. Read them first — clearing " +
                        "destroys the only record you have.",
                )
            }
        )

        add(
            GalleryEntry(
                group = "Containers",
                name = "explainer-all-tones",
                notes = "All four side by side. Containers must stay distinguishable from the " +
                    "page and from each other.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    ExplainerCard(text = "Healthy and complete.", tone = Tone.ACCENT)
                    ExplainerCard(text = "Worth keeping an eye on.", tone = Tone.WARNING)
                    ExplainerCard(text = "This is a fault.", tone = Tone.DANGER)
                    ExplainerCard(text = "Background information.", tone = Tone.INFO)
                    ExplainerCard(text = "Neither good nor bad.", tone = Tone.NEUTRAL)
                }
            }
        )

        add(
            GalleryEntry(
                group = "Containers",
                name = "info-row-overflow",
                notes = "A label and a value that both want more room than there is. Neither " +
                    "may clip the other.",
                width = 300.dp,
            ) {
                SectionCard {
                    Column {
                        InfoRow("VIN", "WVWZZZ1KZAW123456")
                        InfoRow("Calibration verification number", "A1B2C3D4")
                        InfoRow("Short label", "1")
                    }
                }
            }
        )
    }

    // -----------------------------------------------------------------------------------
    // Readouts
    // -----------------------------------------------------------------------------------

    private fun MutableList<GalleryEntry>.readouts() {
        add(
            GalleryEntry(
                group = "Readouts",
                name = "gauge-tiles-as-on-the-dashboard",
                notes = "Two across, the width the dashboard actually uses, with the longest " +
                    "labels in the app. This is the case that was broken: the label used to " +
                    "sit inside the ring, so a two-line one overlapped the arc. It also " +
                    "checks that tiles line up when one label wraps and another doesn't.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf(
                        listOf(
                            Triple("Calculated engine load", "100.0", "%"),
                            Triple("Engine coolant temperature", "84.0", "°C"),
                        ),
                        listOf(
                            Triple("Engine RPM", "816", "rpm"),
                            Triple("Vehicle speed", "28", "mph"),
                        ),
                        listOf(
                            Triple("Commanded equivalence ratio (lambda)", "0.994", "λ"),
                            Triple("Odometer", "184320", "miles"),
                        ),
                    ).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            row.forEach { (name, value, unit) ->
                                Box(Modifier.weight(1f)) {
                                    SectionCard {
                                        Gauge(
                                            value = 62f, min = 0f, max = 100f,
                                            label = name, unit = unit, valueText = value,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )

        add(
            GalleryEntry(
                group = "Readouts",
                name = "gauge-thresholds",
                notes = "The same gauge below, at and above its warning and danger " +
                    "thresholds. These states are hard to reach in a healthy car and so are " +
                    "the ones least likely to have been looked at.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf(82f to "82", 108f to "108", 121f to "121").forEach { (v, text) ->
                        Box(Modifier.weight(1f)) {
                            Gauge(
                                value = v, min = -40f, max = 130f,
                                label = "Coolant temperature", unit = "°C", valueText = text,
                                warningThreshold = 105f, dangerThreshold = 115f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        )

        add(
            GalleryEntry(
                group = "Readouts",
                name = "gauge-temperature-scale",
                notes = "A fluid temperature reads blue when cold, green through its working " +
                    "range and red beyond it. Cold is not a fault, but an engine below " +
                    "temperature is wearing faster and using more fuel, and that is worth " +
                    "seeing without reading the number.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf(
                        listOf(12f to "12", 55f to "55", 78f to "78"),
                        listOf(90f to "90", 108f to "108", 121f to "121"),
                    ).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            row.forEach { (v, text) ->
                                Box(Modifier.weight(1f)) {
                                    Gauge(
                                        value = v, min = 0f, max = 130f,
                                        label = "Coolant temperature", unit = "°C", valueText = text,
                                        warningThreshold = 105f, dangerThreshold = 115f,
                                        optimalRange = 82f..105f,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )

        add(
            GalleryEntry(
                group = "Readouts",
                name = "gauge-percentage-grading",
                notes = "A parameter with no meaningful threshold grades across the whole " +
                    "sweep instead, so a rising load is visible before it is critical.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf(8f to "8", 42f to "42", 68f to "68", 95f to "95").forEach { (v, text) ->
                        Box(Modifier.weight(1f)) {
                            Gauge(
                                value = v, min = 0f, max = 100f,
                                label = "Engine load", unit = "%", valueText = text,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        )

        add(
            GalleryEntry(
                group = "Readouts",
                name = "gauge-edge-cases",
                notes = "No reading, a value pinned to each end of the scale, and a label " +
                    "long enough to wrap to its two-line limit.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Box(Modifier.weight(1f)) {
                        Gauge(
                            value = 0f, min = 0f, max = 7000f,
                            label = "Engine speed", unit = "rpm", valueText = "—",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        Gauge(
                            value = 7000f, min = 0f, max = 7000f,
                            label = "Engine speed", unit = "rpm", valueText = "7000",
                            warningThreshold = 5500f, dangerThreshold = 6500f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        Gauge(
                            value = 0.42f, min = 0f, max = 1f,
                            label = "Commanded equivalence ratio", unit = "", valueText = "0.42",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        )

        add(
            GalleryEntry(
                group = "Readouts",
                name = "sparkline-shapes",
                notes = "Flat, rising and noisy. A flat trace must still draw a line rather " +
                    "than collapsing to nothing.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    Sparkline(List(40) { 50f }, Modifier.fillMaxWidth().height(40.dp))
                    Sparkline(List(40) { it.toFloat() }, Modifier.fillMaxWidth().height(40.dp), Tone.INFO)
                    Sparkline(
                        List(40) { 50f + (it % 7) * 6f - (it % 3) * 9f },
                        Modifier.fillMaxWidth().height(40.dp), Tone.WARNING,
                    )
                }
            }
        )
    }

    // -----------------------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------------------

    private fun MutableList<GalleryEntry>.actionRows() {
        add(
            GalleryEntry(
                group = "Structure",
                name = "action-row-three-buttons",
                notes = "Three actions abreast, which is what broke: after an icon and " +
                    "Material's padding, a third of the width left about thirty points for " +
                    "the label and \"Report\" wrapped, stranding its last letter on a second " +
                    "line. Rendered at 320dp as well as full width, because the narrower " +
                    "phone is where it shows first.",
                width = 320.dp,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("Wrong — three abreast", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        listOf("Read codes", "Report", "Clear").forEach { text ->
                            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                                Text(text)
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.sm))
                    Text("Right — primary full width", style = MaterialTheme.typography.labelMedium)
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        Text("Read codes", maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        listOf("Report", "Clear").forEach { text ->
                            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                                Text(text, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        )
    }

    private fun MutableList<GalleryEntry>.structure() {
        add(
            GalleryEntry(
                group = "Structure",
                name = "odometer-header-states",
                notes = "The card pinned above the dashboard gauges, in all three states it " +
                    "can be in. The rollback state is the one that matters and the one no " +
                    "healthy car will ever produce, so this is the only place it gets looked " +
                    "at. The middle state is deliberately short-lived: once the app knows the " +
                    "car has no odometer to report, the dashboard drops the card rather than " +
                    "keeping a permanent apology at the top of the screen.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OdometerHeader(km = 296_720.0, units = UnitSystem.UK, rollback = false)
                    OdometerHeader(km = null, units = UnitSystem.UK, rollback = false)
                    OdometerHeader(km = 148_300.0, units = UnitSystem.UK, rollback = true)
                }
            }
        )

        add(
            GalleryEntry(group = "Structure", name = "screen-header") {
                Column {
                    ScreenHeader("Fault codes", subtitle = "3 codes found")
                    ScreenHeader(
                        "Garage",
                        subtitle = "2 cars",
                        trailing = { StatusPill("Plugged in", Tone.ACCENT) },
                    )
                }
            }
        )

        add(
            GalleryEntry(
                group = "Structure",
                name = "tappable-rows",
                notes = "Every row is at least 48dp tall regardless of its content, so the " +
                    "list has a rhythm and nothing is hard to hit.",
            ) {
                SectionCard {
                    Column {
                        TappableRow(
                            onClick = {},
                            leading = { RowIcon(Icons.Filled.Bluetooth, Tone.ACCENT) },
                            trailing = { StatusPill("Likely", Tone.ACCENT) },
                        ) {
                            Column {
                                Text("OBDII", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Bluetooth LE · strong signal",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TappableRow(
                            onClick = {},
                            leading = { RowIcon(Icons.Filled.DirectionsCar, Tone.NEUTRAL) },
                        ) {
                            Text("Single line row", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        )

        add(
            GalleryEntry(
                group = "Structure",
                name = "row-icons-all-tones",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    RowIcon(Icons.Filled.CheckCircle, Tone.ACCENT)
                    RowIcon(Icons.Filled.Warning, Tone.WARNING)
                    RowIcon(Icons.Filled.BatteryAlert, Tone.DANGER)
                    RowIcon(Icons.Filled.DirectionsCar, Tone.INFO)
                    RowIcon(Icons.Filled.Bluetooth, Tone.NEUTRAL)
                }
            }
        )

        add(
            GalleryEntry(
                group = "Structure",
                name = "buttons",
                notes = "Filled, outlined and destructive, plus their disabled states — the " +
                    "ones that never get looked at and are usually the ones with too little " +
                    "contrast.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Read codes") }
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Read codes (disabled)")
                    }
                    OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Report") }
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Report (disabled)")
                    }
                }
            }
        )
    }

    // -----------------------------------------------------------------------------------
    // Empty states
    // -----------------------------------------------------------------------------------

    private fun MutableList<GalleryEntry>.emptyStates() {
        add(
            GalleryEntry(
                group = "Empty states",
                name = "empty-no-cars",
                notes = "An empty state says what belongs here, why it is empty, and what to " +
                    "do — the third being the part that used to be missing everywhere.",
            ) {
                EmptyState(
                    icon = Icons.Filled.DirectionsCar,
                    title = "No cars yet",
                    message = "Connect to a car and it gets added here automatically, " +
                        "identified by its VIN.",
                    action = { Button(onClick = {}) { Text("Connect an adapter") } },
                )
            }
        )

        add(
            GalleryEntry(group = "Empty states", name = "empty-no-faults") {
                EmptyState(
                    icon = Icons.Filled.CheckCircle,
                    tone = Tone.ACCENT,
                    title = "No fault codes",
                    message = "Nothing stored, pending or permanent. The engine management " +
                        "light should be off.",
                )
            }
        )
    }

    @Composable
    private fun Swatch(label: String, colour: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(colour)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Spacer(Modifier.height(Space.xxs))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
