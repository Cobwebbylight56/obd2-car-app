package com.rhys.obd2.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.rhys.obd2.ui.gallery.Gallery
import com.rhys.obd2.ui.theme.OpenObdTheme
import com.rhys.obd2.ui.theme.Space
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the design gallery to PNGs.
 *
 * This is the review harness. Compose runs on the JVM through Robolectric, so there is no
 * emulator and no device — every entry in [Gallery] is drawn in both themes and written to
 * `design/screenshots/`, which is committed. A visual regression then arrives in review as
 * an image diff rather than as a line of Kotlin somebody has to picture.
 *
 * Both themes, every time, is the whole point. The light scheme was unusable for months —
 * status colours at 1.6:1 against a white card — precisely because nothing ever put the two
 * renders next to each other.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi")
class DesignReviewTest {

    /*
     * The virtual device is specified rather than left to default, and both parts matter.
     *
     * Robolectric's default screen is 320x470dp at 1x — a phone from 2010. Rendering
     * against it produced two separate lies. Everything was 91dp narrower than a real
     * handset, so text wrapped and truncated in ways it never would on hardware; the first
     * review off this harness turned up a "truncated gauge label" that was purely an
     * artifact of the narrow screen. And the capture is bounded by the screen height, so
     * any entry taller than 470dp was silently cut off — the status tone sheet lost its
     * last swatch entirely, which is exactly the kind of omission a review must not have.
     *
     * 411dp is a common modern width (Pixel-class). The height is deliberately absurd so
     * that nothing is ever clipped by it; the capture still sizes to the content. xhdpi
     * doubles the pixel dimensions, which matters because these images get read at a
     * glance and 1x text is too small to judge.
     */

    @Test
    fun `render every gallery entry in both themes`() {
        val root = File("../design/screenshots")

        Gallery.entries.forEach { entry ->
            listOf(false to "light", true to "dark").forEach { (dark, themeName) ->
                val slug = entry.group.lowercase().replace(' ', '-')
                val target = File(root, "$slug/${entry.name}--$themeName.png")
                target.parentFile?.mkdirs()

                captureRoboImage(file = target) {
                    OpenObdTheme(darkTheme = dark) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Column(
                                Modifier
                                    .width(entry.width)
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(Space.lg)
                            ) {
                                entry.content()
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `rendered images are not clipped by the virtual screen`() {
        // A capture bounded by the screen produces a partial picture that still looks like
        // a complete one, which is worse than no picture. If the tallest entry ever comes
        // within reach of the virtual screen height, the review stops being trustworthy.
        val screenHeightDp = 2400
        val root = File("../design/screenshots")
        val tallest = root.walkTopDown()
            .filter { it.isFile && it.extension == "png" }
            .maxOfOrNull { pngHeightPx(it) / 2 } ?: 0   // xhdpi, so px / 2 gives dp
        check(tallest < screenHeightDp - 200) {
            "The tallest render is ${tallest}dp against a ${screenHeightDp}dp screen. " +
                "Raise the qualifier height before an entry gets cut off."
        }
    }

    /** Reads height straight out of the PNG header — no image library needed. */
    private fun pngHeightPx(file: File): Int {
        val header = file.readBytes().copyOfRange(16, 24)
        return ((header[4].toInt() and 0xFF) shl 24) or
            ((header[5].toInt() and 0xFF) shl 16) or
            ((header[6].toInt() and 0xFF) shl 8) or
            (header[7].toInt() and 0xFF)
    }

    @Test
    fun `gallery entry names are unique and stable as filenames`() {
        // A duplicate name means one entry silently overwrites another's screenshot, so the
        // review looks complete while a component goes unrendered.
        val duplicates = Gallery.entries
            .groupBy { "${it.group}/${it.name}" }
            .filterValues { it.size > 1 }
            .keys
        check(duplicates.isEmpty()) { "Duplicate gallery entries: $duplicates" }

        val badNames = Gallery.entries.map { it.name }.filterNot { it.matches(Regex("[a-z0-9-]+")) }
        check(badNames.isEmpty()) {
            "Gallery names become filenames, so they must be lowercase and hyphenated: $badNames"
        }
    }

    @Test
    fun `every component group is represented`() {
        // A cheap guard against the harness quietly falling behind the app: if a group
        // empties out, someone has deleted entries rather than updating them.
        val expected = setOf("Foundations", "Status", "Containers", "Readouts", "Structure", "Empty states")
        val actual = Gallery.groups.toSet()
        check(actual == expected) { "Gallery groups drifted. Expected $expected, found $actual" }
    }
}
