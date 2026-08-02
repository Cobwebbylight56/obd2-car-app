package com.rhys.obd2.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.components.SectionCard
import com.rhys.obd2.ui.theme.Accent
import com.rhys.obd2.ui.theme.Danger
import com.rhys.obd2.ui.theme.Info
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(viewModel: ObdViewModel, onBack: () -> Unit, onOpen: (File) -> Unit) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(viewModel.listLogs()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text("Recorded trips", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${logs.size} log${if (logs.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                ExplainerCard(
                    accent = Info,
                    text = "No recordings yet. Tap Record on the dashboard while connected, and every " +
                        "reading is written to a CSV you can open in a spreadsheet or plot later. " +
                        "Useful for chasing intermittent faults that never happen while you're looking.",
                )
            }
        }

        for (log in logs) {
            item(key = log.name) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { onOpen(log) }
                        ) {
                            Text(
                                formatName(log),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${sizeLabel(log.length())} · ${lineCount(log)} rows · tap to view",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { share(context, log) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share", tint = Accent)
                        }
                        IconButton(onClick = {
                            viewModel.deleteLog(log)
                            logs = viewModel.listLogs()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Danger)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

private fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share trip log"))
}

private fun formatName(file: File): String {
    val formatter = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK)
    return formatter.format(Date(file.lastModified()))
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Counted rather than estimated; these files are small enough that it's cheap. */
private fun lineCount(file: File): Int = runCatching {
    file.bufferedReader().use { reader -> reader.lineSequence().count() - 1 }
}.getOrDefault(0).coerceAtLeast(0)
