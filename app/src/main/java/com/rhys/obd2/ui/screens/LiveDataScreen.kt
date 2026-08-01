package com.rhys.obd2.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.data.LiveValue
import com.rhys.obd2.data.UnitSystem
import com.rhys.obd2.data.Units
import com.rhys.obd2.obd.Pid
import com.rhys.obd2.obd.PidCategory
import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.Sparkline
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Accent
import com.rhys.obd2.ui.theme.Info

/**
 * Every parameter the car reports, live.
 *
 * The adapter can only poll so fast, so the selection here is not cosmetic: reading four
 * PIDs gives roughly four times the update rate of reading sixteen. The screen therefore
 * only polls what's actually selected rather than everything it can see.
 */
@Composable
fun LiveDataScreen(viewModel: ObdViewModel) {
    val connection by viewModel.connectionState.collectAsState()
    val live by viewModel.liveData.collectAsState()
    val supported by viewModel.supportedPids.collectAsState()
    val units by viewModel.settings.units.collectAsState()
    val dashboardPids by viewModel.settings.dashboardPids.collectAsState()
    val rate by viewModel.pollRate.collectAsState()

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<PidCategory?>(null) }
    var selected by remember { mutableStateOf(dashboardPids.toSet()) }

    // Poll exactly what the user has ticked. Leaving the screen stops it so the dashboard
    // gets the adapter back at full rate.
    DisposableEffect(selected, connection) {
        if (connection is ConnectionState.Connected && selected.isNotEmpty()) {
            viewModel.setPollTargets(selected.toList())
        }
        onDispose { }
    }

    val available = remember(supported) {
        val pool = if (supported.isEmpty()) {
            PidRegistry.ALL.filter { it.featured }
        } else {
            PidRegistry.ALL.filter { it.id in supported }
        }
        pool.filter { PidRegistry.isPollable(it.id) }
    }

    val visible = remember(available, query, category) {
        available.filter { pid ->
            (category == null || pid.category == category) &&
                (query.isBlank() || pid.name.contains(query, ignoreCase = true) ||
                    pid.hex.contains(query, ignoreCase = true))
        }
    }

    val categories = remember(available) { available.map { it.category }.distinct().sortedBy { it.label } }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Live data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${available.size} parameters available · ${selected.size} polling",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill("%.1f/s".format(rate), if (rate > 2) Accent else Info)
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search parameters") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        FilterChip(
                            selected = category == null,
                            onClick = { category = null },
                            label = { Text("All") },
                        )
                    }
                    items(categories) { entry ->
                        FilterChip(
                            selected = category == entry,
                            onClick = { category = if (category == entry) null else entry },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }

            if (connection !is ConnectionState.Connected) {
                item {
                    ExplainerCard(
                        accent = Info,
                        text = "Not connected. The list below shows the common parameters; connect an " +
                            "adapter and the app will replace it with exactly what your car reports.",
                    )
                }
            } else if (selected.isEmpty()) {
                item {
                    ExplainerCard(
                        accent = Info,
                        text = "Tap parameters to start reading them. Fewer selected means each one " +
                            "updates faster — the adapter reads them one at a time.",
                    )
                }
            }

            items(visible, key = { it.id }) { pid ->
                LiveRow(
                    pid = pid,
                    value = live[pid.id],
                    units = units,
                    isSelected = pid.id in selected,
                    isPinned = pid.id in dashboardPids,
                    onToggle = {
                        selected = if (pid.id in selected) selected - pid.id else selected + pid.id
                    },
                    onPin = {
                        val current = dashboardPids
                        val updated = if (pid.id in current) current - pid.id else (current + pid.id).takeLast(8)
                        viewModel.setDashboardPids(updated)
                    },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LiveRow(
    pid: Pid,
    value: LiveValue?,
    units: UnitSystem,
    isSelected: Boolean,
    isPinned: Boolean,
    onToggle: () -> Unit,
    onPin: () -> Unit,
) {
    val background = if (isSelected) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onToggle)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    pid.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "PID ${pid.hex} · ${pid.category.label}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                val readings = value?.readings.orEmpty()
                if (readings.isEmpty()) {
                    Text(
                        if (isSelected) "…" else "—",
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    readings.take(3).forEach { reading ->
                        val converted = Units.convert(reading.value, reading.unit, units)
                        val display = reading.text
                            ?: "${Units.format(converted.value, converted.unit)} ${converted.unit}".trim()
                        Text(
                            display,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (readings.size > 1) 13.sp else 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (readings.size > 1) {
                            Text(
                                reading.label,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.PushPin,
                contentDescription = if (isPinned) "Unpin from dashboard" else "Pin to dashboard",
                tint = if (isPinned) Accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onPin),
            )
        }

        val history = value?.history.orEmpty()
        if (isSelected && history.size > 3) {
            Sparkline(
                points = history,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .padding(top = 6.dp),
            )
        }
    }
}
