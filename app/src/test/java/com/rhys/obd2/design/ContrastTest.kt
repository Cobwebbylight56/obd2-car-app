package com.rhys.obd2.design

import androidx.compose.ui.graphics.Color
import com.rhys.obd2.ui.theme.DarkStatusColors
import com.rhys.obd2.ui.theme.LightStatusColors
import com.rhys.obd2.ui.theme.StatusColors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast, measured rather than eyeballed.
 *
 * This test exists because the light theme shipped unusable and stayed that way. The status
 * colours were chosen against a near-black dashboard, screens referenced them directly, and
 * on a white card the accent measured 1.6:1 — below the point at which text is legible at
 * all. Nobody noticed, because checking meant opening the app in light mode and trusting an
 * impression.
 *
 * WCAG 2.1 AA wants 4.5:1 for normal text and 3:1 for large text and meaningful graphics.
 * Every foreground here is checked against every surface it can actually land on, including
 * its own container — that last pairing being both the tightest and the one an eyeball check
 * skips, because a chip looks fine right up until it doesn't.
 */
class ContrastTest {

    // The surfaces a status foreground can be drawn on, per theme.
    private val darkSurfaces = mapOf(
        "background" to Color(0xFF0B0F14),
        "surface" to Color(0xFF141A22),
        "surfaceVariant" to Color(0xFF1D2530),
        "surfaceContainerHighest" to Color(0xFF232C38),
    )

    private val lightSurfaces = mapOf(
        "background" to Color(0xFFF6F8FA),
        "surface" to Color.White,
        "surfaceVariant" to Color(0xFFEDF1F5),
        "surfaceContainerHighest" to Color(0xFFE3E9EF),
    )

    @Test
    fun `dark status foregrounds are legible on every surface`() {
        assertAllForegrounds("dark", DarkStatusColors, darkSurfaces)
    }

    @Test
    fun `light status foregrounds are legible on every surface`() {
        assertAllForegrounds("light", LightStatusColors, lightSurfaces)
    }

    @Test
    fun `status foregrounds are legible on their own containers`() {
        // A chip draws its label in the foreground tone on the container tone. That is the
        // tightest pairing in the system and the one that broke.
        listOf("dark" to DarkStatusColors, "light" to LightStatusColors).forEach { (theme, s) ->
            named(s).forEach { (name, pair) ->
                val (foreground, container) = pair
                assertAA(
                    "$theme $name foreground on $name container",
                    foreground,
                    container,
                )
            }
        }
    }

    @Test
    fun `containers are distinguishable from the surface behind them`() {
        // A container that matches its surface makes the chip vanish into the card. This is
        // a lower bar than text contrast on purpose — it only has to read as a block.
        val cases = listOf(
            Triple("dark", DarkStatusColors, Color(0xFF141A22)),
            Triple("light", LightStatusColors, Color.White),
        )
        cases.forEach { (theme, s, surface) ->
            named(s).forEach { (name, pair) ->
                val ratio = contrast(pair.second, surface)
                assertTrue(
                    "$theme $name container is invisible against the card behind it " +
                        "(${"%.2f".format(ratio)}:1, want 1.10 or more)",
                    ratio >= 1.10,
                )
            }
        }
    }

    @Test
    fun `outlines are visible against the surface`() {
        listOf(
            Triple("dark", DarkStatusColors, Color(0xFF141A22)),
            Triple("light", LightStatusColors, Color.White),
        ).forEach { (theme, s, surface) ->
            listOf(
                "accent" to s.accentOutline,
                "warning" to s.warningOutline,
                "danger" to s.dangerOutline,
                "info" to s.infoOutline,
                "neutral" to s.neutralOutline,
            ).forEach { (name, outline) ->
                val ratio = contrast(outline, surface)
                assertTrue(
                    "$theme $name outline is invisible (${"%.2f".format(ratio)}:1, want 1.30 or more)",
                    ratio >= 1.30,
                )
            }
        }
    }

    @Test
    fun `graphic tones meet the bar for a meaningful graphic`() {
        // 3:1, not 4.5:1 — WCAG holds a drawn shape to a lower bar than text, and that
        // slack is the entire reason these tones exist. It is not licence to go pale: a
        // gauge arc still has to be findable at arm's length in daylight.
        listOf(
            Triple("dark", DarkStatusColors, darkSurfaces),
            Triple("light", LightStatusColors, lightSurfaces),
        ).forEach { (theme, s, surfaces) ->
            graphics(s).forEach { (name, ink) ->
                surfaces.forEach { (surfaceName, surface) ->
                    val ratio = contrast(ink, surface)
                    assertTrue(
                        "$theme $name graphic on $surfaceName measures " +
                            "${"%.2f".format(ratio)}:1, below the 3:1 a graphic needs",
                        ratio >= 3.0,
                    )
                }
            }
        }
    }

    @Test
    fun `no gauge blend passes through mud`() {
        // A gauge fades between adjacent tones. If the halfway point is markedly less
        // colourful than either end, the arc reads as a faded instrument rather than as a
        // warming one — which is what an engine at 108 degrees looked like: no more
        // alarming than one at 90.
        //
        // The pairing that caused that, the old dark green to the old dark amber, measured
        // 0.24 on this scale. The blends performed now measure 0.54 and above, so the floor
        // sits between: comfortably clear of what ships, comfortably above what failed.
        //
        // Green to orange is a pairing no two colours can survive — every intermediate is
        // olive — so the gauge steps across that boundary instead of fading. This checks
        // only the blends it actually performs.
        listOf("dark" to DarkStatusColors, "light" to LightStatusColors).forEach { (theme, s) ->
            listOf(
                "cold to healthy" to (s.infoGraphic to s.accentGraphic),
                "warning to danger" to (s.warningGraphic to s.dangerGraphic),
            ).forEach { (what, ends) ->
                val (from, to) = ends
                val midpoint = Color(
                    red = (from.red + to.red) / 2f,
                    green = (from.green + to.green) / 2f,
                    blue = (from.blue + to.blue) / 2f,
                )
                assertTrue(
                    "$theme $what blends through a washed-out midpoint " +
                        "(chroma ${"%.3f".format(chroma(midpoint))}, want $MIN_BLEND_CHROMA " +
                        "or more)",
                    chroma(midpoint) >= MIN_BLEND_CHROMA,
                )
            }
        }
    }

    @Test
    fun `the two themes define the same set of meanings`() {
        // A tone present in one theme and missing in the other would resolve to whatever the
        // data class default happened to be, which is how one-theme bugs start.
        assertTrue(
            "Both themes must define all five tones",
            named(DarkStatusColors).keys == named(LightStatusColors).keys,
        )
    }

    @Test
    fun `the measurement itself is right`() {
        // Anchors from the WCAG definition. If these drift the rest of the file proves nothing.
        assertTrue("black on white is 21:1", contrast(Color.Black, Color.White) > 20.9)
        assertTrue("white on white is 1:1", contrast(Color.White, Color.White) < 1.01)
        assertTrue("contrast is symmetric", contrast(Color.Black, Color.White) == contrast(Color.White, Color.Black))
    }

    // -----------------------------------------------------------------------------------

    private fun named(s: StatusColors): Map<String, Pair<Color, Color>> = mapOf(
        "accent" to (s.accent to s.accentContainer),
        "warning" to (s.warning to s.warningContainer),
        "danger" to (s.danger to s.dangerContainer),
        "info" to (s.info to s.infoContainer),
        "neutral" to (s.neutral to s.neutralContainer),
    )

    private fun graphics(s: StatusColors): Map<String, Color> = mapOf(
        "accent" to s.accentGraphic,
        "warning" to s.warningGraphic,
        "danger" to s.dangerGraphic,
        "info" to s.infoGraphic,
        "neutral" to s.neutralGraphic,
    )

    /**
     * How colourful a colour is, on the crude but sufficient max-minus-min measure.
     *
     * Grey is 0 and a pure hue is 1. Enough to catch a blend collapsing towards grey, which
     * is the only thing it is used for here.
     */
    private fun chroma(c: Color): Float =
        maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    private fun assertAllForegrounds(
        theme: String,
        colours: StatusColors,
        surfaces: Map<String, Color>,
    ) {
        named(colours).forEach { (name, pair) ->
            surfaces.forEach { (surfaceName, surface) ->
                assertAA("$theme $name on $surfaceName", pair.first, surface)
            }
        }
    }

    private fun assertAA(what: String, foreground: Color, background: Color) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "$what measures ${"%.2f".format(ratio)}:1, below the 4.5:1 needed for text",
            ratio >= 4.5,
        )
    }

    /** WCAG 2.1 relative luminance. */
    private fun luminance(colour: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(colour.red) +
            0.7152 * channel(colour.green) +
            0.0722 * channel(colour.blue)
    }

    private companion object {
        /** See `no gauge blend passes through mud` for where this number comes from. */
        const val MIN_BLEND_CHROMA = 0.45f
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
