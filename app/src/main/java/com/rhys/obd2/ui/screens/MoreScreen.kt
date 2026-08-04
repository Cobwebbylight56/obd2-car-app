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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.Routes
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.color
import com.rhys.obd2.ui.components.ScreenHeader

@Composable
fun MoreScreen(viewModel: ObdViewModel, onNavigate: (String) -> Unit) {
    val connection by viewModel.connectionState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(title = "More")
        }

        item {
            SectionCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (val state = connection) {
                                    is ConnectionState.Connected -> state.deviceName
                                    is ConnectionState.Connecting -> state.step
                                    is ConnectionState.Failed -> "Connection failed"
                                    ConnectionState.Disconnected -> "Not connected"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            (connection as? ConnectionState.Connected)?.protocol?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        StatusPill(
                            if (connection is ConnectionState.Connected) "Online" else "Offline",
                            if (connection is ConnectionState.Connected) Tone.ACCENT else Tone.INFO,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onNavigate(Routes.CONNECT) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Adapters") }
                        if (connection is ConnectionState.Connected) {
                            OutlinedButton(
                                onClick = { viewModel.disconnect() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.LinkOff, contentDescription = null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Diagnostics") {
                Column {
                    MenuRow(
                        Icons.Filled.DirectionsCar,
                        "Vehicle & adapter",
                        "VIN, ECU calibration, protocol, supported parameters",
                    ) { onNavigate(Routes.VEHICLE) }
                    MenuRow(
                        Icons.Filled.Science,
                        "On-board test results",
                        "Service 06 — see how close each component is to its limit",
                    ) { onNavigate(Routes.TESTS) }
                    MenuRow(
                        Icons.Filled.Timeline,
                        "Recorded trips",
                        "Browse and export the CSV logs you've recorded",
                    ) { onNavigate(Routes.LOGS) }
                    MenuRow(
                        Icons.Filled.Terminal,
                        "Terminal",
                        "Send raw AT and OBD commands to the adapter",
                    ) { onNavigate(Routes.TERMINAL) }
                    MenuRow(
                        Icons.Filled.Search,
                        "Look up a code",
                        "Search the offline code database — no car or adapter needed",
                    ) { onNavigate(Routes.LOOKUP) }
                }
            }
        }

        item {
            SectionCard(title = "App") {
                Column {
                    MenuRow(
                        Icons.Filled.Settings,
                        "Settings",
                        "Units, screen behaviour, dashboard layout",
                    ) { onNavigate(Routes.SETTINGS) }
                    MenuRow(
                        Icons.Filled.Palette,
                        "Design lab",
                        "Every component and state, in both themes",
                    ) { onNavigate(Routes.DESIGN_LAB) }
                }
            }
        }

        item {
            SectionCard(title = "About") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "OpenOBD talks to any ELM327-compatible adapter over Bluetooth LE, classic " +
                            "Bluetooth or Wi-Fi, and implements OBD-II services 01 through 0A: live data, " +
                            "freeze frame, fault codes, clearing, on-board test results and vehicle " +
                            "information.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "It reads and clears standard emissions-related codes. It does not do " +
                            "manufacturer-specific functions like ABS or airbag modules, key coding, or " +
                            "service resets — those need protocols outside the OBD-II standard and differ " +
                            "per marque.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = Tone.ACCENT.color())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
