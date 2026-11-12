package com.billmongan.herdrdroid.ui

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

    val panes = vm.panes
    var forwardingResult by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Surface(color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(vm.current?.label ?: "", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    LinkLabel(vm, " · ${panes.size} tabs · v${com.billmongan.herdrdroid.BuildConfig.VERSION_NAME}")
                }
                TextButton(onClick = { vm.screen = Screen.Dispatch }) { Text("Hand out", fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = { vm.openFiles("~") }) { Text("Files") }
                IconButton(onClick = { scope.launch { vm.refreshPanes(); vm.refreshPreviews() }; vm.refreshQuotasSoon() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Palette.Ink)
                }
                IconButton(onClick = { vm.screen = Screen.Settings(Screen.Tabs) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.Ink)
                }
                IconButton(onClick = { vm.disconnect() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Disconnect", tint = Palette.Ink)
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
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
            if (vm.fleetStates.isNotEmpty() && filter == Filter.ALL) {
                item(key = "_fleet") {
                    Text(
                        vm.fleetStates.values.joinToString("   ") { st ->
                            val w = st.panes.count { it.status == Status.WORKING }
                            val n = st.panes.count { it.status == Status.NEEDS_YOU }
                            st.server.label + ": " + (st.error?.let { "unreachable" } ?: "$n need you, $w working")
                        },
                        color = Palette.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
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
                        checking = vm.showQuotas && vm.probingPane == p.id,
                        underneath = vm.workCounts[p.id] ?: 0,
                        onClose = if (p.tabLabel.startsWith("helper-")) ({ vm.closeHelper(p) }) else null,
                    ) { vm.openPane(p.id) }
                }
            }
            // Every agent on the other watched servers, grouped by server; a tap switches servers.
            vm.fleetStates.values.sortedBy { it.server.label.lowercase() }.forEach { st ->
                val agents = st.panes.filter { it.agent != null && it.status != Status.NEEDS_YOU && filter.matches(it) }
                if (agents.isEmpty()) return@forEach
                item(key = "_srv:" + st.server.id) {
                    Text("On ${st.server.label}", color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                }
                items(agents, key = { "o:" + st.server.id + it.id }) { p ->
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line),
                        modifier = Modifier.fillMaxWidth().clickable { vm.switchTo(st.server.id, p.id) },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AgentTile(p.initial, p.status, size = 32)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text("${p.agentName} · ${p.folder}", color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p.title.trim().ifBlank { look(p.status).label }, color = Palette.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StatusPill(p.status)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    forwardingResult?.let { t ->
        ForwardPicker(vm, exclude = null, onPick = { to -> forwardingResult = null; vm.forward(t, null, to) }, onDismiss = { forwardingResult = null })
    }
}

@Composable
private fun PaneCard(
    p: Pane, preview: String, model: String?, quota: QuotaReading?, checking: Boolean,
    underneath: Int = 0,
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
        Row(Modifier.padding(14.dp)) {
            AgentTile(p.initial, p.status)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (p.folder.isNotBlank()) "${p.agentName} · ${p.folder}" else p.agentName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Palette.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Subagents and background tasks running under this agent.
                    if (underneath > 0) Text("+$underneath", color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    StatusPill(p.status)
                }
                // The agent's terminal title, which most agents set to what they are working on.
                val title = p.title.trim()
                if (title.isNotBlank() && !title.equals(p.agentName, ignoreCase = true) && !title.equals(p.agent, ignoreCase = true) && title != p.cwd) {
                    Text(title, color = Palette.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                model?.let {
                    Text(
                        it,
                        color = Palette.Blue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    preview.ifBlank { p.id },
                    color = Palette.Preview,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                quota?.let { QuotaLine(it, Modifier.padding(top = 4.dp)) }
                if (checking) Text("Checking usage…", color = Palette.Muted, fontSize = 12.sp)
                if (onClose != null) {
                    Text(
                        if (p.status == Status.WORKING) "Helper · working" else "Helper · Close tab",
                        color = Palette.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(enabled = p.status != Status.WORKING) { onClose() }.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
