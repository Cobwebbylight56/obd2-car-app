package com.rhys.obd2.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.data.FreezeFrame
import com.rhys.obd2.data.UnitSystem
import com.rhys.obd2.data.Units
import com.rhys.obd2.obd.Dtc
import com.rhys.obd2.obd.DtcSeverity
import com.rhys.obd2.obd.DtcStatus
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.InfoRow
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Accent
import com.rhys.obd2.ui.theme.Danger
import com.rhys.obd2.ui.theme.Info
import androidx.core.content.FileProvider
import com.rhys.obd2.ui.theme.Warning
import kotlinx.coroutines.launch

@Composable
fun DtcScreen(viewModel: ObdViewModel) {
    val connection by viewModel.connectionState.collectAsState()
    val snapshot by viewModel.dtcs.collectAsState()
    val frame by viewModel.freezeFrame.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val units by viewModel.settings.units.collectAsState()

    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                    Text("Fault codes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    snapshot?.let {
                        Text(
                            "${it.total} code${if (it.total == 1) "" else "s"} found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (busy != null) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    StatusPill(
                        if (snapshot?.milOn == true) "Engine light on" else "Light off",
                        if (snapshot?.milOn == true) Danger else Accent,
                    )
                }
            }
        }

        if (connection !is ConnectionState.Connected) {
            item {
                ExplainerCard(
                    accent = Info,
                    text = "Connect to the car to read fault codes.",
                )
            }
            return@LazyColumn
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.refreshDtcs() },
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Read codes")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            viewModel.saveReport()?.let { shareReport(context, it) }
                        }
                    },
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Report")
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = busy == null && (snapshot?.stored?.isNotEmpty() == true || snapshot?.pending?.isNotEmpty() == true),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
            }
        }

        val current = snapshot
        if (current == null) {
            item {
                ExplainerCard(
                    accent = Info,
                    text = "Tap Read codes. The app checks all three lists the car keeps: confirmed " +
                        "faults, pending ones that haven't happened often enough to turn the light on " +
                        "yet, and permanent emissions codes that a scan tool can't erase.",
                )
            }
        } else if (current.total == 0) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("No fault codes stored", fontWeight = FontWeight.SemiBold)
                            Text(
                                "The engine management system isn't reporting any faults.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            listOf(
                Triple("Confirmed faults", current.stored, DtcStatus.STORED),
                Triple("Pending faults", current.pending, DtcStatus.PENDING),
                Triple("Permanent codes", current.permanent, DtcStatus.PERMANENT),
            ).forEach { (title, codes, status) ->
                if (codes.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            )
                            Text(
                                status.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Sorted worst-first so the code that matters is the one you see.
                    for (dtc in codes.sortedBy { it.severity.ordinal }) {
                        item(key = "${status.name}-${dtc.code}") { DtcCard(dtc) }
                    }
                }
            }
        }

        frame?.let { item { FreezeFrameCard(it, units) } }

        item { Spacer(Modifier.height(32.dp)) }
    }

    if (confirmClear) {
        ClearDialog(
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                viewModel.clearDtcs()
            },
        )
    }
}

@Composable
private fun DtcCard(dtc: Dtc) {
    var expanded by remember { mutableStateOf(false) }
    val colour = severityColour(dtc.severity)

    SectionCard(modifier = Modifier.clickable { expanded = !expanded }) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        dtc.code,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = colour,
                    )
                    Text(
                        dtc.description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(dtc.severity.label, colour)
                if (dtc.manufacturerSpecific) {
                    StatusPill("Maker-specific", Info)
                }
            }

            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 12.dp)) {
                    InfoRow("System", dtc.system.label)
                    InfoRow("Status", dtc.status.label)
                    InfoRow(
                        "Code type",
                        if (dtc.manufacturerSpecific) "Manufacturer-defined" else "Generic (SAE standard)",
                    )
                    dtc.advice?.let {
                        Spacer(Modifier.height(8.dp))
                        ExplainerCard(text = it, accent = colour)
                    }
                    if (dtc.manufacturerSpecific) {
                        Spacer(Modifier.height(8.dp))
                        ExplainerCard(
                            accent = Info,
                            text = "This code is defined by the manufacturer rather than the OBD-II " +
                                "standard, so the same number means different things on different makes. " +
                                "Look it up together with your car's make, model and engine.",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FreezeFrameCard(frame: FreezeFrame, units: UnitSystem) {
    SectionCard(
        title = "Freeze frame",
        subtitle = frame.triggerCode?.let { "Captured when $it was stored" }
            ?: "Conditions when the fault was recorded",
    ) {
        Column {
            ExplainerCard(
                accent = Info,
                text = "This is a snapshot of what the engine was doing at the exact moment the fault " +
                    "was stored. Cold or hot, idling or under load, lean or rich — it usually narrows " +
                    "the cause far more than the code alone.",
            )
            Spacer(Modifier.height(10.dp))
            frame.values.forEach { (pid, readings) ->
                val reading = readings.firstOrNull() ?: return@forEach
                val converted = Units.convert(reading.value, reading.unit, units)
                InfoRow(
                    pid.name,
                    reading.text ?: "${Units.format(converted.value, converted.unit)} ${converted.unit}".trim(),
                )
            }
        }
    }
}

@Composable
private fun ClearDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear fault codes?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This erases the stored and pending codes, the freeze frame data, and turns the engine light off.")
                Text(
                    "It does not repair anything. If the fault is still there the light will come back, " +
                        "usually within a few dozen miles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "It also resets every emissions readiness monitor. If you have an MOT or emissions " +
                        "test coming up, the car will need a full drive cycle afterwards or it will fail " +
                        "on incomplete monitors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning,
                )
                Text(
                    "This is the only record of these codes. Tap Cancel and then Report to save " +
                        "the codes, freeze frame and readiness state to a file you can keep or send " +
                        "to a garage.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
            ) { Text("Clear codes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun severityColour(severity: DtcSeverity) = when (severity) {
    DtcSeverity.CRITICAL -> Danger
    DtcSeverity.SERIOUS -> Danger
    DtcSeverity.MODERATE -> Warning
    DtcSeverity.MINOR -> Info
    DtcSeverity.UNKNOWN -> Info
}

/**
 * Hands the saved report to whatever the user wants to send it with.
 *
 * Plain text rather than an attachment-only type so that mail clients and messengers
 * offer to inline it — a mechanic is far more likely to read a pasted report than to
 * open an attachment from a stranger's phone.
 */
private fun shareReport(context: android.content.Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Vehicle diagnostic report")
        putExtra(Intent.EXTRA_TEXT, runCatching { file.readText() }.getOrDefault(""))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share diagnostic report"))
}
