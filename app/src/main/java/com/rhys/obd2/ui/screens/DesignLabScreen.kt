package com.rhys.obd2.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.ScreenHeader
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.gallery.Gallery
import com.rhys.obd2.ui.theme.OpenObdTheme
import com.rhys.obd2.ui.theme.Radius
import com.rhys.obd2.ui.theme.Space
import com.rhys.obd2.ui.theme.Tone

/**
 * The design gallery, on a device.
 *
 * The rendered screenshots catch layout and colour, but there are things only a real phone
 * will tell you: whether a row is comfortable under an actual thumb, what happens at 200%
 * font scale, how the dark scheme reads at night in a car rather than on a monitor. This is
 * the same [Gallery] the screenshot harness renders, so the two can't drift.
 *
 * The theme toggle is the important control. It flips only this screen's theme, so both
 * versions of a component can be compared in a couple of seconds without leaving the app or
 * changing a system setting.
 */
@Composable
fun DesignLabScreen(onBack: () -> Unit) {
    var group by remember { mutableStateOf(Gallery.groups.first()) }
    var forceDark by remember { mutableStateOf<Boolean?>(null) }

    val entries = Gallery.entries.filter { it.group == group }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item {
            ScreenHeader(
                title = "Design lab",
                subtitle = "${Gallery.entries.size} components and states",
                trailing = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }

        item {
            ExplainerCard(
                tone = Tone.INFO,
                text = "Every component in every state it can reach, including the ones a " +
                    "healthy car never shows you. The same list is rendered to images on " +
                    "each build, so a visual change is reviewable as a picture rather than " +
                    "as a description.",
            )
        }

        item {
            Column {
                Text("Group", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(Space.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Wraps naturally on narrow screens by letting the row scroll.
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Gallery.groups.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                row.forEach { name ->
                                    FilterChip(
                                        selected = group == name,
                                        onClick = { group = name },
                                        label = { Text(name) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Column {
                Text("Theme", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(Space.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    FilterChip(
                        selected = forceDark == null,
                        onClick = { forceDark = null },
                        label = { Text("System") },
                    )
                    FilterChip(
                        selected = forceDark == false,
                        onClick = { forceDark = false },
                        label = { Text("Light") },
                    )
                    FilterChip(
                        selected = forceDark == true,
                        onClick = { forceDark = true },
                        label = { Text("Dark") },
                    )
                }
            }
        }

        items(entries, key = { "${it.group}/${it.name}" }) { entry ->
            SectionCard(title = entry.name, subtitle = entry.notes.ifBlank { null }) {
                // Each entry renders under the chosen theme rather than the app's, so both
                // schemes can be compared without restarting anything.
                val content = @Composable {
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        shape = Radius.control,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(Space.md)) { entry.content() }
                    }
                }
                if (forceDark == null) content() else OpenObdTheme(darkTheme = forceDark!!) { content() }
            }
        }

        item { Spacer(Modifier.height(Space.scrollFooter)) }
    }
}
