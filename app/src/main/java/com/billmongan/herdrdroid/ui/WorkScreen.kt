package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.ProcInfo
import com.billmongan.herdrdroid.herdr.ageText
import com.billmongan.herdrdroid.herdr.protectedPids
import com.billmongan.herdrdroid.transcript.Item
import com.billmongan.herdrdroid.transcript.Transcript
import com.billmongan.herdrdroid.transcript.rowsOf
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * What runs under an agent: its subagents and background tasks (Claude, from its own files) and
 * the process tree of its pane (any agent). Each one opens: a subagent's conversation, a task's
 * output, a process's details with Stop. Read every 3 seconds while open.
 */
@Composable
fun WorkScreen(vm: AppViewModel, s: Screen.Work) {
    when (s.kind) {
        "sub" -> SubagentView(vm, s)
        "task" -> TaskOutputView(vm, s)
        else -> WorkList(vm, s.paneId)
    }
}

@Composable
private fun WorkHeader(title: String, subtitle: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Ink) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
    HorizontalDivider(color = Palette.Line)
}

@Composable
private fun ProcRow(p: ProcInfo, keep: Set<Int>, agent: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width((p.depth.coerceAtMost(8) * 10).dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                if (p.pid in keep) Text("  ${if (p.depth == 0) "pane shell" else if (p.name.contains(agent ?: "\u0000", true)) "the agent" else "launcher"}",
                    color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
            }
            Text(p.cmd, color = Palette.Muted, fontFamily = Fonts.Mono, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("${ageText(p.ageSec)}\n${p.cpu}% · ${p.rssKb / 1024} MB", color = Palette.Muted, fontSize = 11.sp, lineHeight = 14.sp,
            modifier = Modifier.padding(start = 8.dp), softWrap = false)
    }
}

private fun ago(epochSec: Long): String = ageText((System.currentTimeMillis() / 1000 - epochSec).coerceAtLeast(0)) + " ago"

@Composable
private fun WorkList(vm: AppViewModel, paneId: String) {
    val pane = vm.pane(paneId)
    LaunchedEffect(paneId) {
        while (true) {
            vm.refreshWork(paneId)
            delay(3_000)
        }
    }
    var asking by remember { mutableStateOf<ProcInfo?>(null) }
    val w = vm.work[paneId]
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        WorkHeader("Running under ${pane?.agentName ?: "this pane"}", pane?.folder ?: paneId, onBack = { vm.screen = Screen.Agent(paneId) })
        if (w == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Blue) }
            return@Column
        }
        val keep = remember(w) { protectedPids(w, pane?.agent) }
        val activeProcs = remember(w) { w.activeProcs(pane?.agent) }
        val activePids = activeProcs.map { it.pid }.toSet()
        val ended = vm.endedProcs[paneId].orEmpty()
        val doneSubs = w.subagents.filter { !it.active }
        val doneTasks = w.tasks.filter { !it.running }
        val other = w.procs.filter { it.pid !in activePids }
        var showOther by remember { mutableStateOf(false) }
        PullRefresh({ vm.refreshWork(paneId) }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Spacer(Modifier.height(6.dp)) }
            // Three groups, matching the header's "N sh / K ag": shells, subagents, then finished.
            if (w.activeCount(pane?.agent) == 0) item { Text("Nothing is running underneath right now.", color = Palette.Muted, fontSize = 13.sp) }
            // ---- Shells ----------------------------------------------------------------------
            val runningTasks = w.tasks.filter { it.running }
            if (runningTasks.isNotEmpty() || activeProcs.isNotEmpty()) {
                item { SectionTitle("Shells", "${activeProcs.size} running") }
                items(runningTasks, key = { "t:" + it.id }) { t ->
                    WorkRow(Palette.Blue, t.title, "Background task · running · output ${t.kb} KB") { vm.screen = Screen.Work(paneId, "task", t.file, t.title) }
                }
                items(activeProcs, key = { "ap:" + it.pid }) { p -> ProcRow(p, keep, pane?.agent) { asking = p } }
            }
            // ---- Subagents -------------------------------------------------------------------
            val runningSubs = w.subagents.filter { it.active }
            if (runningSubs.isNotEmpty()) {
                item { SectionTitle("Subagents", "${runningSubs.size} running") }
                items(runningSubs, key = { "s:" + it.id }) { a ->
                    WorkRow(Palette.Blue, a.title, "Subagent · running · updated ${ago(a.updated)} · ${a.kb} KB") { vm.screen = Screen.Work(paneId, "sub", a.file, a.title) }
                }
            }
            // ---- Finished --------------------------------------------------------------------
            if (doneSubs.isNotEmpty() || doneTasks.isNotEmpty() || ended.isNotEmpty()) {
                item { SectionTitle("Finished", "${doneSubs.size + doneTasks.size + ended.size} in this session") }
                items(ended, key = { "e:" + it.proc.pid + ":" + it.endedAt }) { e ->
                    WorkRow(Palette.Muted, e.proc.name + "  " + e.proc.cmd, "Process · ended ${ago(e.endedAt / 1000)} · ran at least ${ageText(e.proc.ageSec)}") {}
                }
                items(doneTasks, key = { "t:" + it.id }) { t ->
                    WorkRow(Palette.Muted, t.title, "Background task · finished ${ago(t.updated)} · output ${t.kb} KB") { vm.screen = Screen.Work(paneId, "task", t.file, t.title) }
                }
                items(doneSubs, key = { "s:" + it.id }) { a ->
                    WorkRow(Palette.Muted, a.title, "Subagent · done · ${ago(a.updated)} · ${a.kb} KB") { vm.screen = Screen.Work(paneId, "sub", a.file, a.title) }
                }
            }
            // ---- Everything else in the pane (its shell, the agent, helpers it keeps) ----------
            item {
                Row(Modifier.fillMaxWidth().clickable { showOther = !showOther }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Other processes", color = Palette.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${other.size} · the pane's shell, the agent, and helpers it runs all the time", color = Palette.Muted, fontSize = 12.sp)
                    }
                    Text(if (showOther) "▾" else "▸", color = Palette.Muted, fontSize = 18.sp)
                }
            }
            if (showOther) items(other, key = { "p:" + it.pid }) { p -> ProcRow(p, keep, pane?.agent) { asking = p } }
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
    asking?.let { p -> ProcessDialog(vm, paneId, p, canStop = w != null && p.pid !in protectedPids(w, pane?.agent)) { asking = null } }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(title, color = Palette.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(detail, color = Palette.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun WorkRow(dot: androidx.compose.ui.graphics.Color, title: String, detail: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, color = Palette.Muted, fontSize = 12.sp)
            }
            Text("›", color = Palette.Muted, fontSize = 20.sp)
        }
    }
}

/** A process's details, with Stop (TERM, then Force stop with KILL) when it isn't the agent itself. */
@Composable
private fun ProcessDialog(vm: AppViewModel, paneId: String, p: ProcInfo, canStop: Boolean, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var stopped by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(p.cmd, fontFamily = Fonts.Mono, fontSize = 12.sp, color = Palette.Ink,
                        modifier = Modifier.fillMaxWidth().background(Palette.Gray, RoundedCornerShape(8.dp)).padding(8.dp))
                }
                Text("pid ${p.pid} · running ${ageText(p.ageSec)} · ${p.cpu}% CPU on average · ${p.rssKb / 1024} MB", color = Palette.Muted, fontSize = 13.sp)
                if (!canStop) Text("This is the pane's shell or the agent itself, so it has no Stop here. Use the agent's Stop to interrupt it.",
                    color = Palette.Muted, fontSize = 13.sp)
                if (confirm && result == null) Text(
                    "Stop ${p.name} and everything it started? They get a stop signal (TERM) and can clean up; anything unsaved in them may be lost.",
                    color = Palette.OrangeInk, fontSize = 14.sp,
                )
                result?.let { Text(it, color = Palette.Blue, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            when {
                !canStop -> Unit
                !confirm -> TextButton(onClick = { confirm = true }) { Text("Stop…", color = Palette.OrangeInk) }
                result == null -> Button(
                    onClick = {
                        result = "Stopping…"
                        vm.stopProcess(paneId, p.pid, force = false) { r ->
                            stopped = r.isSuccess
                            result = r.fold({ "Stop sent to $it process(es). If it is still listed in a few seconds, use Force stop." }, { it.message ?: "Couldn't stop it." })
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Orange, contentColor = Palette.OnBlue),
                ) { Text("Stop it") }
                stopped -> TextButton(onClick = {
                    vm.stopProcess(paneId, p.pid, force = true) { r ->
                        result = r.fold({ "Force stop (KILL) sent to $it process(es)." }, { it.message ?: "Couldn't stop it." })
                    }
                }) { Text("Force stop", color = Palette.OrangeInk) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (result != null) "Close" else "Cancel") } },
    )
}

/** A Claude subagent's conversation, read from its own log, live while it runs. */
@Composable
private fun SubagentView(vm: AppViewModel, s: Screen.Work) {
    val pane = vm.pane(s.paneId)
    val context = LocalContext.current
    var items by remember(s.file) { mutableStateOf<List<Item>>(emptyList()) }
    var more by remember(s.file) { mutableStateOf(false) }
    var problem by remember(s.file) { mutableStateOf<String?>(null) }
    var loaded by remember(s.file) { mutableStateOf(false) }
    LaunchedEffect(s.file) {
        var cursor = JSONObject().put("pin", s.file).toString()
        while (true) {
            vm.readSubagent(s.paneId, cursor).onSuccess { r ->
                cursor = r.cursor
                if (r.reset || r.items.isNotEmpty()) items = Transcript.merge(items, r.items, r.reset)
                if (r.reset) more = r.more
                problem = null
            }.onFailure { problem = it.message }
            loaded = true
            delay(2_500)
        }
    }
    val title = s.title ?: "Subagent"
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        WorkHeader(title, "Subagent of ${pane?.agentName ?: "the agent"} · read-only", onBack = { vm.screen = Screen.Work(s.paneId) }) {
            if (items.isNotEmpty()) TextButton(onClick = { vm.exportMarkdown(context, title, "Subagent", items) }) { Text("Export", softWrap = false) }
        }
        when {
            problem != null && items.isEmpty() -> Text(problem!!, color = Palette.OrangeInk, modifier = Modifier.padding(16.dp))
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Blue) }
            items.isEmpty() -> Text("Nothing in this subagent's log yet.", color = Palette.Muted, modifier = Modifier.padding(16.dp))
            else -> ProvideAttachments(vm::attachmentBytes) {
                TranscriptChat(
                    rows = remember(items) { rowsOf(items) },
                    working = false, workingLabel = "", agentName = "Subagent", pending = emptyList(), more = more,
                    size = vm.current?.textSize ?: 12.5f, family = vm.readingFont.family,
                    actions = ChatActions(onQuote = {}, onStop = null, onForward = null),
                    header = {}, footer = {}, state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** A background task's output, following its end while it grows (like tail -f). */
@Composable
private fun TaskOutputView(vm: AppViewModel, s: Screen.Work) {
    val file = s.file ?: return
    var text by remember(file) { mutableStateOf("") }
    var cut by remember(file) { mutableStateOf(false) }
    var problem by remember(file) { mutableStateOf<String?>(null) }
    var follow by remember(file) { mutableStateOf(true) }
    val scroll = rememberScrollState()
    LaunchedEffect(file) {
        var offset = -1L
        while (true) {
            vm.tailTask(file, offset).onSuccess { r ->
                if (offset < 0) cut = r.cut
                if (r.text.isNotEmpty()) {
                    // Keep the last 200 KB on the phone.
                    text = (text + r.text).let { if (it.length > 200_000) { cut = true; it.takeLast(200_000) } else it }
                }
                offset = r.next
                problem = null
            }.onFailure { problem = it.message }
            delay(2_000)
        }
    }
    LaunchedEffect(text, follow) { if (follow) scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        WorkHeader(s.title ?: "Background task", "Output · read-only", onBack = { vm.screen = Screen.Work(s.paneId) }) {
            TextButton(onClick = { follow = !follow }) { Text(if (follow) "Following" else "Follow", softWrap = false) }
        }
        problem?.let { Text(it, color = Palette.OrangeInk, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        if (cut) Text("Showing the end of the output.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                text.ifEmpty { "No output yet." },
                color = Palette.TerminalInk, fontFamily = Fonts.Mono, fontSize = 11.5.sp, lineHeight = 15.sp, softWrap = false,
                modifier = Modifier.fillMaxSize().background(Palette.Terminal).verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}
