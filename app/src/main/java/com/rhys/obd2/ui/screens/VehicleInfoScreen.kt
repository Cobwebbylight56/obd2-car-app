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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.InfoRow
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.color

@Composable
fun VehicleInfoScreen(viewModel: ObdViewModel, onBack: () -> Unit) {
    val connection by viewModel.connectionState.collectAsState()
    val info by viewModel.vehicleInfo.collectAsState()
    val supported by viewModel.supportedPids.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val clipboard = LocalClipboardManager.current

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
                Text(
                    "Vehicle & adapter",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (busy != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        if (connection !is ConnectionState.Connected) {
            item { ExplainerCard(tone = Tone.INFO, text = "Connect to read the vehicle's identification.") }
            return@LazyColumn
        }

        item {
            Button(
                onClick = { viewModel.refreshVehicleInfo() },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Read vehicle information")
            }
        }

        val current = info

        current?.vin?.let { vin ->
            item {
                SectionCard(
                    title = "Vehicle identification number",
                    trailing = {
                        Text(
                            "Copy",
                            color = Tone.ACCENT.color(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { clipboard.setText(AnnotatedString(vin)) }
                                .padding(4.dp),
                        )
                    },
                ) {
                    Column {
                        Text(
                            vin,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        val details = current.decodedVin
                        if (details != null) {
                            Spacer(Modifier.height(10.dp))
                            InfoRow("Manufacturer code", details.worldManufacturerId)
                            details.manufacturer?.let { InfoRow("Manufacturer", it) }
                            InfoRow("Built for", details.region)
                            details.modelYear?.let { InfoRow("Model year", it) }
                            InfoRow("Serial", details.serialNumber)
                            Spacer(Modifier.height(8.dp))
                            StatusPill(
                                if (details.valid) "Check digit valid" else "Check digit doesn't match",
                                if (details.valid) Tone.ACCENT else Tone.WARNING,
                            )
                            if (!details.valid) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Not necessarily a problem — the check digit is mandatory in North " +
                                        "America but often left blank on European cars.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Connection") {
                Column {
                    InfoRow("Adapter", current?.adapter ?: "Unknown")
                    InfoRow("Protocol", current?.protocol ?: "Unknown")
                    InfoRow("Battery voltage", current?.batteryVoltage ?: "—")
                    InfoRow("OBD standard", current?.obdStandard ?: "Not reported")
                    InfoRow("Fuel type", current?.fuelType ?: "Not reported")
                }
            }
        }

        if (!current?.ecuName.isNullOrBlank() ||
            current?.calibrationIds?.isNotEmpty() == true ||
            current?.calibrationVerificationNumbers?.isNotEmpty() == true
        ) {
            item {
                SectionCard(
                    title = "Control module",
                    subtitle = "Software identity of the engine ECU",
                ) {
                    Column {
                        current?.ecuName?.let { InfoRow("Module name", it) }
                        current?.calibrationIds?.forEachIndexed { index, id ->
                            InfoRow(if (index == 0) "Calibration ID" else "Calibration ID ${index + 1}", id)
                        }
                        current?.calibrationVerificationNumbers?.forEachIndexed { index, cvn ->
                            InfoRow(if (index == 0) "Verification number" else "Verification ${index + 1}", cvn)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Calibration IDs identify the exact software in the ECU. Worth noting down: " +
                                "if they change without you authorising a remap or a recall, someone has " +
                                "reflashed the car.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (supported.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Supported parameters",
                    subtitle = "${supported.size} PIDs reported by this car",
                ) {
                    Column {
                        val known = supported.filter { PidRegistry.contains(it) }
                        val unknown = supported.size - known.size
                        Text(
                            known.sorted().joinToString(" ") { "%02X".format(it) },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (unknown > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "$unknown further PIDs are reported that this app has no decoder for. " +
                                    "They're mostly manufacturer extensions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (current?.vin == null) {
            item {
                ExplainerCard(
                    tone = Tone.INFO,
                    text = "No VIN reported. Reporting the VIN over OBD only became mandatory around " +
                        "2008, so plenty of older cars simply don't have it — everything else on this " +
                        "screen still works.",
                )
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}
