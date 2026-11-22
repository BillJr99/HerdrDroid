package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * 2.1.5: the cross-server cards in one pane's chat. Each shows the source pane, the destination
 * resolved against the reachable agents (with a choice when the frame's name matches more than
 * one or none), the decoded message and the code. Nothing is sent from here: the user confirms
 * by typing the code in this pane's composer (see [composerSend]), or taps Cancel.
 */
@Composable
internal fun CrossServerPanel(vm: AppViewModel, paneId: String) {
    val cards = vm.crossServerCardsFor(paneId)
    if (cards.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        cards.forEach { card ->
            val choices = destinationLabels(vm.crossServerChoices(card))
            val source = "${sessionName(card.source.pane)} on ${vm.current?.label.orEmpty()}"
            val destination = card.choice?.let { "${sessionName(it.pane)} on ${it.server.label}" }
            CrossServerCardView(
                source = source, requested = card.requested, destination = destination, text = card.text,
                code = card.code, state = card.state, note = card.note,
                choices = if (card.state == CrossServerState.PENDING) choices.keys.toList() else emptyList(),
                onChoose = { label -> choices[label]?.let { vm.chooseCrossServerDestination(card, it) } },
                onCancel = { vm.cancelCrossServerCard(card) },
                onClose = { vm.closeCrossServerCard(card) },
            )
        }
    }
}

/**
 * The composer's send for one pane (2.1.5). Text that is exactly a card's code (trimmed, any case)
 * confirms that card and is never typed into the pane; anything else goes to [send] as before.
 * Only this composer calls it: pane output is never checked for a code.
 */
internal fun composerSend(vm: AppViewModel, paneId: String, text: String, send: (String) -> Unit) {
    if (vm.crossServerCodeReply(paneId, text)) return
    send(text)
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
    onCancel: () -> Unit,
    onClose: () -> Unit,
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
        Text("Code: $code", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        when (state) {
            CrossServerState.PENDING -> {
                if (choices.isNotEmpty()) {
                    Text(if (destination == null) "Choose a destination:" else "Change the destination:", color = Palette.Muted, fontSize = 13.sp)
                    DestinationChoices(choices, enabled = true, onPick = onChoose)
                }
                Text("To send it, type $code in this chat's message box and tap Send. The code is not passed to the agent.",
                    color = Palette.Muted, fontSize = 13.sp)
                Text("Untrusted text from the pane. Check the whole message first. Expires 10 minutes after it appeared.",
                    color = Palette.Muted, fontSize = 12.sp)
            }
            else -> {}
        }
        note?.let { Text(it, color = Palette.Ink, fontSize = 13.sp) }
        Row {
            when (state) {
                CrossServerState.PENDING -> TextButton(onClick = onCancel) { Text("Cancel") }
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
