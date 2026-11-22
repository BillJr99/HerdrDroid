package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.herdr.PaneControls
import com.billmongan.herdrdroid.herdr.PastSession
import com.billmongan.herdrdroid.herdr.ageText

/** One row of a pane-control dialog: a label, a line under it, and the "(now)" mark. */
@Composable
private fun ControlRow(label: String, note: String?, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = if (current) Palette.BlueSoft else Palette.Card,
        border = BorderStroke(1.dp, if (current) Palette.Blue else Palette.Line),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled && !current, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(label + if (current) "  (now)" else "", color = if (enabled || current) Palette.Ink else Palette.Muted,
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, color = Palette.Muted, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * 2.2.0: the permission mode, as the agent's footer shows it, and the modes the app can switch to.
 * A mode that approves every tool is offered only where Claude's menu already reached it, and asks
 * first. The switch is confirmed by reading the footer again.
 */
@Composable
fun ModeDialog(agentName: String, spec: PaneControls.ModeSpec, current: String?, idle: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var confirming by remember { mutableStateOf<PaneControls.ModeOption?>(null) }
    val can = PaneControls.canSwitch(spec, current)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mode for $agentName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val now = spec.options.firstOrNull { it.name == current }?.label ?: current ?: "not shown"
                Text("Now: $now", color = Palette.Muted, fontSize = 13.sp)
                if (!idle) Text("$agentName is busy. The mode can be changed once it's idle.", color = Palette.OrangeInk, fontSize = 14.sp)
                if (!can && current != null) Text("The app doesn't switch away from this mode; change it at the computer.", color = Palette.OrangeInk, fontSize = 14.sp)
                PaneControls.reachable(spec, current).forEach { o ->
                    ControlRow(o.label, if (o.approvesAll) "Asks you first" else null, o.name == current, idle && can) {
                        if (o.approvesAll) confirming = o else { onDismiss(); onPick(o.name) }
                    }
                }
                Text("The app presses ${keyName(spec.key)} until the footer shows the mode you picked, and goes back to where it started if it can't get there.",
                    color = Palette.Muted, fontSize = 12.5.sp)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    confirming?.let { o ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Turn on ${o.label.substringBefore(" (")}?") },
            text = { Text("$agentName will run every tool without asking you, until you change the mode back.") },
            confirmButton = { TextButton(onClick = { confirming = null; onDismiss(); onPick(o.name) }) { Text("Turn it on", color = Palette.OrangeInk) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

private fun keyName(key: String) = when (key) { "shift+tab" -> "Shift+Tab"; else -> key }

/** 2.2.0: the thinking level, set with the agent's own command and confirmed from its screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThinkingDialog(agentName: String, spec: PaneControls.ThinkingSpec, current: String?, idle: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thinking for $agentName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Now: " + (current ?: "not shown"), color = Palette.Muted, fontSize = 13.sp)
                if (!idle) Text("$agentName is busy. The thinking level can be changed once it's idle.", color = Palette.OrangeInk, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    spec.levels.forEach { l -> ComposerChip(l, current.equals(l, ignoreCase = true)) { if (idle) { onDismiss(); onPick(l) } } }
                }
                Text("Sends ${spec.command("<level>")} and checks the screen for the change.", color = Palette.Muted, fontSize = 12.5.sp)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * 2.2.0 (Bill: "a list of sessions for that pane and let me switch between them", "a button to create
 * a new session for that agent"): the pane's sessions, newest first, the one its terminal is in
 * marked, and New session. [canSwitch]: the agent can resume that session by id, or its picker opens.
 */
@Composable
fun SessionsDialog(
    agentName: String,
    sessions: List<PastSession>?,
    problem: String?,
    isCurrent: (PastSession) -> Boolean,
    canSwitch: Boolean,
    switchNote: String?,
    herdrOutOfDate: Boolean,
    idle: Boolean,
    onNew: () -> Unit,
    onPick: (PastSession) -> Unit,
    onPicker: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sessions for $agentName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (herdrOutOfDate) Text("herdr out of date: herdr hasn't heard of the session the terminal is in yet. The chat follows the terminal.",
                    color = Palette.OrangeInk, fontSize = 12.5.sp)
                if (!idle) Text("$agentName is busy. Sessions can be changed once it's idle.", color = Palette.OrangeInk, fontSize = 14.sp)
                Button(onClick = { onDismiss(); onNew() }, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("＋ New session") }
                when {
                    problem != null -> Text(problem, color = Palette.OrangeInk, fontSize = 13.sp)
                    sessions == null -> Text("Loading sessions…", color = Palette.Muted, fontSize = 13.sp)
                    sessions.isEmpty() -> Text("No sessions in this folder yet.", color = Palette.Muted, fontSize = 13.sp)
                }
                sessions.orEmpty().take(30).forEach { s ->
                    val when_ = if (s.whenSec > 0) ageText((System.currentTimeMillis() / 1000 - s.whenSec).coerceAtLeast(0)) + " ago" else null
                    ControlRow(s.title ?: "(no title)", when_, isCurrent(s), idle && canSwitch) { onDismiss(); onPick(s) }
                }
                switchNote?.let { Text(it, color = Palette.Muted, fontSize = 12.5.sp) }
                onPicker?.let { open -> TextButton(onClick = { onDismiss(); open() }, enabled = idle) { Text("Open $agentName's own session picker") } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
