package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.store.Server

/**
 * The drawer: every agent on every server in one list, grouped by status and tagged with its
 * server, then the servers as a quiet status list, then the app's other places. A row opens its
 * agent; a long press renames, pins, or closes it.
 */
@Composable
fun SessionDrawer(vm: AppViewModel, opening: Boolean = true) {
    val listState = rememberLazyListState()
    LaunchedEffect(opening) {
        if (opening) listState.scrollToItem(0)
    }
    var filter by remember { mutableStateOf("") }
    var onlyServer by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf<AppViewModel.Session?>(null) }
    var renaming by remember { mutableStateOf<AppViewModel.Session?>(null) }
    var relaying by remember { mutableStateOf<AppViewModel.Session?>(null) }
    @Suppress("UNUSED_VARIABLE") val pins = vm.pinVersion
    @Suppress("UNUSED_VARIABLE") val models = vm.modelsVersion
    val all = vm.sessions()
    val shown = all.filter { s ->
        (onlyServer == null || s.server.id == onlyServer) &&
            (filter.isBlank() || listOf(s.pane.agentName, s.pane.tabLabel, s.pane.folder, s.server.label).any { it.contains(filter, ignoreCase = true) })
    }
    val openId = (vm.screen as? Screen.Agent)?.paneId
    Column(Modifier.fillMaxHeight().width(320.dp).background(Palette.Ground).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("HerdrDroid", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f))
        }
        Button(
            onClick = { creating = true },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnBlue),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) { Text("＋ New session", fontWeight = FontWeight.SemiBold) }
        OutlinedTextField(
            filter, { filter = it }, singleLine = true,
            placeholder = { Text("Search sessions") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        vm.sessionBusy?.let { Text(it, color = Palette.Blue, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            val groups = listOf(
                "Needs you" to listOf(Status.NEEDS_YOU),
                "Working" to listOf(Status.WORKING),
                "Done and idle" to listOf(Status.DONE, Status.IDLE, Status.UNKNOWN),
            )
            for ((title, statuses) in groups) {
                val rows = shown.filter { it.status in statuses }
                if (rows.isEmpty()) continue
                item(key = "h:$title") { GroupLabel(title) }
                items(rows, key = { it.server.id + "|" + it.pane.id }) { s ->
                    SessionRow(
                        s, open = s.current && s.pane.id == openId, model = vm.modelFor(s.server.id, s.pane.id),
                        onOpen = { vm.drawerOpen = false; vm.switchTo(s.server.id, s.pane.id) },
                        onRelay = if (!s.current) ({ relaying = s }) else null,
                        onRename = if (vm.canRename(s.server.id)) ({ renaming = s }) else null, onPin = { vm.togglePin(s.server, s.pane) }, onClose = { closing = s },
                    )
                }
            }
            if (shown.isEmpty()) item { Text(if (all.isEmpty()) "No agents yet. Start one with New session." else "Nothing matches.", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            item(key = "servers") {
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 8.dp))
                GroupLabel("Servers")
            }
            items(vm.serverStates(), key = { "s:" + it.server.id }) { st ->
                val on = onlyServer == st.server.id
                Row(
                    Modifier.fillMaxWidth().background(if (on) Palette.BlueSoft else Palette.Ground)
                        .clickable { onlyServer = if (on) null else st.server.id }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(if (st.reachable == true) Palette.Green else Palette.Line, CircleShape))
                    Text("  " + st.server.label, color = Palette.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (st.reachable) {
                            false -> "unreachable" + (st.seenAt?.let { " " + com.billmongan.herdrdroid.herdr.ago(it).removeSuffix(" ago") } ?: "")
                            else -> "${st.sessions}"
                        },
                        color = Palette.Muted, fontSize = 12.5.sp,
                    )
                }
            }
            item(key = "links") {
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 8.dp))
                val back = vm.screen
                DrawerLink("Usage") { vm.drawerOpen = false; vm.screen = Screen.Usage }
                DrawerLink("Search chats") { vm.drawerOpen = false; vm.screen = Screen.Search }
                DrawerLink("Servers…") { vm.drawerOpen = false; vm.openServers() }
                DrawerLink("Settings") { vm.drawerOpen = false; vm.screen = Screen.Settings(back) }
                Spacer(Modifier.height(24.dp))
            }
        }
        // 2.2.8: every open question on the servers the app can see, pinned under the list.
        HorizontalDivider(color = Palette.Line)
        QuestionsDrawerItem(vm.openQuestionCount(), selected = vm.screen == Screen.Questions) {
            vm.drawerOpen = false
            vm.screen = Screen.Questions
        }
    }
    relaying?.let { session ->
        var target by remember(session.server.id, session.pane.id) { mutableStateOf<AppViewModel.RemotePromptTarget?>(null) }
        var checking by remember(session.server.id, session.pane.id) { mutableStateOf(true) }
        LaunchedEffect(session.server.id, session.pane.id) {
            target = vm.bindRemotePrompt(session)
            checking = false
        }
        val bound = target
        val available = bound != null && vm.remotePromptCurrent(bound)
        PromptRelayDialog(
            destination = "${sessionName(session.pane)} on ${session.server.label}",
            checking = checking, available = available,
            onDismiss = { relaying = null },
            onSend = { text ->
                if (bound == null) com.billmongan.herdrdroid.herdr.RelayResult.REFUSED
                else vm.sendRemotePrompt(bound, text) { vm.inFront }
            },
            onSent = {
                relaying = null
                android.widget.Toast.makeText(vm.getApplication(), "Message sent to ${sessionName(session.pane)} on ${session.server.label}.",
                    android.widget.Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (creating) NewSessionDialog(vm) { creating = false }
    closing?.let { s -> CloseSessionDialog(s, onDismiss = { closing = null }) { closing = null; vm.closeSession(s.server.id, s.pane) } }
    renaming?.let { s -> RenameDialog(s, onDismiss = { renaming = null }) { name -> renaming = null; vm.renameSession(s.server.id, s.pane, name) } }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text.uppercase(), color = Palette.Muted, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun DrawerLink(text: String, onClick: () -> Unit) {
    Text(text, color = Palette.Ink, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp))
}

/** The session's name: the tab's label when it says more than the agent's own name. */
internal fun sessionName(p: Pane): String {
    val label = p.tabLabel.trim()
    return if (label.isBlank() || label.equals(p.agent, ignoreCase = true) || label.equals(p.agentName, ignoreCase = true)) p.agentName else "${p.agentName} · $label"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: AppViewModel.Session, open: Boolean, model: String?, onOpen: () -> Unit, onRelay: (() -> Unit)?, onRename: (() -> Unit)?, onPin: () -> Unit, onClose: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().background(if (open) Palette.BlueSoft else Palette.Ground)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).background(look(s.status).ink, CircleShape))
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    (if (s.status == Status.NEEDS_YOU) "! " else "") + sessionName(s.pane) + (if (s.pinned) "  ★" else ""),
                    color = Palette.Ink, fontSize = 14.5.sp, fontWeight = if (open) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(s.server.label, model, s.pane.folder).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // The session on screen now (Bill asked what the old blue check meant: it read as "done").
            if (open) Text("open", color = Palette.Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onRelay != null) DropdownMenuItem(text = { Text("Send prompt…") }, onClick = { menu = false; onRelay() })
            // herdr-core can't rename, so the item is only there where it works.
            if (onRename != null) DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text(if (s.pinned) "Unpin" else "Pin to the top") }, onClick = { menu = false; onPin() })
            DropdownMenuItem(text = { Text("Close session…", color = Palette.OrangeInk) }, onClick = { menu = false; onClose() })
        }
    }
}

/** Closing ends the agent in that tab. Its folder is never touched. Busy sessions ask twice. */
@Composable
internal fun CloseSessionDialog(s: AppViewModel.Session, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val busy = s.status == Status.WORKING || s.status == Status.NEEDS_YOU
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 0) "Close \"${s.pane.tabLabel.ifBlank { s.pane.agentName }}\" on ${s.server.label}?" else "It is ${if (s.status == Status.WORKING) "still working" else "waiting on you"}. Close it anyway?") },
        text = {
            Text(
                if (step == 0) "This ends the ${s.pane.agentName} session in that tab. Files in ${s.pane.cwd.ifBlank { "its folder" }} are not touched."
                else "Whatever it is doing now stops, and its unsaved work in the conversation is gone. The folder is still not touched.",
            )
        },
        confirmButton = {
            Button(onClick = { if (step == 0 && busy) step = 1 else onConfirm() }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Orange)) {
                Text(if (step == 0) "Close session" else "Close it anyway")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

@Composable
private fun RenameDialog(s: AppViewModel.Session, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(s.pane.tabLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * New session: a name, a server, one of the agents installed there, a folder (recent ones offered),
 * and an optional first message.
 */
@Composable
internal fun NewSessionDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val reachable = vm.serverStates().filter { it.reachable == true }.map { it.server }
    var server by remember { mutableStateOf<Server?>(reachable.firstOrNull { it.id == vm.current?.id } ?: reachable.firstOrNull()) }
    var agents by remember { mutableStateOf<List<String>?>(null) }
    var kind by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("~") }
    var first by remember { mutableStateOf("") }
    var pickServer by remember { mutableStateOf(false) }
    var pickAgent by remember { mutableStateOf(false) }
    LaunchedEffect(server?.id) {
        agents = null; kind = null
        server?.let { s -> agents = vm.installedAgents(s.id).also { kind = it.firstOrNull() } }
    }
    val recent = vm.sessions().filter { it.server.id == server?.id }.map { it.pane.cwd }.filter { it.isNotBlank() }.distinct().take(5)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, placeholder = { Text("cs357-tutorial") })
                Box {
                    OutlinedTextField(server?.label ?: "No server reachable", {}, readOnly = true, label = { Text("Server") },
                        trailingIcon = { TextButton(onClick = { pickServer = true }) { Text("Change") } })
                    DropdownMenu(expanded = pickServer, onDismissRequest = { pickServer = false }) {
                        reachable.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { pickServer = false; server = s }) }
                    }
                }
                Box {
                    OutlinedTextField(
                        kind ?: if (agents == null) "Checking what's installed…" else "No agents found there", {}, readOnly = true, label = { Text("Agent") },
                        trailingIcon = { if (!agents.isNullOrEmpty()) TextButton(onClick = { pickAgent = true }) { Text("Change") } },
                    )
                    DropdownMenu(expanded = pickAgent, onDismissRequest = { pickAgent = false }) {
                        agents.orEmpty().forEach { a -> DropdownMenuItem(text = { Text(a) }, onClick = { pickAgent = false; kind = a }) }
                    }
                }
                OutlinedTextField(folder, { folder = it }, singleLine = true, label = { Text("Folder") })
                if (recent.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    recent.take(3).forEach { r -> ComposerChip(r.substringAfterLast('/').ifBlank { r }, folder == r) { folder = r } }
                }
                OutlinedTextField(first, { first = it }, label = { Text("First message (optional)") }, maxLines = 4)
                Text("A new tab on that server. Closing the session later never deletes the folder.", color = Palette.Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(enabled = server != null && kind != null && name.isNotBlank() && vm.sessionBusy == null, onClick = {
                vm.newSession(server!!.id, kind!!, folder.trim(), name.trim(), first.trim().ifBlank { null })
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
