package com.rhys.obd2.ui.screens

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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.transport.DeviceScanner
import com.rhys.obd2.transport.WifiTransport
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Accent
import com.rhys.obd2.ui.theme.Danger
import com.rhys.obd2.ui.theme.Info

@Composable
fun ConnectScreen(
    viewModel: ObdViewModel,
    onRequestPermissions: () -> Unit,
    onConnected: () -> Unit,
) {
    val connection by viewModel.connectionState.collectAsState()
    val devices by viewModel.scanResults.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    var showWifiDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onRequestPermissions()
        viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(16.dp)) }

        item {
            Column {
                Text(
                    "OpenOBD",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Connect to your car's diagnostic port",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { ConnectionStatusCard(connection, viewModel, onConnected) }

        viewModel.settings.lastDevice?.let { saved ->
            if (connection !is ConnectionState.Connected) {
                item {
                    SectionCard(title = "Last used") {
                        DeviceRow(
                            name = saved.name,
                            subtitle = "${saved.kind.label} · ${saved.address}",
                            highlight = true,
                            onClick = { viewModel.connectToLastDevice() },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Nearby adapters",
                subtitle = if (scanning) "Scanning…" else "Tap to refresh",
                trailing = {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Rescan",
                            modifier = Modifier.clickable { viewModel.startScan() },
                        )
                    }
                },
            ) {
                if (devices.isEmpty()) {
                    Text(
                        if (scanning) {
                            "Looking for Bluetooth adapters. Make sure the dongle is plugged into the " +
                                "OBD port and the ignition is on — most adapters have no power otherwise."
                        } else {
                            "Nothing found yet. Check the adapter is plugged in and Bluetooth is on."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        devices.forEach { device ->
                            DeviceRow(
                                name = device.name,
                                subtitle = buildString {
                                    append(device.kind.label)
                                    device.rssi?.let { append(" · ${signalLabel(it)}") }
                                    if (device.bonded) append(" · paired")
                                },
                                highlight = DeviceScanner.looksLikeObdAdapter(device.name),
                                onClick = { viewModel.connect(device) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Other ways to connect") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showWifiDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Wi-Fi adapter")
                    }
                    OutlinedButton(
                        onClick = { viewModel.connectDemo() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.DirectionsCar, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Demo mode — try it without a car")
                    }
                }
            }
        }

        item {
            ExplainerCard(
                accent = Info,
                text = "First time? Plug the adapter into the OBD-II socket — usually under the " +
                    "dashboard on the driver's side, near the pedals or above them. Turn the ignition " +
                    "to position II so the dashboard lights come on; the engine doesn't need to be " +
                    "running for most functions. If your adapter needs pairing, do that in Android's " +
                    "Bluetooth settings first — the usual PIN is 1234 or 0000.",
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showWifiDialog) {
        WifiDialog(
            onDismiss = { showWifiDialog = false },
            onConnect = { host, port ->
                showWifiDialog = false
                viewModel.connectWifi(host, port)
            },
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    connection: ConnectionState,
    viewModel: ObdViewModel,
    onConnected: () -> Unit,
) {
    when (connection) {
        is ConnectionState.Disconnected -> {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill("Not connected", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        is ConnectionState.Connecting -> {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(connection.step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is ConnectionState.Connected -> {
            SectionCard(title = "Connected", subtitle = connection.deviceName) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill("Talking to the car", Accent)
                    connection.protocol?.let {
                        Text(
                            "Protocol: $it",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    connection.adapter?.let {
                        Text(
                            "Adapter: $it",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConnected) { Text("Open dashboard") }
                        OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
                    }
                }
            }
        }

        is ConnectionState.Failed -> {
            SectionCard(title = "Couldn't connect", subtitle = connection.deviceName) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusPill("Failed", Danger)
                    Text(connection.message, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.connectToLastDevice() }) { Text("Try again") }
                        OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Reset") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    name: String,
    subtitle: String,
    highlight: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (highlight) Accent.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = if (highlight) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (highlight) {
            StatusPill("Likely", Accent)
        }
    }
}

@Composable
private fun WifiDialog(onDismiss: () -> Unit, onConnect: (String, Int) -> Unit) {
    var host by remember { mutableStateOf(WifiTransport.DEFAULT_HOST) }
    var port by remember { mutableStateOf(WifiTransport.DEFAULT_PORT.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi adapter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Join the adapter's own Wi-Fi network in Android settings first. Most of them " +
                        "use the address below; check the label on the dongle if it doesn't respond.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Address") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onConnect(host.trim(), port.toIntOrNull() ?: WifiTransport.DEFAULT_PORT)
            }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Turns RSSI into something meaningful. dBm means nothing to most people. */
private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "strong signal"
    rssi >= -75 -> "good signal"
    rssi >= -88 -> "weak signal"
    else -> "very weak"
}
