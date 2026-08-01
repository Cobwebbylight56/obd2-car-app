package com.rhys.obd2.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.obd2.ui.theme.Accent
import com.rhys.obd2.ui.theme.Danger
import com.rhys.obd2.ui.theme.Warning
import kotlin.math.max

/**
 * A radial gauge.
 *
 * The 240° sweep with the gap at the bottom is the automotive convention, and it leaves
 * room for the numeric readout in the middle where the eye naturally lands. The needle
 * is animated rather than snapping so a glance at a moving value reads as motion, not
 * as a number that changed while you weren't looking.
 */
@Composable
fun Gauge(
    value: Float,
    min: Float,
    max: Float,
    label: String,
    unit: String,
    valueText: String,
    modifier: Modifier = Modifier,
    warningThreshold: Float? = null,
    dangerThreshold: Float? = null,
) {
    val range = (max - min).takeIf { it > 0f } ?: 1f
    val fraction = ((value - min) / range).coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = fraction, label = "gaugeSweep")

    val colour = when {
        dangerThreshold != null && value >= dangerThreshold -> Danger
        warningThreshold != null && value >= warningThreshold -> Warning
        else -> Accent
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.09f
            val inset = stroke / 2f + 2f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            // 240° of sweep starting at 150°, which puts the gap symmetrically at the bottom.
            drawArc(
                color = track,
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = colour,
                startAngle = 150f,
                sweepAngle = 240f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = valueText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = onSurface,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Text(unit, fontSize = 12.sp, color = dim, maxLines = 1)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                color = dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/**
 * A compact history plot. Auto-scales to the data it's given rather than to the PID's
 * full theoretical range, because coolant temperature drifting between 88 and 92 °C is
 * invisible on a -40 to 215 °C axis and is exactly what you want to see.
 */
@Composable
fun Sparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    colour: Color = Accent,
) {
    if (points.size < 2) {
        Box(modifier)
        return
    }

    val minValue = points.min()
    val maxValue = points.max()
    val span = max(maxValue - minValue, 0.0001f)

    Canvas(modifier = modifier) {
        val stepX = size.width / (points.size - 1).toFloat()
        val path = Path()
        val fill = Path()

        points.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - ((value - minValue) / span) * size.height
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()

        drawPath(fill, color = colour.copy(alpha = 0.15f))
        drawPath(path, color = colour, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** A labelled status chip. The dot gives a second, non-colour cue at a glance. */
@Composable
fun StatusPill(
    text: String,
    colour: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colour.copy(alpha = 0.15f))
            .border(1.dp, colour.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(colour)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = colour, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Section container used throughout, so spacing and elevation stay consistent. */
@Composable
fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** Key/value row used in the info screens. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/** Explanatory block for the screens that need to teach as well as report. */
@Composable
fun ExplainerCard(text: String, modifier: Modifier = Modifier, accent: Color = Warning) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.08f))
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(if (text.length > 120) 96.dp else 48.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
