package com.rhys.obd2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

/** One plotted line: values aligned to the shared x-axis, with gaps allowed. */
data class ChartSeries(
    val label: String,
    val unit: String,
    val values: List<Float?>,
    val colour: Color,
)

/**
 * A time-series chart for reviewing a recorded trip.
 *
 * Each series is scaled to its own range rather than a shared axis. That's unusual for a
 * chart and deliberate here: the useful comparison is shape against shape — did coolant
 * temperature climb while the fuel trim drifted — and plotting 900 °C catalyst
 * temperature on the same axis as a ±10 % fuel trim renders the trim as a flat line.
 * Each series carries its own min and max labels so the scaling is never implicit.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    xValues: List<Float>,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    if (series.isEmpty() || xValues.size < 2) {
        Box(modifier) {
            Text(
                "Nothing to plot",
                style = MaterialTheme.typography.bodySmall,
                color = dim,
                modifier = Modifier.padding(8.dp),
            )
        }
        return
    }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            // Horizontal guides at quarters. No numbers on them — each series has its own
            // scale, so a shared y-axis label would be actively misleading.
            val dashes = PathEffect.dashPathEffect(floatArrayOf(6f, 10f))
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(
                    color = grid,
                    start = androidx.compose.ui.geometry.Offset(0f, y),
                    end = androidx.compose.ui.geometry.Offset(size.width, y),
                    strokeWidth = 1f,
                    pathEffect = if (i == 0 || i == 4) null else dashes,
                )
            }

            series.forEach { line ->
                val present = line.values.filterNotNull()
                if (present.size < 2) return@forEach
                val low = present.min()
                val high = present.max()
                val span = max(high - low, 0.0001f)

                val path = Path()
                var started = false
                line.values.forEachIndexed { index, value ->
                    if (value == null) return@forEachIndexed
                    val x = size.width * index / (line.values.size - 1).coerceAtLeast(1)
                    val y = size.height - ((value - low) / span) * size.height * 0.92f -
                        size.height * 0.04f
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(
                    path,
                    color = line.colour,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                "0s",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = dim,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${xValues.last().toInt()}s",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = dim,
            )
        }
    }
}

/** Legend entry showing the series colour and the range its line was scaled against. */
@Composable
fun ChartLegendRow(series: ChartSeries) {
    val present = series.values.filterNotNull()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        // A plain filled swatch rather than a Canvas: a Canvas sized only by width would
        // collapse to zero height and draw nothing.
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(width = 14.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(series.colour)
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(series.label, style = MaterialTheme.typography.bodySmall)
            if (present.isNotEmpty()) {
                Text(
                    "${format(present.min())} to ${format(present.max())} ${series.unit}".trim(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = dim,
                )
            }
        }
    }
}

private fun format(value: Float): String = when {
    abs(value) >= 1000 -> "%.0f".format(java.util.Locale.UK, value)
    abs(value) >= 10 -> "%.1f".format(java.util.Locale.UK, value)
    else -> "%.2f".format(java.util.Locale.UK, value)
}
