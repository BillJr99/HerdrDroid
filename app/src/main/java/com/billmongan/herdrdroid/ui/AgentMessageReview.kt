package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.AppViewModel.CrossServerState

/**
 * 2.1.5/2.2.0: the cross-server cards in one pane's chat. Each shows the source pane, the
 * destination resolved against the reachable agents (with a choice when the frame's name matches
 * more than one or none) and the decoded message, with Approve and Cancel (2.2.2: Retry, Force Send and Cancel after a busy refusal). 2.2.0: the newest
 * pending card also opens a Yes/No dialog ([CrossServerApprovalDialog]); the user never types the
 * code.
 */
@Composable
internal fun CrossServerPanel(vm: AppViewModel, paneId: String) {
    val cards = vm.crossServerCardsFor(paneId)
    // 2.2.2: the proposal Force Send was tapped on, bound then; its dialog asks before anything is forced.
    var forcing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<AppViewModel.CrossServerApproval, AppViewModel.Session>?>(null) }
    if (cards.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = crossServerPanelMax()).verticalScroll(rememberScrollState())) {
        cards.forEach { card ->
            val choices = destinationLabels(vm.crossServerChoices(card))
            val approval = vm.crossServerApprovalOf(card)
            CrossServerCardView(
                source = crossServerSource(vm, card), requested = card.requested, destination = crossServerDestination(card),
                text = card.text, code = card.code, state = card.state, note = card.note,
                choices = if (card.state == CrossServerState.PENDING) choices.keys.toList() else emptyList(),
                onChoose = { label -> choices[label]?.let { vm.chooseCrossServerDestination(card, it) } },
                onApprove = approval?.let { a -> { vm.approveCrossServer(a); Unit } },
                onCancel = { vm.cancelCrossServerCard(card) },
                onClose = { vm.closeCrossServerCard(card) },
                retry = card.state == CrossServerState.PENDING && card.refusal != null,
                onForce = approval?.takeIf { vm.crossServerForceOffered(card) }?.let { a ->
                    card.choice?.let { to -> { forcing = a to to } }
                },
            )
        }
    }
    forcing?.let { (approval, to) ->
        CrossServerForceDialog(
            agent = sessionName(to.pane), server = to.server.label,
            onSend = { forcing = null; vm.forceCrossServer(approval) },
            onCancel = { forcing = null },
        )
    }
    // One dialog at a time, for the newest proposal; the others wait on their cards.
    vm.crossServerDialogFor(paneId)?.let { card ->
        androidx.compose.runtime.key(card.id) {
            // Bound once, when this proposal's dialog opens: Yes approves only this id, code and destination.
            val approval = androidx.compose.runtime.remember { vm.crossServerApprovalOf(card) }
            if (approval != null) {
                val waiting = cards.count { it.id != card.id && it.state == CrossServerState.PENDING }
                CrossServerApprovalDialog(
                    source = crossServerSource(vm, card), destination = crossServerDestination(card) ?: card.requested,
                    text = card.text, code = card.code, waiting = waiting,
                    onYes = { vm.approveCrossServer(approval) },
                    onNo = { vm.cancelCrossServerCard(card) },
                    onLater = { vm.deferCrossServerDialog(card) },
                )
            }
        }
    }
}

/**
 * 2.2.0 step 1: the cards' box was up to 420 dp above the chat and the message box. In a window
 * about 470 dp tall (a phone with the keyboard up) that left the message box no height at all, so
 * nothing could be typed or sent there. The box now takes at most about a third of the window
 * above the keyboard, and scrolls.
 */
@Composable
private fun crossServerPanelMax(): androidx.compose.ui.unit.Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val ime = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val window = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    return ((window - ime) * 0.34f).coerceIn(120.dp, 320.dp)
}

private fun crossServerSource(vm: AppViewModel, card: AppViewModel.CrossServerCard) =
    "${sessionName(card.source.pane)} on ${vm.current?.label.orEmpty()}"

private fun crossServerDestination(card: AppViewModel.CrossServerCard) =
    card.choice?.let { "${sessionName(it.pane)} on ${it.server.label}" }

/**
 * 2.2.0: Yes or No for one proposal, with the source agent, the destination agent and server, and
 * the whole decoded message (scrollable). Yes sends; No cancels; Back or a tap outside leaves the
 * proposal pending on its card. Shown only while the pane is open and the app is in front and
 * unlocked (see AppViewModel.crossServerDialogFor).
 */
@Composable
internal fun CrossServerApprovalDialog(
    source: String,
    destination: String,
    text: String,
    code: String,
    waiting: Int,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onLater: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onLater,
        modifier = Modifier.semantics { contentDescription = "Approve cross-server message" },
        title = { Text("Send this message to another server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("From: $source", color = Palette.Ink)
                Text("To: $destination", color = Palette.Ink, fontWeight = FontWeight.SemiBold)
                Text("Untrusted text from the agent. Read the whole message first. Proposal $code, expires 10 minutes after it appeared.",
                    color = Palette.Muted, fontSize = 12.sp)
                if (waiting > 0) Text(
                    if (waiting == 1) "1 more proposal is waiting on its card." else "$waiting more proposals are waiting on their cards.",
                    color = Palette.Muted, fontSize = 12.sp)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(8.dp)).background(Palette.BlueSoft)
                        .verticalScroll(rememberScrollState()).padding(10.dp)
                        .semantics { contentDescription = "Cross-server message text" },
                ) {
                    Text(text, fontFamily = FontFamily.Monospace, color = Palette.Ink)
                }
            }
        },
        confirmButton = { TextButton(onClick = onYes) { Text("Yes, send") } },
        dismissButton = { TextButton(onClick = onNo) { Text("No") } },
    )
}

/**
 * 2.2.2: asks before Force Send types a message into a busy agent. Only Send anyway forces; Cancel,
 * Back or a tap outside leaves the proposal pending on its card with Retry, Force Send and Cancel.
 */
@Composable
internal fun CrossServerForceDialog(agent: String, server: String, onSend: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.semantics { contentDescription = "Force send cross-server message" },
        title = { Text("Force send?") },
        text = { Text("$agent on $server is busy. Send anyway? It will be typed in while it works.", color = Palette.Ink) },
        confirmButton = { TextButton(onClick = onSend) { Text("Send anyway") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
internal fun CrossServerCardView(
    source: String,
    requested: String,
    destination: String?,
    text: String,
    code: String,
    state: CrossServerState,
    note: String?,
    choices: List<String>,
    onChoose: (String) -> Unit,
    /** Null while the card has no destination or is no longer pending. */
    onApprove: (() -> Unit)?,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    /** 2.2.2: pending again after a refused try: the first button is Retry (the same checks as Approve). */
    retry: Boolean = false,
    /** 2.2.2: Force Send, only after a refusal for a busy destination (working or background work); null hides it. */
    onForce: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(Palette.BlueSoft).padding(12.dp)
            .semantics { contentDescription = "Cross-server message card" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Cross-server message", fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        Text("From: $source", color = Palette.Ink)
        Text("To: " + (destination ?: "not chosen (the agent asked for \"$requested\")"), color = Palette.Ink)
        Text(text, fontFamily = FontFamily.Monospace, color = Palette.Ink)
        Text("Proposal $code", fontFamily = FontFamily.Monospace, color = Palette.Muted, fontSize = 12.sp)
        when (state) {
            CrossServerState.PENDING -> {
                if (choices.isNotEmpty()) {
                    Text(if (destination == null) "Choose a destination:" else "Change the destination:", color = Palette.Muted, fontSize = 13.sp)
                    DestinationChoices(choices, enabled = true, onPick = onChoose)
                }
                Text("Untrusted text from the pane. Check the whole message first. Expires 10 minutes after it appeared.",
                    color = Palette.Muted, fontSize = 12.sp)
            }
            else -> {}
        }
        note?.let { Text(it, color = Palette.Ink, fontSize = 13.sp) }
        Row {
            when (state) {
                CrossServerState.PENDING -> {
                    TextButton(onClick = { onApprove?.invoke() }, enabled = onApprove != null) { Text(if (retry) "Retry" else "Approve") }
                    if (retry && onForce != null) TextButton(onClick = onForce) { Text("Force Send") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                CrossServerState.SENDING -> {}
                else -> TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/** The destination rows by label. IDs disambiguate panes even when tab names match; an ambiguous label is never offered. */
internal fun destinationLabels(sessions: List<AppViewModel.Session>): Map<String, AppViewModel.Session> = sessions.groupBy {
    "${sessionName(it.pane)} on ${it.server.label} · ${it.server.host}:${it.server.port} · ${it.pane.id}"
}.mapNotNull { (label, same) -> same.singleOrNull()?.let { label to it } }.toMap()

/**
 * Every eligible destination, one row each, at its full height (2.1.2, 2.1.1 acceptance D2). The
 * list was its own 160 dp scroll box inside the dialog's scroll: with four destinations on
 * FixtureB the fourth (b2, sorted last) sat below that box's edge with nothing to say it was there,
 * so the review looked as if it listed three.
 */
@Composable
internal fun DestinationChoices(destinations: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    Column {
        destinations.forEach { label ->
            TextButton(enabled = enabled, onClick = { onPick(label) }) { Text(label) }
        }
    }
}
