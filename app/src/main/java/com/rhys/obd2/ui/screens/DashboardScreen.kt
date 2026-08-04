package com.rhys.obd2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.data.UnitSystem
import com.rhys.obd2.data.Units
import com.rhys.obd2.obd.Pid
import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.Gauge
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.Sparkline
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.color
import com.rhys.obd2.ui.theme.colors

@Composable
fun DashboardScreen(
    viewModel: ObdViewModel,
    onOpenConnect: () -> Unit,
    onOpenCodes: () -> Unit,
) {
    val connection by viewModel.connectionState.collectAsState()
    val live by viewModel.liveData.collectAsState()
    val units by viewModel.settings.units.collectAsState()
    val dashboardPids by viewModel.settings.dashboardPids.collectAsState()
    val rate by viewModel.pollRate.collectAsState()
    val codes by viewModel.dtcs.collectAsState()
    val logging by viewModel.isLogging.collectAsState()
    val trip by viewModel.tripStats.collectAsState()

    // Polling belongs to whichever screen is showing, so leaving the dashboard hands the
    // adapter back rather than competing with the next screen's requests.
    DisposableEffect(connection, dashboardPids) {
        if (connection is ConnectionState.Connected) viewModel.startDashboardPolling()
        onDispose { }
    }

    if (connection !is ConnectionState.Connected) {
        NotConnected(onOpenConnect)
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            (connection as ConnectionState.Connected).deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "%.1f readings/sec".format(rate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    RecordButton(logging, viewModel)
                }
            }
        }

        if (codes != null && codes!!.stored.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) {
                FaultBanner(codes!!.stored.size, onOpenCodes)
            }
        }

        if (logging && trip != null) {
            item(span = { GridItemSpan(2) }) {
                TripSummary(trip!!, units)
            }
        }

        val gaugePids = dashboardPids.mapNotNull { PidRegistry[it] }
        items(gaugePids, key = { it.id }) { pid ->
            val value = live[pid.id]
            GaugeTile(pid, value?.primary?.value, value?.history ?: emptyList(), units)
        }

        item(span = { GridItemSpan(2) }) {
            Text(
                "Change which gauges appear from Live data — long-press any row to pin it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun GaugeTile(
    pid: Pid,
    rawValue: Double?,
    history: List<Float>,
    units: UnitSystem,
) {
    val converted = rawValue?.let { Units.convert(it, pid.unit, units) }
    val displayUnit = converted?.unit ?: Units.convert(0.0, pid.unit, units).unit
    val text = converted?.let { Units.format(it.value, it.unit) } ?: "—"

    // Thresholds are set from the value's own meaning, not a blanket percentage: 100 °C
    // coolant is fine, 110 °C is not, and neither is anywhere near the PID's 215 °C max.
    val (warn, danger) = thresholds(pid.id)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Gauge(
                value = rawValue?.toFloat() ?: pid.min.toFloat(),
                min = pid.min.toFloat(),
                max = gaugeMax(pid).toFloat(),
                label = pid.name,
                unit = displayUnit,
                valueText = text,
                warningThreshold = warn,
                dangerThreshold = danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.15f),
            )
            if (history.size > 2) {
                Sparkline(
                    points = history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .padding(top = 4.dp),
                    tone = Tone.ACCENT,
                )
            }
        }
    }
}

/**
 * The full standard range makes a poor gauge scale — vehicle speed goes to 255 km/h and
 * coolant to 215 °C, so real values sit in the first third and never move visibly. These
 * are the ranges a driver actually operates in.
 */
private fun gaugeMax(pid: Pid): Double = when (pid.id) {
    0x0C -> 7000.0    // RPM
    0x0D -> 180.0     // speed
    0x05 -> 130.0     // coolant
    0x5C -> 150.0     // oil temp
    0x0F -> 80.0      // intake air
    0x42 -> 16.0      // control module voltage
    0x0B -> 255.0     // MAP
    0x10 -> 200.0     // MAF
    0xA6 -> 300000.0  // odometer
    else -> pid.max
}

private fun thresholds(pidId: Int): Pair<Float?, Float?> = when (pidId) {
    0x05 -> 105f to 115f          // coolant temperature
    0x5C -> 120f to 135f          // oil temperature
    0x0C -> 5500f to 6500f        // RPM
    0x42 -> null to null          // voltage needs a low-side check the gauge can't express
    else -> null to null
}

@Composable
private fun RecordButton(logging: Boolean, viewModel: ObdViewModel) {
    val colour = if (logging) Tone.DANGER.color() else Tone.ACCENT.color()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colour.copy(alpha = 0.15f))
            .clickable {
                if (logging) viewModel.stopLogging() else viewModel.startLogging()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (logging) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = colour,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (logging) "Stop" else "Record",
            color = colour,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FaultBanner(count: Int, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tone.DANGER.colors().container)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Tone.DANGER.color(), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$count stored fault code${if (count == 1) "" else "s"}",
                fontWeight = FontWeight.SemiBold,
                color = Tone.DANGER.color(),
            )
            Text(
                "Tap to see what they mean",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TripSummary(stats: com.rhys.obd2.data.TripStats, units: UnitSystem) {
    val distance = Units.convert(stats.distanceKm, "km", units)
    val maxSpeed = Units.convert(stats.maxSpeed, "km/h", units)

    SectionCard(title = "Recording", subtitle = formatDuration(stats.durationMs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric("Distance", "${Units.format(distance.value, distance.unit)} ${distance.unit}")
            Metric("Top speed", "${Units.format(maxSpeed.value, maxSpeed.unit)} ${maxSpeed.unit}")
            stats.economyL100km?.let {
                // 282.481 is the imperial-gallon constant; the US gallon is 235.215. A UK
                // driver reads imperial mpg, so UK and IMPERIAL are not the same number.
                val economy = when (units) {
                    UnitSystem.UK -> "%.1f mpg".format(282.481 / it)
                    UnitSystem.IMPERIAL -> "%.1f mpg".format(235.215 / it)
                    UnitSystem.METRIC -> "%.1f L/100km".format(it)
                }
                Metric("Economy", economy)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun NotConnected(onOpenConnect: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StatusPill("Not connected", Tone.NEUTRAL)
            Spacer(Modifier.height(12.dp))
            Text(
                "Connect an adapter to see live data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Open connection screen",
                color = Tone.ACCENT.color(),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onOpenConnect),
            )
        }
    }
}
