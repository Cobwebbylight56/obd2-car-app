package com.rhys.obd2.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * What a piece of the interface *means*, rather than what colour it is.
 *
 * Components take a tone; they never take a `Color`. That inversion is what stops the
 * light theme from rotting again: a call site can say "this is a fault" but cannot say
 * "this is #FF5470", so there is no way to write a screen that only works in one theme.
 * It also means the palette can be retuned in one place and every screen follows.
 */
enum class Tone {
    /** Healthy, passing, complete, or the primary action. */
    ACCENT,

    /** Worth attention but not a fault: drifting values, incomplete monitors. */
    WARNING,

    /** A fault, a failure, or a destructive action. */
    DANGER,

    /** Explanatory or contextual. Carries no judgement about the car. */
    INFO,

    /** Present but unremarkable: identifiers, timestamps, raw readings. */
    NEUTRAL,
}

/** Resolved colours for a tone in the current theme. */
@Immutable
data class ToneColors(val foreground: Color, val container: Color, val outline: Color)

@Composable
@ReadOnlyComposable
fun Tone.colors(): ToneColors {
    val s = MaterialTheme.status
    return when (this) {
        Tone.ACCENT -> ToneColors(s.accent, s.accentContainer, s.accentOutline)
        Tone.WARNING -> ToneColors(s.warning, s.warningContainer, s.warningOutline)
        Tone.DANGER -> ToneColors(s.danger, s.dangerContainer, s.dangerOutline)
        Tone.INFO -> ToneColors(s.info, s.infoContainer, s.infoOutline)
        Tone.NEUTRAL -> ToneColors(s.neutral, s.neutralContainer, s.neutralOutline)
    }
}

/** Just the foreground, for text and icons drawn straight onto a surface. */
@Composable
@ReadOnlyComposable
fun Tone.color(): Color = colors().foreground
