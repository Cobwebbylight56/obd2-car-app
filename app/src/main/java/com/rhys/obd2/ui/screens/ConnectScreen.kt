package com.rhys.obd2.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.transport.AdapterDevice
import com.rhys.obd2.transport.DeviceRelevance
import com.rhys.obd2.transport.WifiTransport
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.color
import com.rhys.obd2.ui.theme.colors

@Composable
fun ConnectScreen(
    viewModel: ObdViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onConnected: () -> Unit,
) {
    val connection by viewModel.connectionState.collectAsState()
    val devices by viewModel.scanResults.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    var showWifiDialog by remember { mutableStateOf(false) }
    var showAllDevices by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // A BLE scan anywhere near other people hears dozens of beacons, earbuds and
    // televisions, none of which can possibly be an OBD adapter. Only adapters and things
    // the owner has actually paired are shown; the rest stay one tap away rather than
    // being discarded, because an unbranded dongle that advertises nothing recognisable
    // would otherwise be unreachable with no clue as to why.
    val candidates = remember(devices) {
        devices.filter { it.relevance != DeviceRelevance.OTHER }
    }
    val otherDevices = remember(devices) {
        devices.filter { it.relevance == DeviceRelevance.OTHER }
    }

    val autoConnect by viewModel.settings.autoConnect.collectAsState()

    // Ask on first appearance; the scan itself waits for the answer.
    LaunchedEffect(Unit) { onRequestPermissions() }

    DisposableEffect(permissionsGranted) {
        if (permissionsGranted) viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }

    // Coming back from Android's Bluetooth settings is the moment a newly paired classic
    // adapter becomes visible, and nothing else would prompt a re-read.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, permissionsGranted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionsGranted) {
                viewModel.refreshPairedDevices()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Reconnect to the adapter used last time, once, if the user has asked for that.
    // Worth doing automatically: the usual sequence is plug in, start engine, open app,
    // and there is nothing useful to choose between on a phone that only ever sees one
    // dongle.
    LaunchedEffect(permissionsGranted, connection) {
        if (autoConnect && permissionsGranted && connection is ConnectionState.Disconnected) {
            viewModel.autoConnectOnce()
        }
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
                if (!permissionsGranted) {
                    Text(
                        "OpenOBD needs Bluetooth permission to find adapters. Grant it when " +
                            "Android asks, or from the app's page in Android settings if you " +
                            "already dismissed the prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (candidates.isEmpty()) {
                            Text(
                                if (scanning) {
                                    "Looking for adapters. Make sure the dongle is plugged into the OBD " +
                                        "port and the ignition is on — most have no power otherwise.\n\n" +
                                        "If yours is a classic Bluetooth dongle — the sort sold as " +
                                        "\"Android and Windows only\" — it will not appear here until it " +
                                        "has been paired in Android's Bluetooth settings. Use the button " +
                                        "below, pair it (the PIN is almost always 1234 or 0000), then " +
                                        "come back."
                                } else {
                                    "Nothing found. Check the adapter is plugged in and Bluetooth is on. " +
                                        "Classic Bluetooth dongles must be paired in Android's settings first."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        candidates.forEach { device ->
                            DeviceRow(
                                name = device.name,
                                subtitle = deviceSubtitle(device),
                                highlight = device.relevance == DeviceRelevance.LIKELY,
                                onClick = { viewModel.connect(device) },
                            )
                        }

                        if (otherDevices.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (showAllDevices) {
                                    "Hide the other ${otherDevices.size} Bluetooth devices"
                                } else {
                                    "${otherDevices.size} other Bluetooth device" +
                                        (if (otherDevices.size == 1) "" else "s") +
                                        " nearby — show anyway"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Tone.ACCENT.color(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showAllDevices = !showAllDevices }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            )

                            if (showAllDevices) {
                                Text(
                                    "These are everything else your phone can hear — beacons in shops, " +
                                        "other people's headphones, televisions. An OBD adapter almost " +
                                        "always names itself, so anything listed as unnamed is very " +
                                        "unlikely to be one. Shown in case yours is unusual.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                otherDevices.forEach { device ->
                                    DeviceRow(
                                        name = device.name,
                                        subtitle = deviceSubtitle(device),
                                        highlight = false,
                                        onClick = { viewModel.connect(device) },
                                    )
                                }
                            }
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
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pair a classic adapter")
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
                tone = Tone.INFO,
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
                    StatusPill("Not connected", Tone.NEUTRAL)
                }
            }
        }

        is ConnectionState.Connecting -> {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(connection.step, style = MaterialTheme.typography.bodyMedium)
                    }
                    // Connecting to a classic dongle can legitimately take twenty seconds
                    // across its fallbacks. Without a way out, a slow attempt and a hung
                    // one look identical, and the only recourse was force-quitting.
                    OutlinedButton(
                        onClick = { viewModel.cancelConnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel") }
                }
            }
        }

        is ConnectionState.Connected -> {
            SectionCard(title = "Connected", subtitle = connection.deviceName) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill("Talking to the car", Tone.ACCENT)
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
                    StatusPill("Failed", Tone.DANGER)
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
                    if (highlight) Tone.ACCENT.colors().container
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = if (highlight) Tone.ACCENT.color() else MaterialTheme.colorScheme.onSurfaceVariant,
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
            StatusPill("Likely", Tone.ACCENT)
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

private fun deviceSubtitle(device: AdapterDevice): String = buildString {
    append(device.kind.label)
    device.rssi?.let { append(" · ${signalLabel(it)}") }
    if (device.bonded) append(" · paired")
}

/** Turns RSSI into something meaningful. dBm means nothing to most people. */
private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "strong signal"
    rssi >= -75 -> "good signal"
    rssi >= -88 -> "weak signal"
    else -> "very weak"
}
