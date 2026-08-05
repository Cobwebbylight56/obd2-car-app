package com.rhys.obd2.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The status palette: the four meanings this app needs to express beyond Material's own
 * roles — healthy, worth watching, wrong, and informational.
 *
 * Each meaning carries three tones rather than one, and that separation is the whole point.
 * A single colour cannot serve as both the fill behind a chip and the text on top of it:
 * whatever is light enough to sit behind text is too light to *be* text. Every status
 * therefore has a foreground tone for text and icons, a container tone for the block behind
 * them, and an outline tone for its edge.
 *
 * The tones differ between light and dark rather than being one set used in both. A mint
 * that reads at 10.7:1 on a near-black dashboard reads at 1.6:1 on white — invisible — so
 * the light scheme uses darker, more saturated tones of the same hues. Every foreground is
 * verified at 4.5:1 or better against the lightest surface it can land on, including its
 * own container, which is the tightest case and the one that gets missed.
 *
 * There is a fourth tone per meaning, for ink rather than text: gauge arcs, sparklines,
 * bars. WCAG asks 4.5:1 of text but only 3:1 of a meaningful graphic, and that difference
 * matters more than it sounds. Forcing the light-theme warning down to 4.5:1 on white
 * produced a dark brown, and a gauge blending from green to dark brown passes through
 * olive — which is what an engine at 108 °C actually looked like: dirty, and not obviously
 * worse than one at 90. The graphic tones take the slack the standard allows and are
 * properly chromatic as a result.
 *
 * Colour is never the only carrier of meaning: chips pair it with a dot and a word, and
 * severity is always spelled out in text as well.
 */
@Immutable
data class StatusColors(
    val accent: Color,
    val accentContainer: Color,
    val accentOutline: Color,
    val accentGraphic: Color,

    val warning: Color,
    val warningContainer: Color,
    val warningOutline: Color,
    val warningGraphic: Color,

    val danger: Color,
    val dangerContainer: Color,
    val dangerOutline: Color,
    val dangerGraphic: Color,

    val info: Color,
    val infoContainer: Color,
    val infoOutline: Color,
    val infoGraphic: Color,

    /** Readings and identifiers that are present but carry no judgement. */
    val neutral: Color,
    val neutralContainer: Color,
    val neutralOutline: Color,
    val neutralGraphic: Color,
) {
    /** The container that belongs with a given foreground, for chips and banners. */
    fun containerFor(foreground: Color): Color = when (foreground) {
        accent -> accentContainer
        warning -> warningContainer
        danger -> dangerContainer
        info -> infoContainer
        else -> neutralContainer
    }

    /** The outline that belongs with a given foreground. */
    fun outlineFor(foreground: Color): Color = when (foreground) {
        accent -> accentOutline
        warning -> warningOutline
        danger -> dangerOutline
        info -> infoOutline
        else -> neutralOutline
    }
}

/**
 * Dark tones. This is the scheme that was actually designed for — the app gets used in a
 * footwell at night and under a bonnet — and the values are unchanged from the original
 * palette because they measure well: every foreground is above 5.6:1 on the card surface.
 */
internal val DarkStatusColors = StatusColors(
    accent = Color(0xFF37E5A0),
    accentContainer = Color(0xFF16332C),
    accentOutline = Color(0xFF2A6B54),
    // On a near-black surface there is no tension between legibility and chroma — these
    // tones are already vivid, so ink and text are the same colour here. The split only
    // earns its keep in the light theme.
    accentGraphic = Color(0xFF37E5A0),

    warning = Color(0xFFFFB020),
    warningContainer = Color(0xFF33291A),
    warningOutline = Color(0xFF7A5A1E),
    warningGraphic = Color(0xFFFFB020),

    danger = Color(0xFFFF5470),
    dangerContainer = Color(0xFF351F27),
    dangerOutline = Color(0xFF7A3444),
    dangerGraphic = Color(0xFFFF5470),

    info = Color(0xFF54A0FF),
    infoContainer = Color(0xFF18293D),
    infoOutline = Color(0xFF2F5580),
    infoGraphic = Color(0xFF54A0FF),

    neutral = Color(0xFF8C99A8),
    neutralContainer = Color(0xFF1D2530),
    neutralOutline = Color(0xFF35404E),
    neutralGraphic = Color(0xFF8C99A8),
)

/**
 * Light tones: the same four hues taken darker and more saturated so they survive on white.
 *
 * These are not tints of the dark values, they are replacements. Reusing the dark mint here
 * put the primary action colour at 1.6:1 against a white card, which is below the threshold
 * at which text is legible at all — the light theme was effectively unusable, and Android
 * defaults to light.
 */
internal val LightStatusColors = StatusColors(
    accent = Color(0xFF0A6B45),
    accentContainer = Color(0xFFE1F4EC),
    accentOutline = Color(0xFF8FCDB4),
    accentGraphic = Color(0xFF00875A),

    warning = Color(0xFF8A5300),
    warningContainer = Color(0xFFFBEFD9),
    warningOutline = Color(0xFFDCB876),
    // The one that matters. #8A5300 is the darkest amber that still reads as text on
    // white; as ink it is brown, and a gauge fading from green to brown goes through
    // olive. #CC6600 is a real orange, and at 3.1:1 on the tightest light surface it is
    // still above what the standard asks of a graphic.
    warningGraphic = Color(0xFFCC6600),

    danger = Color(0xFFC10025),
    dangerContainer = Color(0xFFFCE0E5),
    dangerOutline = Color(0xFFE79AA8),
    dangerGraphic = Color(0xFFDA1E33),

    info = Color(0xFF0058C4),
    infoContainer = Color(0xFFDEEAFB),
    infoOutline = Color(0xFF9CBEE8),
    infoGraphic = Color(0xFF0069D9),

    neutral = Color(0xFF4A5764),
    neutralContainer = Color(0xFFEDF1F5),
    neutralOutline = Color(0xFFC3CCD6),
    neutralGraphic = Color(0xFF6E7C8C),
)

internal val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }

/**
 * Status colours for the current theme.
 *
 * Reached through [MaterialTheme] so call sites read like the built-in roles do —
 * `MaterialTheme.status.danger` alongside `MaterialTheme.colorScheme.onSurface` — and so
 * no screen can reach a raw hex value that only works in one theme.
 */
val MaterialTheme.status: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current
