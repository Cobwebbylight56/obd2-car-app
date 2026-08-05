package com.rhys.obd2.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhys.obd2.data.UnitSystem
import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.SectionCard
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role

@Composable
fun SettingsScreen(viewModel: ObdViewModel, onBack: () -> Unit) {
    val units by viewModel.settings.units.collectAsState()
    val keepScreenOn by viewModel.settings.keepScreenOn.collectAsState()
    val autoConnect by viewModel.settings.autoConnect.collectAsState()
    val showOdometer by viewModel.settings.showOdometer.collectAsState()
    val dashboardPids by viewModel.settings.dashboardPids.collectAsState()
    val supported by viewModel.supportedPids.collectAsState()

    // The toggle can be on and the card still absent, because this car has no mileage to
    // report. Saying so here is the difference between a considered decision and an
    // apparently broken switch.
    val odometerReported = supported.isEmpty() || 0xA6 in supported

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }

        item {
            SectionCard(title = "Units") {
                Column {
                    UnitSystem.entries.forEach { system ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = units == system,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.settings.setUnits(system) },
                                )
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = units == system, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(system.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    system.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        "The car always reports metric — imperial values are converted for display only, " +
                            "and logs are written in the car's own units so they stay comparable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item {
            SectionCard(title = "Behaviour") {
                Column {
                    ToggleRow(
                        "Keep screen on",
                        "Stops the phone sleeping mid-drive, which would truncate a recording.",
                        keepScreenOn,
                    ) { viewModel.settings.setKeepScreenOn(it) }
                    ToggleRow(
                        "Reconnect to last adapter",
                        "Connects to the adapter you used last as soon as the app opens, so you " +
                            "don't have to pick it every time. Disconnecting by hand won't trigger it.",
                        autoConnect,
                    ) { viewModel.settings.setAutoConnect(it) }
                    ToggleRow(
                        "Show odometer on the dashboard",
                        when {
                            odometerReported ->
                                "Pins total mileage above the gauges, and warns you there " +
                                    "if a reading ever comes back lower than an earlier one."
                            showOdometer ->
                                "On, but nothing to show — this car doesn't report its " +
                                    "mileage over OBD-II. The card will appear by itself on " +
                                    "a car that does."
                            else ->
                                "Off. This car doesn't report its mileage over OBD-II " +
                                    "anyway, so turning it on would show nothing."
                        },
                        showOdometer,
                    ) { viewModel.settings.setShowOdometer(it) }
                }
            }
        }

        item {
            SectionCard(
                title = "Dashboard gauges",
                subtitle = "${dashboardPids.size} of 8 slots used",
            ) {
                Column {
                    dashboardPids.mapNotNull { PidRegistry[it] }.forEach { pid ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(pid.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Remove",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .clickable {
                                        viewModel.setDashboardPids(dashboardPids - pid.id)
                                    }
                                    .padding(4.dp),
                            )
                        }
                    }
                    Text(
                        "Add gauges by tapping the pin icon on the Live data screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (supported.isNotEmpty()) {
            item {
                SectionCard(title = "This car") {
                    Text(
                        "${supported.size} parameters detected. If a gauge you want isn't in the Live " +
                            "data list, this car doesn't report it over OBD-II — that's a property of the " +
                            "vehicle, not the adapter or the app.",
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
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
