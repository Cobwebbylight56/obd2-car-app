package com.rhys.obd2.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhys.obd2.data.TripLog
import com.rhys.obd2.ui.components.ChartLegendRow
import com.rhys.obd2.ui.components.ChartSeries
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.InfoRow
import com.rhys.obd2.ui.components.LineChart
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.theme.Accent
import com.rhys.obd2.ui.theme.Danger
import com.rhys.obd2.ui.theme.Info
import com.rhys.obd2.ui.theme.Warning
import java.io.File

/**
 * Reviews a recorded trip.
 *
 * The reason to record at all is to catch something that doesn't happen while you're
 * looking at the gauges, which means the review is the useful half. Plotting two or three
 * parameters together is what turns a log into a diagnosis: a misfire that only appears
 * once coolant temperature passes 90 °C is obvious on a chart and invisible in a
 * spreadsheet column.
 */
@Composable
fun TripDetailScreen(file: File, onBack: () -> Unit) {
    val log = remember(file.path) { TripLog.parse(file) }

    var selected by remember(file.path) {
        // Start with the two most-plotted parameters if they're present, so the screen
        // opens showing something rather than an empty chart and a shrug.
        mutableStateOf(
            log?.populated.orEmpty()
                .filter { it.label.contains("RPM") || it.label.contains("speed", ignoreCase = true) }
                .map { it.name }
                .take(2)
                .ifEmpty { log?.populated.orEmpty().take(1).map { it.name } }
                .toSet()
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Trip", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    log?.let { "${it.sampleCount} samples over ${formatDuration(it.durationSeconds)}" }
                        ?: file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (log == null || log.populated.isEmpty()) {
            ExplainerCard(
                accent = Info,
                text = "This log has no readable data in it. That usually means the recording was " +
                    "stopped within a second or two of starting, before any rows were written.",
                modifier = Modifier.padding(16.dp),
            )
            return
        }

        val chartSeries = log.populated
            .filter { it.name in selected }
            .mapIndexed { index, s ->
                ChartSeries(
                    label = s.label,
                    unit = s.unit,
                    values = s.values,
                    colour = SERIES_COLOURS[index % SERIES_COLOURS.size],
                )
            }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(
                    title = "Chart",
                    subtitle = "Each line is scaled to its own range, so compare shapes rather than heights",
                ) {
                    Column {
                        LineChart(
                            series = chartSeries,
                            xValues = log.elapsedSeconds,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        chartSeries.forEach { ChartLegendRow(it) }
                    }
                }
            }

            item {
                Text(
                    "Parameters",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(log.populated) { s ->
                        FilterChip(
                            selected = s.name in selected,
                            onClick = {
                                selected = if (s.name in selected) {
                                    selected - s.name
                                } else {
                                    // Cap it: past four lines the chart stops being readable.
                                    (selected + s.name).toList().takeLast(4).toSet()
                                }
                            },
                            label = { Text(s.label) },
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Summary") {
                    Column {
                        InfoRow("Duration", formatDuration(log.durationSeconds))
                        InfoRow("Samples", log.sampleCount.toString())
                        log.populated.forEach { s ->
                            val mean = s.mean ?: return@forEach
                            InfoRow(
                                s.label,
                                "min ${fmt(s.min)}  avg ${fmt(mean)}  max ${fmt(s.max)} ${s.unit}".trim(),
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private val SERIES_COLOURS = listOf(Accent, Info, Warning, Danger)

private fun fmt(value: Float?): String = when {
    value == null -> "—"
    kotlin.math.abs(value) >= 1000 -> "%.0f".format(java.util.Locale.UK, value)
    kotlin.math.abs(value) >= 10 -> "%.1f".format(java.util.Locale.UK, value)
    else -> "%.2f".format(java.util.Locale.UK, value)
}

private fun formatDuration(seconds: Float): String {
    val total = seconds.toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}
