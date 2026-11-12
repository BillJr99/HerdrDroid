package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.CostTier
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.costTier

private enum class Mode(val label: String) { BEST("Best available"), PICK("Pick agents"), HELPER("Helper"), NEW("New agent") }

/** Agent kinds herdr can start (`herdr agent start --kind`), in the order Bill uses them. */
val AgentKinds = listOf("claude", "codex", "pi", "omp", "opencode", "grok", "agy", "copilot", "hermes")

/**
 * One place to hand out work: the best available agent, chosen agents (one, or several to
 * compare), a short-lived helper in its own tab, or a new agent. Anything that would spend paid
 * quota (Claude or Codex) asks first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DispatchScreen(vm: AppViewModel) {
    var task by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(Mode.BEST) }
    var sensitive by rememberSaveable { mutableStateOf(false) }
    var whenIdle by rememberSaveable { mutableStateOf(true) }
    val picked = remember { mutableStateListOf<String>() }
    var kind by rememberSaveable { mutableStateOf("pi") }
    var folder by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var confirmPaid by remember { mutableStateOf<List<Pane>?>(null) }
    val ranked = vm.candidates(sensitive)

    fun send(targets: List<Pane>) {
        if (targets.isEmpty() || task.isBlank()) return
        if (targets.any { costTier(it) == CostTier.PAID } && confirmPaid == null) {
            confirmPaid = targets
            return
        }
        confirmPaid = null
        vm.dispatch(task.trim(), targets, whenIdle)
    }

    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.Tabs }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Ink) }
            Text("Hand out work", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        }
        HorizontalDivider(color = Palette.Line)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                value = task, onValueChange = { task = it },
                label = { Text(if (mode == Mode.NEW) "First message (optional)" else "The task") },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m -> ComposerChip(m.label, mode == m) { mode = m } }
            }
            if (mode == Mode.BEST || mode == Mode.PICK) {
                Toggle("Student data or Canvas", "Only the scoped OpenCode may take it; nothing else ever goes there.", sensitive) { sensitive = it; picked.clear() }
                Toggle("Send when idle", "A busy agent gets it as soon as it finishes; otherwise it is sent now.", whenIdle) { whenIdle = it }
            }
            when (mode) {
                Mode.BEST -> {
                    val top = ranked.firstOrNull()
                    if (top == null) {
                        Text(if (sensitive) "The scoped OpenCode isn't available." else "No agent is free to take this.", color = Palette.Muted)
                    } else {
                        Text("Goes to", color = Palette.Muted, fontSize = 13.sp)
                        CandidateRow(top.pane, top.why, top.tier, selected = true) {}
                        if (ranked.size > 1) Text("Next in line: " + ranked.drop(1).take(3).joinToString(", ") { it.pane.agentName }, color = Palette.Muted, fontSize = 12.5.sp)
                        Button(onClick = { send(listOf(top.pane)) }, enabled = task.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Text("Send to ${top.pane.agentName}")
                        }
                    }
                }
                Mode.PICK -> {
                    Text("Pick one, or several to compare their answers side by side.", color = Palette.Muted, fontSize = 13.sp)
                    ranked.forEach { c ->
                        CandidateRow(c.pane, c.why, c.tier, selected = c.pane.id in picked) {
                            if (c.pane.id in picked) picked.remove(c.pane.id) else picked.add(c.pane.id)
                        }
                    }
                    val targets = ranked.map { it.pane }.filter { it.id in picked }
                    Button(onClick = { send(targets) }, enabled = task.isNotBlank() && targets.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text(if (targets.size > 1) "Send to ${targets.size} and compare" else "Send")
                    }
                }
                Mode.HELPER, Mode.NEW -> {
                    Text(
                        if (mode == Mode.HELPER) "A helper runs in its own tab; close it from the list when it is done."
                        else "Starts an agent in a new tab. It is not in the boot launcher, so it won't come back after a reboot.",
                        color = Palette.Muted, fontSize = 13.sp,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentKinds.forEach { k -> ComposerChip(k, kind == k) { kind = k } }
                    }
                    val defaultLabel = if (mode == Mode.HELPER) vm.nextHelperLabel(kind) else "$kind-${(vm.panes.count { it.agent == kind } + 1)}"
                    val defaultFolder = if (mode == Mode.HELPER) (vm.panes.firstOrNull { it.agent == kind }?.cwd ?: "~/agents/$kind") else "~/agents/$defaultLabel"
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Tab label") }, placeholder = { Text(defaultLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = folder, onValueChange = { folder = it }, label = { Text("Folder") }, placeholder = { Text(defaultFolder) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val paid = kind == "claude" || kind == "codex"
                    if (paid) Text("This uses paid quota.", color = Palette.OrangeInk, fontSize = 13.sp)
                    Button(
                        onClick = {
                            vm.startAgent(kind, folder.ifBlank { defaultFolder }, label.ifBlank { defaultLabel }, task.ifBlank { null })
                        },
                        enabled = vm.starting == null && (mode == Mode.NEW || task.isNotBlank()),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) { Text(if (vm.starting != null) "Starting ${vm.starting}…" else if (mode == Mode.HELPER) "Start helper" else "Start agent") }
                }
            }
        }
    }

    confirmPaid?.let { targets ->
        AlertDialog(
            onDismissRequest = { confirmPaid = null },
            title = { Text("Use paid quota?") },
            text = { Text(targets.filter { costTier(it) == CostTier.PAID }.joinToString(", ") { it.agentName } + " spends Claude or Codex quota. Send anyway?") },
            confirmButton = { Button(onClick = { confirmPaid = null; vm.dispatch(task.trim(), targets, whenIdle) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirmPaid = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Toggle(title: String, about: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Ink, fontSize = 15.sp)
            Text(about, color = Palette.Muted, fontSize = 12.5.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun CandidateRow(p: Pane, why: String, tier: CostTier, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Palette.BlueSoft else Palette.Card,
        border = BorderStroke(1.dp, if (selected) Palette.Blue else Palette.Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentTile(p.initial, p.status, size = 36)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("${p.agentName} · ${p.folder}", color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(why, color = Palette.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.background(
                    when (tier) { CostTier.FREE -> Palette.GreenSoft; CostTier.MID -> Palette.Gray; CostTier.PAID -> Palette.OrangeSoft },
                    RoundedCornerShape(8.dp),
                ).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(tier.label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = when (tier) { CostTier.FREE -> Palette.Green; CostTier.MID -> Palette.Muted; CostTier.PAID -> Palette.OrangeInk })
            }
            if (selected) Box(Modifier.padding(start = 8.dp).size(8.dp).background(Palette.Blue, CircleShape))
        }
    }
}
