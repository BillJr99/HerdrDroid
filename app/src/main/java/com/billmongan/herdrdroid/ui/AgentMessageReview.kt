package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
                TextButton(onClick = {
                    val reachable = vm.agentMessageReachable(request).map { "${sessionName(it.pane)} on ${it.server.label}" }
                    clipboard.setText(AnnotatedString(proposalInstructions(request.code, reachable)))
                }) {
                    Text("Copy proposal instructions")
                }
            } else {
                TextButton(onClick = { review = request }) { Text("Review agent proposal") }
            }
            TextButton(onClick = { vm.dismissAgentMessage(request) }) { Text("Cancel") }
        }
    }
    review?.let { proposal ->
        val destinations = destinationLabels(vm.agentMessageDestinations(proposal))
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

/** The review's destination rows by label. IDs disambiguate panes even when tab names match; an ambiguous label is never offered. */
internal fun destinationLabels(sessions: List<AppViewModel.Session>): Map<String, AppViewModel.Session> = sessions.groupBy {
    "${sessionName(it.pane)} on ${it.server.label} · ${it.server.host}:${it.server.port} · ${it.pane.id}"
}.mapNotNull { (label, same) -> same.singleOrNull()?.let { label to it } }.toMap()

/**
 * What the source agent is told (2.1.4: with the agents on other servers the phone can reach,
 * by name and server only, so it no longer concludes that an agent such as Hermes is out of
 * reach). The frame still carries no destination; the phone user chooses one in the review.
 */
internal fun proposalInstructions(code: String, reachable: List<String>): String {
    val names = reachable.distinct()
    val known = if (names.isEmpty()) "The phone sees no agent on another server right now. It can reach only agents on servers its Fleet watcher has connected."
    else "Agents on other servers the phone can reach now (it offers only the idle ones when the user reviews your proposal):\n" +
        names.joinToString("\n") { "- $it" } + "\nIf you mean one of them, name it in your reply outside the frame."
    return """If you have a message to propose to another agent, render only the following frame in a plain fenced code block. Do not send the message yourself, select a destination, run a remote command, or include secrets.
HDMSG1 $code
HD> <standard padded Base64 of your literal UTF-8 message, in chunks of at most 40 characters; each chunk has its own HD> prefix>
HDEND $code
The message must be nonblank, at most 1024 UTF-8 bytes, and contain no controls except line feeds. The phone user will choose the destination and decide whether to send.
$known"""
}

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
                    DestinationChoices(destinations, enabled = !checking) { label ->
                        if (checking || started) return@DestinationChoices
                        selected = null
                        checking = true
                        selectFailed = false
                        scope.launch {
                            try {
                                if (onSelect(label)) selected = label else selectFailed = true
                            } finally { checking = false }
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

/**
 * Every eligible destination, one row each, at its full height (2.1.2, 2.1.1 acceptance D2). The
 * list was its own 160 dp scroll box inside the dialog's scroll: with four destinations on
 * FixtureB the fourth (b2, sorted last) sat below that box's edge with nothing to say it was there,
 * so the review looked as if it listed three. The dialog's one scroll now holds them all.
 */
@Composable
internal fun DestinationChoices(destinations: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    Column {
        destinations.forEach { label ->
            TextButton(enabled = enabled, onClick = { onPick(label) }) { Text(label) }
        }
    }
}
