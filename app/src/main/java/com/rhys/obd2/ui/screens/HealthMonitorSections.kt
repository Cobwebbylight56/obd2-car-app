package com.rhys.obd2.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rhys.obd2.data.Abnormality
import com.rhys.obd2.data.AbnormalSeverity
import com.rhys.obd2.data.LinkState
import com.rhys.obd2.data.WarningState
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Space
import com.rhys.obd2.ui.theme.Tone
import java.text.SimpleDateFormat
import java.util.Locale

/** The tone a severity is drawn in, so colour and wording never disagree. */
internal fun AbnormalSeverity.tone(): Tone = when (this) {
    AbnormalSeverity.WATCH -> Tone.INFO
    AbnormalSeverity.NOTABLE -> Tone.WARNING
    AbnormalSeverity.SERIOUS -> Tone.DANGER
}

/**
 * The engine management light and the stored-fault count, watched rather than asked for.
 *
 * Shown even when everything is clear, because "no warning lights" is information and an
 * empty space is not — a driver looking at a blank screen cannot tell whether the app
 * checked and found nothing or never checked at all.
 */
@Composable
internal fun WarningLightsCard(warnings: WarningState?) {
    SectionCard(
        title = "Warning lights",
        trailing = {
            when {
                warnings == null -> StatusPill("Checking", Tone.NEUTRAL)
                warnings.milOn -> StatusPill("Light on", Tone.DANGER)
                else -> StatusPill("Clear", Tone.ACCENT)
            }
        },
    ) {
        Column {
            when {
                warnings == null -> Text(
                    "Waiting for the first check. The app reads the engine management " +
                        "light every twenty seconds while it is connected, so a light that " +
                        "comes on mid-journey is noticed and dated rather than found later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                warnings.milOn -> Text(
                    "The engine management light is on and the ECU is reporting " +
                        "${warnings.storedCount} stored fault" +
                        (if (warnings.storedCount == 1) "" else "s") +
                        ". Read the codes to see what it is. Every change is dated in this " +
                        "car's history, including the light going out again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "No warning light, and the ECU is reporting no stored faults. Checked " +
                        "continuously while connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Everything reading wrongly, and everything that was and no longer is.
 *
 * Recovered entries are kept rather than removed. A fault that comes and goes is a
 * different problem from one that stays, and only a list that keeps both shows which this
 * is — deleting the entry the moment the value came good would throw away the more useful
 * half of the observation.
 */
@Composable
internal fun LiveFindingsCard(findings: List<Abnormality>) {
    val active = findings.filter { it.active && !it.suppressed }
    val explained = findings.filter { it.active && it.suppressed }
    val recovered = findings.filter { !it.active }

    SectionCard(
        title = "Live readings",
        subtitle = "Checked against what this car should be doing, in context",
        trailing = {
            when {
                active.any { it.severity == AbnormalSeverity.SERIOUS } ->
                    StatusPill("${active.size} to look at", Tone.DANGER)
                active.isNotEmpty() -> StatusPill("${active.size} to look at", Tone.WARNING)
                else -> StatusPill("Nothing unusual", Tone.ACCENT)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (findings.isEmpty()) {
                Text(
                    "Nothing has read outside its expected range. Every parameter on the " +
                        "dashboard is judged against what it should be under the conditions " +
                        "at the time — a value is only flagged once it has been wrong for " +
                        "several readings running, so a momentary blip is not treated as a " +
                        "fault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            active.forEach { FindingRow(it) }

            if (explained.isNotEmpty()) {
                GroupLabel("Expected on this car")
                explained.forEach { FindingRow(it) }
            }

            if (recovered.isNotEmpty()) {
                GroupLabel("Came good again")
                recovered.forEach { FindingRow(it) }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Spacer(Modifier.height(Space.xs))
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FindingRow(finding: Abnormality) {
    val tone = if (finding.suppressed) Tone.NEUTRAL
    else if (!finding.active) Tone.ACCENT
    else finding.severity.tone()

    ExplainerCard(
        tone = tone,
        text = buildString {
            append(finding.label)
            append(": ")
            append("%.1f".format(Locale.UK, finding.value))
            if (finding.unit.isNotBlank()) append(" ${finding.unit}")

            finding.expected?.let {
                append("\nExpected ${it.text} ${it.whenApplies}.")
            }

            append("\n\n")
            append(finding.message)

            finding.corroboration?.let { append("\n\n$it") }

            finding.explainedBy?.let {
                append("\n\nNot treated as a fault: $it.")
            }

            append("\n\n")
            append(
                if (finding.active) {
                    "Seen ${finding.occurrences} time${if (finding.occurrences == 1) "" else "s"} " +
                        "over ${humanDuration(finding.durationMs)}, still current."
                } else {
                    "Was out of range for ${humanDuration(finding.durationMs)} and has " +
                        "returned to normal. Kept because a fault that comes and goes is a " +
                        "different problem from one that stays."
                }
            )
        },
    )
}

private fun humanDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "$seconds seconds"
        seconds < 3_600 -> "${seconds / 60} minutes"
        else -> "${seconds / 3600} hours"
    }
}

/**
 * The state of the link itself, and the button for when the driver knows better.
 *
 * Only drawn when there is something to say. A banner that is always there is furniture
 * and stops being read; one that appears only when data has stopped arriving is a signal.
 */
@Composable
internal fun LinkStatusCard(link: LinkState, onReconnect: () -> Unit) {
    if (link is LinkState.Idle || link is LinkState.Live) return

    val (tone, title, body) = when (link) {
        is LinkState.Stalled -> Triple(
            Tone.WARNING,
            "Nothing coming back",
            "The last reading arrived at ${clock(link.lastReadingAt)}. The connection is " +
                "still open, which on Bluetooth means very little — walking out of range " +
                "or leaving the car long enough for the ECU to go to sleep looks exactly " +
                "like this. Trying to get it back.",
        )
        is LinkState.Recovering -> Triple(
            Tone.INFO,
            "Reconnecting",
            "${link.step} — attempt ${link.attempt}. The gauges below are the last values " +
                "that actually arrived, not current ones.",
        )
        is LinkState.Lost -> Triple(
            Tone.DANGER,
            "Connection lost",
            "${link.reason}. The app has stopped trying by itself. Tap Reconnect when you " +
                "are back at the car with the ignition on.",
        )
        else -> return
    }

    SectionCard(title = title, trailing = { StatusPill(shortLabel(link), tone) }) {
        Column {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.sm))
            OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space.xs))
                Text("Reconnect now")
            }
        }
    }
}

private fun shortLabel(link: LinkState): String = when (link) {
    is LinkState.Stalled -> "Stalled"
    is LinkState.Recovering -> "Attempt ${link.attempt}"
    is LinkState.Lost -> "Lost"
    else -> ""
}

private fun clock(at: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.UK).format(java.util.Date(at))
