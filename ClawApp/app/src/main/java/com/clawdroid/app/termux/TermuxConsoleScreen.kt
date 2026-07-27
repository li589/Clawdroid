package com.clawdroid.app.termux

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

data class TermuxConsoleSession(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val lines: List<String> = emptyList(),
    val running: Boolean = false
)

/**
 * Standalone Termux console UI (wired from Settings in a later stage).
 */
@Composable
fun TermuxConsoleScreen(
    modifier: Modifier = Modifier,
    bridge: TermuxBridge? = null
) {
    val context = LocalContext.current
    val termuxBridge = remember(bridge, context) {
        bridge ?: TermuxBridge(context.applicationContext)
    }

    val scope = rememberCoroutineScope()
    val sessions = remember { mutableStateListOf<TermuxConsoleSession>() }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var commandInput by remember { mutableStateOf("") }
    var statusLine by remember {
        mutableStateOf(termuxBridge.statusSummary().lines().joinToString(" · "))
    }

    LaunchedEffect(termuxBridge) {
        if (sessions.isEmpty()) {
            sessions += TermuxConsoleSession(label = "Session 1")
            selectedSessionId = sessions.first().id
        }
    }

    fun updateSession(id: String, transform: (TermuxConsoleSession) -> TermuxConsoleSession) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0) {
            sessions[index] = transform(sessions[index])
        }
    }

    fun appendLine(id: String, line: String) {
        updateSession(id) { session ->
            session.copy(lines = session.lines + line)
        }
    }

    fun runCommand(sessionId: String, command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        appendLine(sessionId, "$ $trimmed")
        updateSession(sessionId) { it.copy(running = true) }
        scope.launch {
            val result = termuxBridge.exec(trimmed)
            appendLine(sessionId, result.output.trimEnd())
            if (!result.error.isNullOrBlank()) {
                appendLine(sessionId, "[error] ${result.error}")
            }
            updateSession(sessionId) { it.copy(running = false) }
            statusLine = termuxBridge.statusSummary().lines().joinToString(" · ")
        }
    }

    val selectedSession = sessions.firstOrNull { it.id == selectedSessionId }
    val outputState = rememberLazyListState()

    LaunchedEffect(selectedSession?.lines?.size) {
        if (selectedSession != null && selectedSession.lines.isNotEmpty()) {
            outputState.animateScrollToItem(selectedSession.lines.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Termux Console",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Requires allow-external-apps=true in ~/.termux/termux.properties",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sessions.forEach { session ->
                val selected = session.id == selectedSessionId
                Surface(
                    modifier = Modifier
                        .clickable { selectedSessionId = session.id },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(session.label, style = MaterialTheme.typography.labelLarge)
                        if (session.running) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(2.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
            FilledTonalButton(
                onClick = {
                    val next = TermuxConsoleSession(label = "Session ${sessions.size + 1}")
                    sessions += next
                    selectedSessionId = next.id
                }
            ) {
                Text("+")
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                state = outputState,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val lines = selectedSession?.lines.orEmpty()
                if (lines.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "No output yet. Enter a command below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(lines, key = { it.hashCode() }) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = { Text("ls -la") },
                singleLine = true,
                enabled = selectedSession?.running != true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val sessionId = selectedSessionId ?: return@KeyboardActions
                        runCommand(sessionId, commandInput)
                        commandInput = ""
                    }
                )
            )
            FilledTonalButton(
                onClick = {
                    val sessionId = selectedSessionId ?: return@FilledTonalButton
                    runCommand(sessionId, commandInput)
                    commandInput = ""
                },
                enabled = selectedSession?.running != true && commandInput.isNotBlank()
            ) {
                Text(if (selectedSession?.running == true) "…" else "Run")
            }
        }
    }
}
