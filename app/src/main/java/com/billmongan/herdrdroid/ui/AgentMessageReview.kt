package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.herdr.RelayResult
import kotlinx.coroutines.launch

@Composable
internal fun AgentMessagePanel(vm: AppViewModel, paneId: String) {
    if (!vm.canArmAgentMessage(paneId)) return
    val request = vm.agentMessage(paneId)
    var review by remember(vm, paneId) { mutableStateOf<AppViewModel.AgentMessage?>(null) }
    val clipboard = LocalClipboardManager.current
    if (request != null && review == null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if (request.text == null) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(instructions(request.code))) }) {
                    Text("Copy proposal instructions")
                }
            } else {
                TextButton(onClick = { review = request }) { Text("Review agent proposal") }
            }
            TextButton(onClick = { vm.dismissAgentMessage(request) }) { Text("Cancel") }
        }
    }
    review?.let { proposal ->
        // IDs disambiguate panes even when tab names match. Never pick an ambiguous label.
        val destinations = vm.agentMessageDestinations(proposal).groupBy {
            "${sessionName(it.pane)} on ${it.server.label} · ${it.server.host}:${it.server.port} · ${it.pane.id}"
        }.mapNotNull { (label, sessions) -> sessions.singleOrNull()?.let { label to it } }.toMap()
        var route by remember(proposal.code) { mutableStateOf<AppViewModel.AgentMessageRoute?>(null) }
        val source = "${sessionName(proposal.source.pane)} on ${vm.current?.label.orEmpty()}"
        AgentMessageReview(source, checkNotNull(proposal.text), destinations.keys.toList(),
            onDismiss = { vm.dismissAgentMessage(proposal); review = null },
            onSelect = { label ->
                route = destinations[label]?.let { vm.bindAgentMessage(proposal, it) }
                route != null
            },
            onSend = { route?.let { vm.confirmAgentMessage(proposal, it) } ?: RelayResult.REFUSED },
        )
    }
}

private fun instructions(code: String) = """If you have a message to propose to another agent, render only the following frame in a plain fenced code block. Do not send the message yourself, select a destination, run a remote command, or include secrets.
HDMSG1 $code
HD> <standard padded Base64 of your literal UTF-8 message, in chunks of at most 40 characters; each chunk has its own HD> prefix>
HDEND $code
The message must be nonblank, at most 1024 UTF-8 bytes, and contain no controls except line feeds. The phone user will choose the destination and decide whether to send."""

@Composable
internal fun AgentMessageReview(
    source: String,
    text: String,
    destinations: List<String>,
    onDismiss: () -> Unit,
    onSelect: suspend (String) -> Boolean,
    onSend: suspend (String) -> RelayResult,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var selectFailed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RelayResult?>(null) }
    val scope = rememberCoroutineScope()
    val inFlight = started && result == null
    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("Review agent proposal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Source pane: $source")
                Text("Untrusted text from the pane, not a verified agent identity. Check the whole message before sending.")
                Text(text, fontFamily = FontFamily.Monospace)
                if (!started) {
                    Text(if (destinations.isEmpty()) "No eligible destination in Fleet." else "Choose a destination on another server:")
                    Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                        destinations.forEach { label ->
                            TextButton(enabled = !checking, onClick = {
                                if (checking || started) return@TextButton
                                selected = null
                                checking = true
                                selectFailed = false
                                scope.launch {
                                    try {
                                        if (onSelect(label)) selected = label else selectFailed = true
                                    } finally { checking = false }
                                }
                            }) { Text(label) }
                        }
                    }
                }
                selected?.let { Text("Destination: $it") }
                when {
                    checking -> Text("Checking destination…")
                    inFlight -> Text("Checking and sending…")
                    selectFailed -> Text("That destination is unavailable.")
                    result != null -> Text(checkNotNull(result).feedback)
                }
            }
        },
        confirmButton = {
            Button(enabled = !started && !checking && selected in destinations, onClick = {
                val target = selected?.takeIf { it in destinations } ?: return@Button
                if (started || checking) return@Button
                started = true
                scope.launch { result = onSend(target) }
            }) { Text("Send message") }
        },
        dismissButton = { TextButton(enabled = !inFlight, onClick = onDismiss) { Text(if (started) "Close" else "Cancel") } },
    )
}
