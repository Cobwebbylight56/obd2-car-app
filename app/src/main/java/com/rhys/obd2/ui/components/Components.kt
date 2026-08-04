package com.rhys.obd2.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhys.obd2.ui.theme.Motion
import com.rhys.obd2.ui.theme.NumericLarge
import com.rhys.obd2.ui.theme.Radius
import com.rhys.obd2.ui.theme.Space
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.TouchTarget
import com.rhys.obd2.ui.theme.colors
import kotlin.math.max

// ---------------------------------------------------------------------------------------
// Readouts
// ---------------------------------------------------------------------------------------

/**
 * A radial gauge.
 *
 * The 240° sweep with the gap at the bottom is the automotive convention, and it leaves
 * room for the numeric readout in the middle where the eye naturally lands. The needle is
 * animated rather than snapping so a glance at a moving value reads as motion, not as a
 * number that changed while you weren't looking.
 *
 * The whole gauge is one node to a screen reader. Read as separate pieces it announces
 * "eighty five, degrees C, Coolant temperature" — three fragments in the wrong order —
 * so the parts are collapsed into a single sentence that says the label first.
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

    val tone = when {
        dangerThreshold != null && value >= dangerThreshold -> Tone.DANGER
        warningThreshold != null && value >= warningThreshold -> Tone.WARNING
        else -> Tone.ACCENT
    }
    val colour = tone.colors().foreground

    // Values arrive from the car rather than from a tap, so they ease rather than snap;
    // a reading that jumps looks like a glitch, one that travels looks like a measurement.
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(Motion.Standard, easing = Motion.Decelerate),
        label = "gaugeSweep",
    )
    val animatedColour by androidx.compose.animation.animateColorAsState(
        targetValue = colour,
        animationSpec = tween(Motion.Slow, easing = Motion.Standard_),
        label = "gaugeTone",
    )

    val track = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    val spoken = buildString {
        append(label)
        append(": ")
        append(if (valueText == "—") "no reading" else valueText)
        if (unit.isNotEmpty() && valueText != "—") append(" $unit")
        when (tone) {
            Tone.DANGER -> append(", above the safe range")
            Tone.WARNING -> append(", higher than normal")
            else -> Unit
        }
    }

    Box(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
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
                color = animatedColour,
                startAngle = 150f,
                sweepAngle = 240f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clearAndSetSemantics { },
        ) {
            Text(
                text = valueText,
                style = NumericLarge,
                color = onSurface,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Text(unit, style = MaterialTheme.typography.labelSmall, color = dim, maxLines = 1)
            }
            Spacer(Modifier.height(Space.xxs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Space.md),
            )
        }
    }
}

/**
 * A compact history plot. Auto-scales to the data it's given rather than to the PID's full
 * theoretical range, because coolant temperature drifting between 88 and 92 °C is invisible
 * on a -40 to 215 °C axis and is exactly what you want to see.
 *
 * Decorative: the gauge above it already carries the value, so this is hidden from screen
 * readers rather than announced as an unlabelled graphic.
 */
@Composable
fun Sparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.ACCENT,
) {
    if (points.size < 2) {
        Box(modifier)
        return
    }

    val colour = tone.colors().foreground
    val minValue = points.min()
    val maxValue = points.max()
    val span = max(maxValue - minValue, 0.0001f)

    Canvas(modifier = modifier.clearAndSetSemantics { }) {
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

// ---------------------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------------------

/**
 * A labelled status chip.
 *
 * The dot is a second, non-colour cue, and the label states the status in words — so the
 * chip survives being read by someone who can't distinguish the hues, or printed in a
 * screenshot sent to a garage.
 */
@Composable
fun StatusPill(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
) {
    val c = tone.colors()
    Row(
        modifier = modifier
            .clip(Radius.pill)
            .background(c.container)
            .border(1.dp, c.outline, Radius.pill)
            .padding(horizontal = Space.md, vertical = 6.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(c.foreground)
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = c.foreground,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------------
// Containers
// ---------------------------------------------------------------------------------------

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
        shape = Radius.card,
    ) {
        Column(Modifier.padding(Space.lg)) {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
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
                Spacer(Modifier.height(Space.md))
            }
            content()
        }
    }
}

/**
 * Explanatory block for the screens that need to teach as well as report.
 *
 * The accent rule is sized by [IntrinsicSize] rather than guessed from the text length.
 * The previous version picked 48dp or 96dp depending on whether the string was over 120
 * characters, which meant the rule almost never matched the paragraph beside it — short
 * of it on long text, overshooting on short.
 */
@Composable
fun ExplainerCard(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.WARNING,
) {
    val c = tone.colors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(Radius.control)
            .background(c.container)
            .padding(Space.md)
            .semantics(mergeDescendants = true) { },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(Radius.pill)
                .background(c.foreground.copy(alpha = 0.7f))
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Key/value row used in the info screens.
 *
 * Long values stack under their label rather than being squeezed into a right-hand column.
 * Side by side, a VIN broke as `WVWZZZ1KZAW1234` / `56` — an identifier split across two
 * lines mid-run, which is hard to read, hard to read *aloud* to a garage, and hard to
 * check a digit of. Anything that long gets the full width of the card instead, on one
 * line, where it stays a single token.
 *
 * The threshold is a character count rather than a measurement. It is approximate by
 * nature, but it is predictable, and every value in this app is either short (a
 * temperature, a count, a date) or clearly an identifier — there is nothing near the
 * boundary for it to get wrong.
 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    val stacked = value.length > 16

    if (stacked) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .semantics(mergeDescendants = true) { },
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xxs))
            Text(
                value,
                style = com.rhys.obd2.ui.theme.NumericSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.md))
        Text(
            value,
            style = com.rhys.obd2.ui.theme.NumericSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

// ---------------------------------------------------------------------------------------
// Structure
// ---------------------------------------------------------------------------------------

/**
 * The title block every top-level screen starts with.
 *
 * Screens previously each rolled their own heading, or had none — the dashboard opened
 * with a device name, the codes list with a headline, live data with something else again.
 * A person landing on a tab should be told where they are in the same place and the same
 * size every time; that consistency is most of what makes an app feel considered.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Space.lg, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
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
}

/**
 * A tappable row.
 *
 * Exists because the app had grown a dozen hand-built ones: a `Row` with `.clickable` and
 * whatever padding the author felt like. Those were inconsistent in height, several were
 * under the 48dp minimum, and none of them told a screen reader they were buttons — so
 * assistive technology announced a label with no indication it could be activated.
 */
@Composable
fun TappableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role = Role.Button,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.control)
            .clickable(enabled = enabled, role = role, onClick = onClick)
            .heightIn(min = TouchTarget.minimum)
            .padding(horizontal = Space.sm, vertical = Space.sm)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics(mergeDescendants = true) {
                        this.contentDescription = contentDescription
                    }
                } else {
                    Modifier.semantics(mergeDescendants = true) { }
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Space.md))
        }
        Box(Modifier.weight(1f)) { content() }
        if (trailing != null) {
            Spacer(Modifier.width(Space.md))
            trailing()
        }
    }
}

/** The circular icon badge used at the start of list rows. */
@Composable
fun RowIcon(
    icon: ImageVector,
    tone: Tone,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    size: Dp = 38.dp,
) {
    val c = tone.colors()
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) c.container else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = c.foreground,
            modifier = Modifier.size(size * 0.53f),
        )
    }
}

/**
 * What a screen shows when it has nothing to show.
 *
 * Every one of these used to be a bare paragraph of grey text, which reads as though the
 * screen has failed. An empty state should say what belongs here, why it's empty, and what
 * to do about it — the third part being the one that was always missing.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Larger than a list row's badge: this is the only thing on the screen, and at
        // 38dp it read as an afterthought floating above the text rather than as the
        // anchor for it.
        RowIcon(icon, tone, size = 64.dp)
        Spacer(Modifier.height(Space.md))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Space.lg))
            action()
        }
    }
}
