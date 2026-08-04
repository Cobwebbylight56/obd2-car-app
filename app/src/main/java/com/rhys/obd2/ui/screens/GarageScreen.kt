package com.rhys.obd2.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.obd2.data.EventType
import com.rhys.obd2.data.Vehicle
import com.rhys.obd2.data.VehicleHistoryEvent
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.InfoRow
import com.rhys.obd2.ui.components.RowIcon
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.color
import com.rhys.obd2.ui.components.ScreenHeader

/**
 * Per-car history that survives the car's own memory.
 *
 * Clearing fault codes wipes the ECU. Everything recorded here stays, so a fault that has
 * come back four times looks different from one that has just appeared — which is usually
 * the difference between guessing and knowing.
 */
@Composable
fun GarageScreen(viewModel: ObdViewModel) {
    val vehicles by viewModel.vehicles.collectAsState()
    val current by viewModel.currentVehicle.collectAsState()

    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = vehicles.firstOrNull { it.key == (selectedKey ?: current?.key) }
        ?: vehicles.firstOrNull()

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var clearingHistory by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<VehicleHistoryEvent?>(null) }
    val revision by viewModel.historyRevision.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Garage",
                subtitle = if (vehicles.isEmpty()) "No cars recorded yet"
                else "${vehicles.size} car${if (vehicles.size == 1) "" else "s"}",
            )
        }

        if (vehicles.isEmpty()) {
            item {
                ExplainerCard(
                    tone = Tone.INFO,
                    text = "Connect to a car and it gets added here automatically, identified by its " +
                        "VIN. From then on every fault code, unusual reading and recorded trip is " +
                        "kept with a date and time — including codes you later clear, which the car " +
                        "itself forgets the moment you erase them.",
                )
            }
            return@LazyColumn
        }

        // Car picker, only when there's a choice to make.
        if (vehicles.size > 1) {
            item {
                SectionCard(title = "Cars") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        vehicles.forEach { vehicle ->
                            VehicleRow(
                                vehicle = vehicle,
                                isSelected = vehicle.key == selected?.key,
                                isConnected = vehicle.key == current?.key,
                                onClick = { selectedKey = vehicle.key },
                            )
                        }
                    }
                }
            }
        }

        val vehicle = selected ?: return@LazyColumn
        // Reading the revision here is what makes a deletion show up: the events come from
        // a file, so nothing else would tell Compose the list has changed.
        val events = run { revision; viewModel.historyFor(vehicle.key) }

        item {
            SectionCard(
                title = vehicle.name,
                subtitle = vehicle.vin ?: "No VIN reported by this car",
                trailing = {
                    Row {
                        // IconButton rather than a sized Icon: it is 48dp regardless of the
                        // glyph inside it, announces itself as a button, and draws a ripple
                        // that matches its real hit area rather than the icon's outline.
                        IconButton(onClick = { renaming = true }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Rename this car",
                                tint = Tone.ACCENT.color(),
                            )
                        }
                        IconButton(onClick = { deleting = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete this car and its history",
                                tint = Tone.DANGER.color(),
                            )
                        }
                    }
                },
            ) {
                Column {
                    if (vehicle.key == current?.key) {
                        StatusPill("Plugged in now", Tone.ACCENT)
                        Spacer(Modifier.height(8.dp))
                    }
                    vehicle.manufacturer?.let { InfoRow("Manufacturer", it) }
                    vehicle.modelYear?.let { InfoRow("Model year", it) }
                    InfoRow("First seen", formatDate(vehicle.firstSeen))
                    InfoRow("Last seen", formatDate(vehicle.lastSeen))
                    InfoRow("History", "${events.size} entries · ${viewModel.historySize(vehicle.key)}")
                    if (events.isNotEmpty()) {
                        TextButton(
                            onClick = { clearingHistory = true },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Tone.DANGER.color(),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Clear all history", color = Tone.DANGER.color())
                        }
                    }

                    if (!vehicle.identifiedByVin) {
                        Spacer(Modifier.height(10.dp))
                        ExplainerCard(
                            tone = Tone.WARNING,
                            text = "This car doesn't report a VIN — common before about 2008 — so it's " +
                                "identified by its ECU calibration or the adapter used. Reading two " +
                                "different VIN-less cars with the same adapter could merge their " +
                                "histories.",
                        )
                    }
                }
            }
        }

        item {
            Text(
                "History",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (events.isEmpty()) {
            item {
                ExplainerCard(
                    tone = Tone.INFO,
                    text = "Nothing recorded for this car yet. Fault codes, unusual readings and " +
                        "recorded trips will appear here as they happen.",
                )
            }
        }

        for (event in events) {
            item(key = "${event.timestamp}-${event.title}") { HistoryCard(event, onDelete = { deletingEvent = event }) }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }

    if (renaming && selected != null) {
        RenameDialog(
            current = selected.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                viewModel.renameVehicle(selected.key, name)
            },
        )
    }

    deletingEvent?.let { event ->
        val key = selected?.key
        AlertDialog(
            onDismissRequest = { deletingEvent = null },
            title = { Text("Delete this record?") },
            text = {
                Column {
                    Text(event.title, fontWeight = FontWeight.Medium)
                    Text(
                        "${event.type.label} · ${formatDateTime(event.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This removes the entry for good. If it was a fault code, the record " +
                            "that it ever happened goes with it — the car forgot it the moment " +
                            "the codes were cleared.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (key != null) viewModel.deleteHistoryEvent(key, event)
                        deletingEvent = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Tone.DANGER.color()),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingEvent = null }) { Text("Keep") } },
        )
    }

    if (clearingHistory && selected != null) {
        AlertDialog(
            onDismissRequest = { clearingHistory = false },
            title = { Text("Clear all history?") },
            text = {
                Text(
                    "Deletes every recorded entry for ${selected.name} — fault codes, unusual " +
                        "readings and trips — but keeps the car itself and its name.\n\n" +
                        "This is the record the car cannot keep for you. Once it is gone there " +
                        "is no way to tell a fault that has come back four times from one that " +
                        "has just appeared.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clearingHistory = false
                        viewModel.clearHistory(selected.key)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Tone.DANGER.color()),
                ) { Text("Clear history") }
            },
            dismissButton = { TextButton(onClick = { clearingHistory = false }) { Text("Cancel") } },
        )
    }

    if (deleting && selected != null) {
        DeleteDialog(
            vehicle = selected,
            entryCount = viewModel.historyFor(selected.key).size,
            size = viewModel.historySize(selected.key),
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                selectedKey = null
                viewModel.deleteVehicle(selected.key)
            },
        )
    }
}

@Composable
private fun VehicleRow(
    vehicle: Vehicle,
    isSelected: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.DirectionsCar,
            contentDescription = null,
            tint = if (isConnected) Tone.ACCENT.color() else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(vehicle.name, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
            Text(
                vehicle.vin ?: "no VIN",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isConnected) StatusPill("Now", Tone.ACCENT)
    }
}

@Composable
private fun HistoryCard(event: VehicleHistoryEvent, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, tone) = presentation(event.type)

    SectionCard(modifier = Modifier.clickable { expanded = !expanded }) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                RowIcon(icon, tone)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.title, fontWeight = FontWeight.Medium)
                    Text(
                        "${event.type.label} · ${formatDateTime(event.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (event.detail.isNotBlank()) {
                AnimatedVisibility(expanded) {
                    Text(
                        event.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (event.type == EventType.CODES_CLEARED) FontFamily.Monospace
                                     else FontFamily.Default,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, start = 42.dp),
                    )
                }
                if (expanded) {
                    // Only on the expanded card. A delete control on every collapsed row
                    // turns a history you are reading into a minefield of small red
                    // targets, and this list is read far more often than it is pruned.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp, start = 42.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDelete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Tone.DANGER.color(),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Delete this record", color = Tone.DANGER.color())
                        }
                    }
                } else {
                    Text(
                        "Tap for detail",
                        style = MaterialTheme.typography.labelSmall,
                        color = tone.color(),
                        modifier = Modifier.padding(top = 6.dp, start = 50.dp),
                    )
                }
            }
        }
    }
}

/** How an event reads at a glance: an icon for the kind, a tone for how much it matters. */
private fun presentation(type: EventType): Pair<ImageVector, Tone> = when (type) {
    EventType.CONNECTED -> Icons.Filled.Link to Tone.INFO
    EventType.CODES_FOUND -> Icons.Filled.Warning to Tone.DANGER
    EventType.CODES_CLEARED -> Icons.Filled.DeleteSweep to Tone.WARNING
    EventType.ABNORMAL -> Icons.Filled.Bolt to Tone.WARNING
    EventType.TRIP -> Icons.Filled.Timeline to Tone.ACCENT
    EventType.NOTE -> Icons.Filled.DirectionsCar to Tone.INFO
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this car") },
        text = {
            Column {
                Text(
                    "Whatever you'll recognise — \"the Golf\", \"Mum's car\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(
    vehicle: Vehicle,
    entryCount: Int,
    size: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${vehicle.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This removes the car and its entire history — $entryCount entries, $size.")
                Text(
                    "There's no undo, and the codes recorded here are the only copy: the car " +
                        "itself forgot them when they were cleared. If you might want the record " +
                        "later, save a diagnostic report from the Codes screen first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.WARNING.color(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Tone.DANGER.color()),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(millis))

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(Date(millis))
