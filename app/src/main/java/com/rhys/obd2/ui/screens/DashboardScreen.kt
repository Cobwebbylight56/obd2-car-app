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
import com.rhys.obd2.data.LinkState
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
import com.rhys.obd2.ui.components.ExplainerCard
import androidx.compose.runtime.remember

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
    val vehicle by viewModel.currentVehicle.collectAsState()
    val rollback = remember(vehicle?.key) { viewModel.odometerWentBackwards() }
    val supported by viewModel.supportedPids.collectAsState()
    val loadEstimate by viewModel.loadEstimate.collectAsState()
    val loadUnusableReason by viewModel.loadUnusableReason.collectAsState()
    val link by viewModel.link.collectAsState()
    val showOdometer by viewModel.settings.showOdometer.collectAsState()

    val odometerKm = live[0xA6]?.primary?.value

    // Two separate reasons to leave the card out, and they are not the same thing.
    //
    // The setting is the driver saying they don't want it. The second clause is the app
    // knowing there is nothing to put in it: once the supported-parameter scan has come
    // back and 0xA6 is not in it, this car will never report mileage, and a card whose
    // only content is "not reported" has no business holding the top of the dashboard for
    // the rest of the car's life. Settings says which of the two is in force, so the
    // toggle never looks broken.
    val odometerUnavailable = odometerKm == null && !rollback &&
        supported.isNotEmpty() && 0xA6 !in supported

    // Substituted, not added alongside. Two load gauges side by side, one real and one
    // derived, is a puzzle rather than a dashboard.
    val realLoadMissing = supported.isNotEmpty() && 0x04 !in supported ||
        live[PidRegistry.ESTIMATED_LOAD] != null && live[0x04] == null ||
        loadUnusableReason != null

    // Same substitution for the battery. PID 42 is a late addition and plenty of cars
    // never answer it, which is why this gauge sat empty; the adapter measures the same
    // battery itself and every ELM327 will report it.
    val carVoltageMissing = live[PidRegistry.ADAPTER_VOLTAGE] != null && live[0x42] == null

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

        if (showOdometer && !odometerUnavailable) {
            item(span = { GridItemSpan(2) }) {
                OdometerHeader(
                    km = odometerKm,
                    units = units,
                    rollback = rollback,
                )
            }
        }

        // Above everything, because a frozen gauge is indistinguishable from a steady one
        // and the driver has no other way to tell. Draws nothing while data is flowing.
        if (link !is LinkState.Idle && link !is LinkState.Live) {
            item(span = { GridItemSpan(2) }) {
                LinkStatusCard(link) { viewModel.reconnect() }
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

        val gaugePids = dashboardPids
            .map {
                when {
                    it == 0x04 && realLoadMissing -> PidRegistry.ESTIMATED_LOAD
                    it == 0x42 && carVoltageMissing -> PidRegistry.ADAPTER_VOLTAGE
                    else -> it
                }
            }
            .mapNotNull { PidRegistry[it] }
        items(gaugePids, key = { it.id }) { pid ->
            val value = live[pid.id]
            GaugeTile(
                pid = pid,
                rawValue = value?.primary?.value,
                history = value?.history ?: emptyList(),
                units = units,
                // Said on the tile rather than by deleting it. A gauge that vanishes looks
                // like the app losing something; a gauge that says the car doesn't report
                // it is the actual answer, and it comes back on a car that does.
                unsupported = supported.isNotEmpty() && pid.id <= 0xFF && pid.id !in supported,
                footnote = when (pid.id) {
                    // Never blank. An empty derived gauge is indistinguishable from the
                    // broken reading it replaced, so it says what it is waiting for.
                    PidRegistry.ESTIMATED_LOAD ->
                        loadEstimate?.let { "${it.confidence.label} — ${it.basis}" }
                            ?: "Waiting for airflow"
                    // Where the number came from, because it did not come from the car.
                    PidRegistry.ADAPTER_VOLTAGE -> "Measured at the adapter"
                    else -> null
                },
            )
        }

        if (live[PidRegistry.ESTIMATED_LOAD] != null) {
            item(span = { GridItemSpan(2) }) {
                ExplainerCard(
                    tone = Tone.INFO,
                    // Two different reasons the derived figure is on screen, and telling
                    // the driver the wrong one is worse than telling them nothing. A car
                    // that never offered the parameter is not the same as a car that
                    // offered it and answered something impossible.
                    text = loadUnusableReason
                        ?: "This car's ECU doesn't report engine load, so the app works one " +
                        "out from airflow and engine speed, learning this engine's range as " +
                        "you drive. It is an estimate, not the ECU's own figure, and it " +
                        "means less on a diesel — a diesel runs unthrottled and breathes " +
                        "much the same at a given speed whatever it is doing, so this reads " +
                        "more as effort than as true load.",
                )
            }
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
    unsupported: Boolean = false,
    footnote: String? = null,
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
                min = gaugeMin(pid).toFloat(),
                max = gaugeMax(pid).toFloat(),
                label = pid.shortName,
                unit = displayUnit,
                valueText = text,
                warningThreshold = warn,
                dangerThreshold = danger,
                optimalRange = optimalRange(pid.id),
                coldAnchor = coldAnchor(pid.id),
                modifier = Modifier.fillMaxWidth(),
            )
            footnote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            if (unsupported) {
                Text(
                    "Not reported by this car",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
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
    0x42, PidRegistry.ADAPTER_VOLTAGE -> 16.0  // battery, from the car or from the adapter
    0x0B -> 255.0     // MAP
    0x10 -> 200.0     // MAF
    0xA6 -> 300000.0  // odometer
    else -> pid.max
}

/**
 * The working range for fluids that have to reach a temperature before the engine is
 * running properly.
 *
 * Below it the gauge reads blue: not a fault, but the engine is wearing faster, using
 * more fuel and not yet in closed loop, which is worth being able to see at a glance
 * rather than having to read the number. Coolant settles between roughly 82 and 105 °C
 * once the thermostat opens; oil runs hotter and takes longer to get there.
 *
 * Deliberately not applied to intake air, where cold is what you want.
 */
/**
 * Where a gauge's scale starts.
 *
 * The standard's floor for a temperature is -40 °C, which is a real limit of the sensor
 * and a useless one for a dial: it puts a stone-cold engine a quarter of the way round
 * before it has done anything. Starting at zero means the sweep tracks warming up.
 */
private fun gaugeMin(pid: Pid): Double = when (pid.id) {
    0x05, 0x0F, 0x46, 0x5C, 0x67 -> 0.0   // temperatures
    // A dead battery is 11 V and a healthy one 14.4, so a scale from zero puts the entire
    // useful range in the last fifth of the sweep and the needle never appears to move.
    0x42, PidRegistry.ADAPTER_VOLTAGE -> 8.0
    else -> pid.min
}

private fun optimalRange(pidId: Int): ClosedFloatingPointRange<Float>? = when (pidId) {
    0x05 -> 82f..105f    // engine coolant
    0x5C -> 80f..115f    // engine oil
    0x67 -> 82f..105f    // coolant, secondary sensor
    // Battery voltage is not "more is worse" and treating it that way turned a perfectly
    // healthy 13.5 V amber — the alternator doing exactly its job, coloured as a warning.
    // It has a wrong end at the bottom, like a temperature: below 12 is a flat battery or
    // a dead alternator, 13.5 to 14.8 is charging properly, and above 15 is overcharging.
    0x42, PidRegistry.ADAPTER_VOLTAGE -> 13.2f..14.8f
    else -> null
}

/**
 * Where the low end of a both-ends-matter gauge is fully "wrong".
 *
 * For a coolant temperature that is 40 °C — a cold engine. For a battery it is 11.5 V,
 * below which the car is not going to start. They share a gauge shape and nothing else,
 * so the anchor travels with the parameter rather than being baked into the component.
 */
private fun coldAnchor(pidId: Int): Float = when (pidId) {
    0x42, PidRegistry.ADAPTER_VOLTAGE -> 11.5f
    else -> 40f
}

private fun thresholds(pidId: Int): Pair<Float?, Float?> = when (pidId) {
    0x05 -> 105f to 115f          // coolant temperature
    0x5C -> 120f to 135f          // oil temperature
    0x0C -> 5500f to 6500f        // RPM
    // Voltage is handled by optimalRange instead: it has a wrong end at both ends, so a
    // pair of "above this is bad" thresholds cannot express it. 15.2 is where overcharging
    // starts and is passed as the danger end so the arc reaches red there rather than at
    // the top of the dial.
    0x42, PidRegistry.ADAPTER_VOLTAGE -> null to 15.2f
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

/**
 * The odometer, pinned above everything else.
 *
 * Not one of the gauges. A dial is for a value that moves while you watch it; total
 * distance is a fact about the car, read once and then stable, and it belongs with the
 * car's identity rather than in a grid of needles.
 *
 * Most cars will show nothing here, and the card says so rather than sitting blank. The
 * odometer only reached the standard in a later revision and is rare on anything built
 * before roughly 2018 — every car this app was written for will come up empty. That state
 * is only worth showing while it is still uncertain: once the app knows the car doesn't
 * report mileage the caller drops the card entirely, and the driver can drop it themselves
 * from Settings whether the car reports it or not.
 *
 * Internal rather than private so the design gallery can render all three of its states
 * without a car, an adapter or a ViewModel.
 */
@Composable
internal fun OdometerHeader(km: Double?, units: UnitSystem, rollback: Boolean) {
    val converted = km?.let { Units.convert(it, "km", units) }

    SectionCard(
        title = "Odometer",
        trailing = {
            when {
                rollback -> StatusPill("Went backwards", Tone.DANGER)
                converted != null -> StatusPill("Recorded", Tone.ACCENT)
                else -> StatusPill("Not reported", Tone.NEUTRAL)
            }
        },
    ) {
        Column {
            if (converted != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%,.0f".format(java.util.Locale.UK, converted.value),
                        // Proportional, not the monospaced gauge style. Monospacing exists
                        // to stop a live number jumping sideways as its digits change, and
                        // total mileage does not change while you watch it. What it does
                        // have is a thousands separator, and a monospaced comma sits in a
                        // full character cell — "184 , 373", read as three numbers.
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        converted.unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            } else {
                Text(
                    "This car doesn't report its odometer over OBD-II. The parameter was " +
                        "added to the standard late and is uncommon on anything built before " +
                        "about 2018.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (rollback) {
                Spacer(Modifier.height(8.dp))
                ExplainerCard(
                    tone = Tone.DANGER,
                    text = "A reading recorded for this car was lower than one taken earlier. " +
                        "An odometer does not run backwards. A replaced instrument cluster or " +
                        "ECU explains it innocently; so does tampering. The dates are in the " +
                        "Garage history.",
                )
            }
        }
    }
}
