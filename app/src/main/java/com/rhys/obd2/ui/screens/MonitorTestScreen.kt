package com.rhys.obd2.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.obd.MonitorTest
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.color

/**
 * Service 06 results: the actual measurements behind each pass or fail.
 *
 * The margin bar is the point of this screen. A catalyst that passes at 95% of its limit
 * is going to fail soon; one at 20% is healthy. Neither shows up as anything other than
 * "pass" on a normal scan tool.
 */
@Composable
fun MonitorTestScreen(viewModel: ObdViewModel, onBack: () -> Unit) {
    val connection by viewModel.connectionState.collectAsState()
    val tests by viewModel.monitorTests.collectAsState()
    val busy by viewModel.busy.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("On-board test results", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Service 06 — measured values and their limits",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        if (connection !is ConnectionState.Connected) {
            item { ExplainerCard(tone = Tone.INFO, text = "Connect to the car to read on-board test results.") }
            return@LazyColumn
        }

        item {
            Button(
                onClick = { viewModel.refreshMonitorTests() },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Read test results")
            }
        }

        item {
            ExplainerCard(
                tone = Tone.INFO,
                text = "This is the early-warning screen. Where the codes page tells you what has already " +
                    "failed, this shows how close each component is to its limit — so a catalytic " +
                    "converter or oxygen sensor on the way out is visible months before it sets a code. " +
                    "Read it again after a few weeks and compare.",
            )
        }

        if (tests.isEmpty() && busy == null) {
            item {
                SectionCard {
                    Text(
                        "Nothing read yet. Note that service 06 is optional — a fair number of cars, " +
                            "particularly pre-2010 ones, return no data here even though everything else works.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        val grouped = tests.groupBy { it.monitorName }
        grouped.forEach { (monitor, results) ->
            item(key = monitor) {
                SectionCard(
                    title = monitor,
                    trailing = {
                        val failed = results.count { !it.passed }
                        StatusPill(
                            if (failed == 0) "Pass" else "$failed failing",
                            if (failed == 0) Tone.ACCENT else Tone.DANGER,
                        )
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        results.forEach { TestRow(it) }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun TestRow(test: MonitorTest) {
    val margin = test.margin
    val colour = when {
        !test.passed -> Tone.DANGER.color()
        margin != null && (margin > 0.85 || margin < 0.15) -> Tone.WARNING.color()
        else -> Tone.ACCENT.color()
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                test.testName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${format(test.value)} ${test.unit}".trim(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = colour,
            )
        }

        if (test.min != null || test.max != null) {
            Spacer(Modifier.height(6.dp))
            // Where the value sits between its limits, drawn as a track with a marker.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (margin != null) {
                    Box(
                        Modifier
                            .fillMaxWidth(margin.toFloat().coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colour),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    test.min?.let { "min ${format(it)}" } ?: "no lower limit",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    test.max?.let { "max ${format(it)}" } ?: "no upper limit",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (test.rawScaling) {
            Text(
                "Raw counts — this car reports a scaling code the app doesn't recognise, so the numbers " +
                    "have no unit. The comparison against the limits is still valid.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun format(value: Double): String = when {
    kotlin.math.abs(value) >= 1000 -> "%.0f".format(value)
    kotlin.math.abs(value) >= 10 -> "%.2f".format(value)
    else -> "%.4f".format(value).trimEnd('0').trimEnd('.')
}
