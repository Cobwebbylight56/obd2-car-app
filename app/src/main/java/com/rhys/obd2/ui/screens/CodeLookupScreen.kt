package com.rhys.obd2.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.obd2.obd.Dtc
import com.rhys.obd2.obd.DtcDatabase
import com.rhys.obd2.obd.DtcSeverity
import com.rhys.obd2.obd.DtcStatus
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.components.StatusPill
import com.rhys.obd2.ui.theme.Tone
import com.rhys.obd2.ui.theme.color

/**
 * Offline code lookup.
 *
 * The situation this is for: a garage quotes you a code over the phone, or you see one on
 * a forum, and you want to know what it means without driving home and plugging in. Works
 * with no adapter and no connection.
 *
 * Searching by description as well as by number matters more than it looks — people
 * usually remember "something about the catalytic converter", not P0420.
 */
@Composable
fun CodeLookupScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }

    val results = remember(query) { DtcDatabase.search(query) }

    // A well-formed code that isn't in the table still deserves an answer: its structure
    // alone says which system it belongs to and whether it's manufacturer-defined.
    val synthesised = remember(query) {
        val candidate = query.trim().uppercase()
        if (DtcDatabase.isWellFormed(candidate) && DtcDatabase.lookup(candidate) == null) {
            Dtc.describe(candidate, DtcStatus.STORED)
        } else {
            null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Look up a code", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${DtcDatabase.size} codes, no connection needed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("P0420, or \"catalyst\"") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            if (query.isBlank()) {
                item {
                    ExplainerCard(
                        tone = Tone.INFO,
                        text = "Type a code to see what it means, or describe the symptom — searching " +
                            "for \"misfire\", \"lean\" or \"catalyst\" works too. A partial code lists " +
                            "the whole family, so P03 shows every misfire code.",
                    )
                }
            }

            synthesised?.let { dtc ->
                item(key = "synth-${dtc.code}") { LookupCard(dtc.code, dtc.description, dtc.severity, dtc.advice, dtc.manufacturerSpecific) }
            }

            for ((code, entry) in results) {
                item(key = code) {
                    LookupCard(
                        code = code,
                        description = entry.description,
                        severity = entry.severity,
                        advice = entry.advice,
                        manufacturerSpecific = code.getOrNull(1) in listOf('1', '3'),
                    )
                }
            }

            if (query.length >= 2 && results.isEmpty() && synthesised == null) {
                item {
                    SectionCard {
                        Text(
                            "Nothing matched. If it's a manufacturer-specific code — the second " +
                                "character is 1 or 3 — it won't be in a generic database at all, and " +
                                "you'll need your car's own service data.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun LookupCard(
    code: String,
    description: String,
    severity: DtcSeverity,
    advice: String?,
    manufacturerSpecific: Boolean,
) {
    val tone = when (severity) {
        DtcSeverity.CRITICAL, DtcSeverity.SERIOUS -> Tone.DANGER
        DtcSeverity.MODERATE -> Tone.WARNING
        else -> Tone.INFO
    }

    SectionCard {
        Column {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = tone.color(),
            )
            Text(description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(severity.label, tone)
                if (manufacturerSpecific) StatusPill("Maker-specific", Tone.INFO)
            }
            advice?.let {
                Spacer(Modifier.height(8.dp))
                ExplainerCard(text = it, tone = tone)
            }
        }
    }
}
