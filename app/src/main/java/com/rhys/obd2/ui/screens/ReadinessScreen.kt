package com.rhys.obd2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.obd.Monitor
import com.rhys.obd2.obd.Readiness
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.color
import com.rhys.obd2.ui.theme.colors

/**
 * Emissions readiness — the "will it pass the test" screen.
 *
 * Framed around the practical question rather than the raw bits, because the thing people
 * actually get caught out by is clearing codes the day before an MOT and failing on
 * incomplete monitors instead.
 */
@Composable
fun ReadinessScreen(
    viewModel: ObdViewModel,
    onOpenTests: () -> Unit,
) {
    val connection by viewModel.connectionState.collectAsState()
    val readiness by viewModel.readiness.collectAsState()
    val codes by viewModel.dtcs.collectAsState()
    val busy by viewModel.busy.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Emissions health", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Readiness monitors and self-test results",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        if (connection !is ConnectionState.Connected) {
            item { ExplainerCard(tone = Tone.INFO, text = "Connect to the car to check emissions readiness.") }
            return@LazyColumn
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.refreshReadiness() },
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Check now")
                }
                OutlinedButton(onClick = onOpenTests, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Science, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Test results")
                }
            }
        }

        val current = readiness
        if (current == null) {
            item {
                ExplainerCard(
                    tone = Tone.INFO,
                    text = "Tap Check now. Readiness monitors are the self-tests your car runs on its " +
                        "own emissions equipment while you drive. An MOT or emissions test checks these " +
                        "before it checks anything else.",
                )
            }
            return@LazyColumn
        }

        item { VerdictCard(current, codes?.stored?.size ?: 0) }

        item {
            SectionCard(
                title = "Monitors",
                subtitle = if (current.compressionIgnition) "Diesel engine" else "Petrol engine",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    current.supportedMonitors.forEach { MonitorRow(it) }
                    val unsupported = current.monitors.filter { !it.supported }
                    if (unsupported.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Not fitted to this car: " + unsupported.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (current.incomplete.isNotEmpty()) {
            item {
                SectionCard(title = "How to complete the remaining monitors") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Monitors run on their own, but only under specific conditions. A drive cycle " +
                                "that gets most cars there:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DriveStep(1, "Start cold — the car must sit for at least eight hours, so this is a first-thing-in-the-morning job.")
                        DriveStep(2, "Idle for two to three minutes with the air conditioning and rear demister on, then turn them off.")
                        DriveStep(3, "Drive at a steady 30–40 mph for about five minutes, ideally without stopping.")
                        DriveStep(4, "Accelerate steadily to around 55–60 mph and hold it for five to ten minutes.")
                        DriveStep(5, "Coast down off the throttle from 55 mph to about 20 mph without braking.")
                        DriveStep(6, "Idle again for two minutes, then switch off. Re-check this screen.")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The evaporative monitor is the fussiest: many cars will only run it with the " +
                                "fuel tank between roughly a quarter and three quarters full, and it may " +
                                "take several days of normal driving.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun VerdictCard(readiness: Readiness, storedCodes: Int) {
    val blockedByCodes = readiness.milOn || storedCodes > 0
    val incomplete = readiness.incompleteCount

    val (colour, headline, detail) = when {
        blockedByCodes -> Triple(
            Tone.DANGER.color(),
            "Would fail an emissions test",
            "The engine management light is on, or there are stored fault codes. That's an automatic " +
                "failure regardless of how the car actually runs. Fix the fault first, then let the " +
                "monitors complete again.",
        )
        incomplete == 0 -> Triple(
            Tone.ACCENT.color(),
            "Ready for an emissions test",
            "Every monitor this car supports has completed and no faults are stored. This is the state " +
                "a test station wants to see.",
        )
        incomplete <= 2 -> Triple(
            Tone.WARNING.color(),
            "$incomplete monitor${if (incomplete == 1) "" else "s"} not yet complete",
            "No faults are stored, but $incomplete self-test hasn't finished running. Most testing " +
                "regimes allow one or two incomplete monitors on older cars and none on newer ones, so " +
                "this may or may not pass. A proper drive cycle will finish them off.",
        )
        else -> Triple(
            Tone.WARNING.color(),
            "$incomplete monitors not yet complete",
            "This many incomplete monitors usually means the codes were cleared or the battery was " +
                "disconnected recently. The car needs a good run — see the drive cycle below — before " +
                "it will pass.",
        )
    }

    SectionCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colour.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (incomplete == 0 && !blockedByCodes) Icons.Filled.Check else Icons.Filled.HourglassEmpty,
                        contentDescription = null,
                        tint = colour,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(headline, fontWeight = FontWeight.SemiBold, color = colour, fontSize = 16.sp)
                    Text(
                        "${readiness.supportedMonitors.size - incomplete} of ${readiness.supportedMonitors.size} monitors complete",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium)

            if (readiness.dtcCount > 0) {
                Spacer(Modifier.height(10.dp))
                StatusPill("${readiness.dtcCount} code${if (readiness.dtcCount == 1) "" else "s"} reported by the ECU", Tone.DANGER)
            }
        }
    }
}

@Composable
private fun MonitorRow(monitor: Monitor) {
    val colour = if (monitor.complete) Tone.ACCENT.color() else Tone.WARNING.color()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(colour.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (monitor.complete) Icons.Filled.Check else Icons.Filled.HourglassEmpty,
                contentDescription = if (monitor.complete) "Complete" else "Not complete",
                tint = colour,
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(monitor.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    if (monitor.complete) "Complete" else "Not ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = colour,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                monitor.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DriveStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Tone.INFO.colors().container),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", fontSize = 11.sp, color = Tone.INFO.color(), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
