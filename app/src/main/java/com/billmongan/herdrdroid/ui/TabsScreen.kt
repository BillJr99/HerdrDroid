package com.billmongan.herdrdroid.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.QuotaReading
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Filter(val label: String) { ALL("All"), NEEDS("Needs you"), WORKING("Working"), IDLE("Idle") }

private fun Filter.matches(p: Pane) = when (this) {
    Filter.ALL -> true
    Filter.NEEDS -> p.status == Status.NEEDS_YOU
    Filter.WORKING -> p.status == Status.WORKING
    Filter.IDLE -> p.status == Status.IDLE || p.status == Status.DONE || p.status == Status.UNKNOWN
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TabsScreen(vm: AppViewModel) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val scope = rememberCoroutineScope()

    // Coming back to the card list checks any usage reading that is due, without waiting for the timer.
    LaunchedEffect(Unit) { vm.refreshQuotasSoon(force = false) }

    // Panes every 3 seconds; the one-line previews every 9.
    LaunchedEffect(vm.current?.id) {
        var tick = 0
        while (true) {
            vm.refreshPanes()
            if (tick % 3 == 0) vm.refreshPreviews()
            tick++
            delay(3_000)
        }
    }

    // Agents with subagents or background shells still running show as working.
    // Done turns idle once opened here or after 30 minutes. Sorted: needs you, working, done, idle.
    val order = listOf(Status.NEEDS_YOU, Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN)
    val panes = vm.panes.map { p -> vm.shownStatus(p).let { s -> if (s != p.status) p.copy(status = s) else p } }
        .sortedWith(compareBy({ order.indexOf(it.status) }, { it.id }))
    var forwardingResult by remember { mutableStateOf<String?>(null) }
    var confirmStopAll by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Surface(color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back to the list of machines (disconnects, like the back gesture).
                IconButton(onClick = { vm.disconnect() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to servers", tint = Palette.Ink)
                }
                Column(Modifier.weight(1f)) {
                    // The machine's name opens a menu of the other machines, to switch quickly.
                    Box {
                        var machines by remember { mutableStateOf(false) }
                        Row(Modifier.clickable { machines = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.current?.label ?: "", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Text(" ▾", fontSize = 18.sp, color = Palette.Muted)
                        }
                        DropdownMenu(expanded = machines, onDismissRequest = { machines = false }) {
                            vm.servers.filter { it.id != vm.current?.id }.forEach { s ->
                                val h = vm.serverHealth[s.id]
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(s.label)
                                            h?.let { Text(it.line, fontSize = 12.sp, color = Palette.Muted) }
                                        }
                                    },
                                    onClick = { machines = false; vm.switchServer(s) },
                                )
                            }
                            DropdownMenuItem(text = { Text("All servers…") }, onClick = { machines = false; vm.disconnect() })
                        }
                    }
                    LinkLabel(vm, " · ${panes.size} tabs · v${com.billmongan.herdrdroid.BuildConfig.VERSION_NAME}")
                }
                IconButton(onClick = { scope.launch { vm.refreshPanes(); vm.refreshPreviews() }; vm.refreshQuotasSoon() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Palette.Ink)
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Palette.Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Files") }, onClick = { menu = false; vm.openFiles("~") })
                        DropdownMenuItem(text = { Text("Search conversations") }, onClick = { menu = false; vm.screen = Screen.Search })
                        DropdownMenuItem(
                            text = { Text("Stop everything…", color = Palette.OrangeInk) },
                            enabled = panes.any { it.status == Status.WORKING && it.agent != null },
                            onClick = { menu = false; confirmStopAll = true },
                        )
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; vm.screen = Screen.Settings(Screen.Tabs) })
                    }
                }

            }
        }
        ServerBanners(vm)
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEach { f ->
                val n = panes.count { f.matches(it) }
                val needs = f == Filter.NEEDS && n > 0
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text("${f.label} $n", fontWeight = FontWeight.SemiBold) },
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == f, borderColor = Palette.Line),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = if (needs) Palette.OrangeSoft else Palette.Card,
                        labelColor = if (needs) Palette.OrangeInk else Palette.Ink,
                        selectedContainerColor = Palette.Ink,
                        selectedLabelColor = Palette.Ground,
                    ),
                )
            }
        }
        PullRefresh({ vm.refreshPanes(); vm.refreshPreviews(); vm.refreshQuotasSoon() }, Modifier.weight(1f)) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Something shared from another app, waiting for an agent.
            vm.pendingShare?.let { sh ->
                item(key = "_share") {
                    var picking by remember { mutableStateOf(false) }
                    Surface(shape = RoundedCornerShape(16.dp), color = Palette.BlueSoft, border = BorderStroke(1.dp, Palette.Blue)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Shared with HerdrDroid", color = Palette.Blue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                listOfNotNull(sh.text?.takeIf { it.isNotBlank() }, sh.uri?.let { "A file (it will be uploaded to ~/.herdrdroid/inbox)" }).joinToString("\n"),
                                color = Palette.Ink, fontSize = 13.5.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { picking = true }) { Text("Send to an agent…") }
                                TextButton(onClick = { vm.discardShare() }) { Text("Discard") }
                            }
                        }
                    }
                    if (picking) ForwardPicker(vm, exclude = null, onPick = { to -> picking = false; vm.sendShareTo(to) }, onDismiss = { picking = false })
                }
            }
            // Helpers that finished and were closed: their last reply, kept until dismissed.
            if (vm.helperResults.isNotEmpty()) {
                item(key = "_helpers") {
                    Text("Helper results", color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
                items(vm.helperResults.reversed(), key = { "h:" + it.id }) { r ->
                    Surface(shape = RoundedCornerShape(16.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Blue)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${r.agent} · ${r.label}", color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Task: " + r.task.lines().first(), color = Palette.Muted, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                                MarkdownText(r.reply.ifBlank { "The helper finished without a written reply." }, 14f, vm.readingFont.family)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(r.reply)) }) { Text("Copy") }
                                TextButton(onClick = { forwardingResult = "${r.agent} (${r.label}) answered \"${r.task.lines().first().take(80)}\":\n\n${r.reply}" }) { Text("Send to…") }
                                TextButton(onClick = { vm.dismissHelperResult(r) }) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }
            // Agents on the other watched servers that are waiting on you.
            val elsewhere = vm.fleetStates.values.flatMap { st -> st.panes.filter { it.status == Status.NEEDS_YOU }.map { st.server to it } }
            if (elsewhere.isNotEmpty() && filter != Filter.IDLE && filter != Filter.WORKING) {
                item(key = "_elsewhere") {
                    Text("Needs you on other servers", color = Palette.OrangeInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
                items(elsewhere, key = { "x:" + it.first.id + it.second.id }) { (server, p) ->
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = Palette.Card, border = BorderStroke(2.dp, Palette.Orange),
                        modifier = Modifier.fillMaxWidth().clickable { vm.switchTo(server.id, p.id) },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AgentTile(p.initial, p.status, size = 36)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text("${p.agentName} · ${p.folder}", color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("on ${server.label} · tap to switch", color = Palette.Muted, fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }
            val shown = panes.filter { filter.matches(it) }
            val byWorkspace = shown.groupBy { it.workspaceId }
            byWorkspace.forEach { (ws, list) ->
                if (byWorkspace.size > 1) {
                    item(key = "_ws:$ws") {
                        // Each workspace rolls up to its most urgent agent state.
                        val urgent = listOf(Status.NEEDS_YOU, Status.WORKING, Status.DONE, Status.IDLE, Status.UNKNOWN).first { s -> list.any { it.status == s } || s == Status.UNKNOWN }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Text("Workspace $ws  ", color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            StatusPill(urgent)
                        }
                    }
                }
                items(list, key = { it.id }) { p ->
                    PaneCard(
                        p, vm.previews[p.id].orEmpty(), model = vm.modelLine(p.id), quota = vm.quotaFor(p),
                        checking = false,
                        underneath = vm.workCounts[p.id] ?: 0,
                        git = vm.gitInfo[p.cwd],
                        onClose = if (p.tabLabel.startsWith("helper-")) ({ vm.closeHelper(p) }) else null,
                    ) { vm.openPane(p.id) }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
        }
    }
    // Hand out work: the main action, as a button that never crowds the server's name.
    androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = { vm.screen = Screen.Dispatch },
        text = { Text("Hand out", fontWeight = FontWeight.SemiBold) },
        icon = { Text("↗", fontSize = 18.sp) },
        containerColor = Palette.Blue, contentColor = Palette.OnBlue,
        modifier = Modifier.align(Alignment.BottomEnd).systemBarsPadding().padding(18.dp),
    )
    }
    if (confirmStopAll) {
        val working = panes.filter { it.status == Status.WORKING && it.agent != null }
        AlertDialog(
            onDismissRequest = { confirmStopAll = false },
            title = { Text("Stop every working agent?") },
            text = {
                Text("Each of these gets its own stop key (Esc, or Ctrl+C for Hermes), which ends its current turn:\n\n" +
                    working.joinToString("\n") { "• ${it.agentName} · ${it.folder}" })
            },
            confirmButton = {
                TextButton(onClick = { confirmStopAll = false; working.forEach { vm.stopPane(it) } }) { Text("Stop ${working.size}", color = Palette.OrangeInk) }
            },
            dismissButton = { TextButton(onClick = { confirmStopAll = false }) { Text("Cancel") } },
        )
    }
    forwardingResult?.let { t ->
        ForwardPicker(vm, exclude = null, onPick = { to -> forwardingResult = null; vm.forward(t, null, to) }, onDismiss = { forwardingResult = null })
    }
}

@Composable
internal fun PaneCard(
    p: Pane, preview: String, model: String?, quota: QuotaReading?, checking: Boolean,
    underneath: Int = 0,
    git: com.billmongan.herdrdroid.herdr.GitInfo? = null,
    onClose: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val needs = p.status == Status.NEEDS_YOU
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Palette.Card,
        border = if (needs) BorderStroke(2.dp, Palette.Orange) else BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        // Just what matters at a glance: who and where (the title), how it is (the status), its
        // model and reasoning (blue), and its usage limits, one per line.
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            AgentTile(p.initial, p.status, size = 40)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (p.folder.isNotBlank()) "${p.agentName} · ${p.folder}" else p.agentName,
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(p.status)
                    if (onClose != null && p.status != Status.WORKING) {
                        Text("✕", color = Palette.Muted, fontSize = 15.sp, modifier = Modifier.clickable { onClose() }.padding(start = 2.dp))
                    }
                }
                model?.let { Text(it, color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                quota?.let { QuotaChips(it, Modifier.padding(top = 2.dp)) }
            }
        }
    }
}

@Composable
private fun Badge(text: String, ink: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Text(text, color = ink, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
        modifier = Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
}

/**
 * The card's one line of the agent's latest output, without the status chrome agents draw at the
 * bottom of their screens (spinners, "esc to interrupt", key hints, mode lines), which the status
 * pill already says. Empty when only chrome is left.
 */
fun cardLine(preview: String): String {
    val t = preview.trim()
    if (t.isEmpty()) return ""
    val chrome = listOf(
        Regex("""^[✶✳✻✽✢✺✹✸✷·*•◐◓◑◒⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏⎿⏺]\s*\S+…"""),
        Regex("""(?i)esc to (interrupt|cancel)|for shortcuts|ctrl\+[a-z] to|shift\+tab|auto mode|bypass permissions|accept edits on|plan mode"""),
        Regex("""(?i)update installed|/restart|restart to (apply|update)|remote control|tip:|press .* to|tab to |context left|auto-compact|tokens\b|↓ ?[\d.]+k?"""),
        Regex("""(?i)^\(?\d+s\s*·"""),
        Regex("""^[»›‖■❯>$%#]\s*$"""),
        Regex("""^[»›‖■⏵]"""),
        Regex("""^[\s─━═│┃╭╮╰╯┌┐└┘├┤┬┴┼\-_|]+(\S{0,20})?[\s─━═│┃╭╮╰╯┌┐└┘\-_|]*$"""),
        // Status lines: context bars and percentages, update notices, agents' own footers.
        Regex("""[█▓▒░▏▎▍▌▋▊▉■□▪▫]{2,}"""),
        Regex("""(?i)\b(context|ctx)\b\s*[:=]?\s*[\d~\-]|\d+(\.\d+)?%\s*(context|ctx|used|left|of)|~?\d+(\.\d+)?[kKmM]\s*/\s*\d+(\.\d+)?[kKmM]|\d+(\.\d+)?%/\d"""),
        Regex("""(?i)update available|new version|version \d+\.\d+.* available|upgrade (available|to)|run .*update|\bv\d+\.\d+\.\d+\b"""),
        Regex("""^\s*(π\s*>|☤|⬢|\?\s)"""),
        Regex("""(?i)/ commands|@ files|! shell|\(auto\)\s*$"""),
    )
    // A line made of three or more "·"-separated short parts is a status bar, not output.
    if (t.count { it == '·' } >= 2 && t.split('·').all { it.trim().length <= 28 }) return ""
    // Box-drawn frames ("│ text │") keep only the text.
    val inner = t.trim('│', '┃', '|', ' ').trim()
    if (inner.isEmpty()) return ""
    return if (chrome.any { it.containsMatchIn(inner) } || inner.count { it.isLetterOrDigit() } < 3) "" else inner
}
