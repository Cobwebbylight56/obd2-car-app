package com.rhys.obd2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.obd2.ui.ObdViewModel
import com.rhys.obd2.ui.components.ExplainerCard
import com.rhys.obd2.ui.theme.Accent
import com.rhys.obd2.ui.theme.Info
import kotlinx.coroutines.launch

/**
 * Raw command console.
 *
 * Every scan tool worth using has one of these. It's how you check something the app
 * doesn't expose, work out why a particular adapter is misbehaving, or try a
 * manufacturer-specific request the standard doesn't cover.
 */
@Composable
fun TerminalScreen(viewModel: ObdViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<TerminalEntry>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1)
    }

    fun send(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || sending) return
        input = ""
        sending = true
        history += TerminalEntry(trimmed, null)
        scope.launch {
            val response = viewModel.sendRaw(trimmed)
            val index = history.lastIndex
            if (index >= 0) history[index] = history[index].copy(response = response.ifBlank { "(no response)" })
            sending = false
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
            Column(Modifier.weight(1f)) {
                Text("Terminal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Raw ELM327 commands",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (history.isNotEmpty()) {
                Text(
                    "Clear",
                    color = Accent,
                    modifier = Modifier
                        .clickable { history.clear() }
                        .padding(8.dp),
                )
            }
        }

        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(QUICK_COMMANDS) { quick ->
                AssistChip(
                    onClick = { send(quick.command) },
                    label = { Text(quick.label, fontSize = 12.sp) },
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (history.isEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    ExplainerCard(
                        accent = Info,
                        text = "Commands starting with AT configure the adapter itself; anything else is " +
                            "sent to the car. Try ATI for the adapter's firmware version, ATRV for battery " +
                            "voltage, 0100 for the car's supported parameters, or 03 to read fault codes " +
                            "raw. Responses are hex.",
                    )
                }
            }

            items(history) { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp),
                ) {
                    Text(
                        "> ${entry.command}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Accent,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.response ?: "…",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.uppercase() },
                placeholder = { Text("Command") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { send(input) }, enabled = !sending && input.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (input.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Accent,
                )
            }
        }
    }
}

private data class TerminalEntry(val command: String, val response: String?)

private data class QuickCommand(val label: String, val command: String)

private val QUICK_COMMANDS = listOf(
    QuickCommand("Version", "ATI"),
    QuickCommand("Voltage", "ATRV"),
    QuickCommand("Protocol", "ATDP"),
    QuickCommand("Supported", "0100"),
    QuickCommand("Read DTCs", "03"),
    QuickCommand("Pending", "07"),
    QuickCommand("Permanent", "0A"),
    QuickCommand("VIN", "0902"),
    QuickCommand("Status", "0101"),
    QuickCommand("Reset", "ATZ"),
    QuickCommand("Headers on", "ATH1"),
    QuickCommand("Headers off", "ATH0"),
)
