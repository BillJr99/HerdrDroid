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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.herdr.PaneCapture
import com.billmongan.herdrdroid.herdr.PaneReadFrame
import com.billmongan.herdrdroid.herdr.PaneReadLimits
import com.billmongan.herdrdroid.herdr.PaneReadState

/**
 * 2.2.7: the pane read requests in one pane's chat (see herdr/PaneRead.kt). Each card shows who
 * asks, which server and pane, how many lines and why, and the reply row the request was found
 * under. Nothing opens by itself: Read on the card opens the Yes/No dialog. Yes reads once, keeps
 * the capture on this phone and sends the requesting agent a short message with it attached.
 */
@Composable
internal fun PaneReadPanel(vm: AppViewModel, paneId: String) {
    val server = vm.current?.id ?: return
    val cards = vm.paneReads.cardsFor(server, paneId)
    var opened by remember(paneId) { mutableStateOf<PaneCapture?>(null) }
    var asking by remember(paneId) { mutableStateOf<String?>(null) }
    // Pending requests expire on the card, 10 minutes after they appeared.
    LaunchedEffect(paneId, cards.any { it.state == PaneReadState.PENDING }) {
        while (cards.any { it.state == PaneReadState.PENDING }) {
            vm.paneReads.expire()
            kotlinx.coroutines.delay(5_000)
        }
    }
    if (cards.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
        cards.forEach { card ->
            val approval = vm.paneReads.approvalOf(card)
            PaneReadCardView(
                requester = card.sourceName, frame = card.frame, state = card.state,
                note = card.note, delivery = card.delivery, evidence = card.evidence,
                retry = card.state == PaneReadState.PENDING && card.refusal != null,
                onRead = approval?.let { { asking = card.id } },
                onCancel = { vm.paneReads.cancel(card) },
                onClose = { vm.paneReads.close(card) },
                onOpen = card.saved?.let { name -> { opened = vm.paneReadStore.get(name) } },
            )
        }
    }
    opened?.let { c -> PaneCaptureDialog(c, onClose = { opened = null }) }
    // Opened only from the card's Read; bound to that request's id and code when it opens.
    if (opened == null) cards.firstOrNull { it.id == asking }?.let { card ->
        key(card.id) {
            val approval = remember { vm.paneReads.approvalOf(card) }
            if (approval == null) LaunchedEffect(Unit) { asking = null }
            else PaneReadApprovalDialog(
                requester = card.sourceName, frame = card.frame,
                onYes = { asking = null; vm.paneReads.approve(approval) },
                onNo = { asking = null; vm.paneReads.cancel(card) },
                onLater = { asking = null },
            )
        }
    }
}

internal fun paneReadTarget(f: PaneReadFrame) = "${f.pane} on ${f.server}"

internal fun paneReadLines(f: PaneReadFrame) =
    f.lines?.let { "the last $it lines" } ?: "the visible screen (at most ${PaneReadLimits.MAX_LINES} lines)"

/** Yes reads once; No cancels; Back or a tap outside leaves the request pending on its card. */
@Composable
internal fun PaneReadApprovalDialog(requester: String, frame: PaneReadFrame, onYes: () -> Unit, onNo: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        modifier = Modifier.semantics { contentDescription = "Approve pane read" },
        title = { Text("Read a pane on another server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Asked by: $requester", color = Palette.Ink)
                Text("Read: ${paneReadTarget(frame)}", color = Palette.Ink, fontWeight = FontWeight.SemiBold)
                Text("Lines: ${paneReadLines(frame)}", color = Palette.Ink)
                Text("Reason: " + frame.reason.ifBlank { "none given" }, color = Palette.Ink)
                Text("Yes reads it once, keeps it on this phone and sends it to the asking agent as an attachment. " +
                    "Request ${frame.code}, expires 10 minutes after it appeared.", color = Palette.Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onYes) { Text("Yes, read") } },
        dismissButton = { TextButton(onClick = onNo) { Text("No") } },
    )
}

@Composable
internal fun PaneReadCardView(
    requester: String,
    frame: PaneReadFrame,
    state: PaneReadState,
    note: String?,
    delivery: String?,
    retry: Boolean,
    /** The reply row the frame was found under. */
    evidence: String = "",
    /** Null when the request can't be read now. */
    onRead: (() -> Unit)?,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(Palette.BlueSoft).padding(12.dp).semantics { contentDescription = "Pane read card" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Pane read request", fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        Text("From: $requester", color = Palette.Ink)
        Text("Read: ${paneReadTarget(frame)}, ${paneReadLines(frame)}", color = Palette.Ink)
        if (frame.reason.isNotBlank()) Text("Reason: ${frame.reason}", color = Palette.Ink)
        if (evidence.isNotBlank()) Text("Found in the agent's reply under: $evidence", color = Palette.Muted, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace)
        Text("Untrusted text from the pane: check that you asked this agent for it.", color = Palette.Muted, fontSize = 12.sp)
        Text("Request ${frame.code}", fontFamily = FontFamily.Monospace, color = Palette.Muted, fontSize = 12.sp)
        note?.let { Text(it, color = Palette.Ink, fontSize = 13.sp) }
        delivery?.let { Text(it, color = Palette.Ink, fontSize = 13.sp) }
        Row {
            when (state) {
                PaneReadState.PENDING -> {
                    TextButton(onClick = { onRead?.invoke() }, enabled = onRead != null) { Text(if (retry) "Retry" else "Read") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                PaneReadState.READING -> {}
                else -> {
                    if (onOpen != null) TextButton(onClick = onOpen) { Text("Open") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

/** One capture: its text, with Copy and Share (the share sheet, as the debug bundle and screen export use). */
@Composable
internal fun PaneCaptureDialog(capture: PaneCapture, onClose: () -> Unit) {
    val context = LocalContext.current
    val copy = rememberCopier()
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.semantics { contentDescription = "Pane read" },
        title = { Text("Pane read ${capture.code}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${capture.pane} on ${capture.server}, ${capture.lines} lines, ${capture.time}", color = Palette.Ink)
                Text("Asked by ${capture.requester}", color = Palette.Muted, fontSize = 12.sp)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp)).background(Palette.BlueSoft)
                        .verticalScroll(rememberScrollState()).padding(8.dp),
                ) { Text(capture.body, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.Ink) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { copy(capture.text, "Pane read copied") }) { Text("Copy") }
                TextButton(onClick = { shareBytes(context, capture.text.toByteArray(Charsets.UTF_8), "herdrdroid-pane-read-${capture.name}", "text/plain", open = false) }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Pane reads, from the pane's menu: every capture kept on this phone (the last 20), newest first, and Clear. */
@Composable
internal fun PaneReadsDialog(vm: AppViewModel, onClose: () -> Unit) {
    val version = vm.paneReadStore.version
    val captures = remember(version) { vm.paneReadStore.list() }
    var opened by remember { mutableStateOf<PaneCapture?>(null) }
    opened?.let { PaneCaptureDialog(it, onClose = { opened = null }); return }
    PaneReadsList(captures, onOpen = { opened = it }, onClear = { vm.paneReadStore.clear() }, onClose = onClose)
}

@Composable
internal fun PaneReadsList(captures: List<PaneCapture>, onOpen: (PaneCapture) -> Unit, onClear: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.semantics { contentDescription = "Pane reads" },
        title = { Text("Pane reads") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (captures.isEmpty()) Text("No pane reads on this phone.", color = Palette.Muted)
                Text("Kept on this phone only, the last ${PaneReadLimits.KEEP}.", color = Palette.Muted, fontSize = 12.sp)
                captures.forEach { c ->
                    TextButton(onClick = { onOpen(c) }) { Text("${c.pane} on ${c.server} · ${c.lines} lines · ${c.time}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClear, enabled = captures.isNotEmpty()) { Text("Clear all") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
