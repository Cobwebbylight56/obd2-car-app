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

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
