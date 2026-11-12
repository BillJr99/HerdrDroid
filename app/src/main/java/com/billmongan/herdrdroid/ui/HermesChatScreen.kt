package com.billmongan.herdrdroid.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.billmongan.herdrdroid.hermes.Ask
import com.billmongan.herdrdroid.hermes.HermesChat
import com.billmongan.herdrdroid.hermes.HermesPhase
import com.billmongan.herdrdroid.hermes.HermesSession
import com.billmongan.herdrdroid.transcript.rowsOf

/** A native chat with Hermes through its gateway, not through the terminal. */
@Composable
fun HermesChatScreen(vm: AppViewModel) {
    val chat = vm.hermesChat() ?: return
    DisposableEffect(Unit) { onDispose { } }
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (chat.phase == HermesPhase.CHAT) chat.showPicker() else vm.screen = Screen.Tabs }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.Ink)
            }
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    if (chat.phase == HermesPhase.CHAT) chat.title else "Hermes",
                    fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (dot, label) = when (chat.phase) {
                        HermesPhase.CONNECTING -> Palette.Muted to "Connecting to Hermes…"
                        HermesPhase.OFFLINE -> Palette.OrangeInk to "Not connected"
                        else -> (if (chat.running) Palette.Blue else Palette.Green) to (if (chat.running) "Working" else "Connected through the gateway")
                    }
                    Box(Modifier.size(7.dp).background(dot, CircleShape))
                    Text("  $label", color = Palette.Muted, fontSize = 12.5.sp, maxLines = 1)
                }
            }
            if (chat.phase == HermesPhase.CHAT) {
                IconButton(onClick = { chat.showPicker() }) { Icon(Icons.Filled.Menu, contentDescription = "Conversations", tint = Palette.Ink) }
            }
            IconButton(onClick = { chat.newChat() }) { Icon(Icons.Filled.Add, contentDescription = "New chat", tint = Palette.Ink) }
        }
        HorizontalDivider(color = Palette.Line)
        chat.problem?.let { p ->
            Surface(color = Palette.OrangeSoft, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p, color = Palette.OrangeInk, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (chat.phase == HermesPhase.OFFLINE) TextButton(onClick = { chat.reconnect() }) { Text("Reconnect") }
                }
            }
        }
        when (chat.phase) {
            HermesPhase.CONNECTING -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Palette.Blue)
            }
            HermesPhase.OFFLINE -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(onClick = { chat.reconnect() }) { Text("Reconnect") }
            }
            HermesPhase.PICKER -> SessionPicker(chat, Modifier.weight(1f))
            HermesPhase.CHAT -> HermesConversation(vm, chat, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SessionPicker(chat: HermesChat, modifier: Modifier) {
    LazyColumn(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { chat.newChat() },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnBlue),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("New chat with Hermes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Text(
                "Recent conversations",
                color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
            )
        }
        if (chat.loadingSessions && chat.sessions.isEmpty()) {
            item { CircularProgressIndicator(color = Palette.Blue, modifier = Modifier.padding(24.dp)) }
        }
        items(chat.sessions, key = { it.id }) { s -> SessionCard(s) { chat.open(s) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun ago(t: Double): String {
    if (t <= 0) return ""
    val s = (System.currentTimeMillis() / 1000.0 - t).toLong()
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}

@Composable
private fun SessionCard(s: HermesSession, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Palette.Card,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.running) Box(Modifier.size(8.dp).background(Palette.Blue, CircleShape))
                Text(
                    (if (s.running) "  " else "") + s.title,
                    color = Palette.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(ago(s.lastActive), color = Palette.Muted, fontSize = 12.sp)
            }
            if (s.preview.isNotBlank()) {
                Text(s.preview.replace('\n', ' '), color = Palette.Preview, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(listOfNotNull(s.source.ifBlank { null }, s.model).joinToString(" · "), color = Palette.Muted, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun HermesConversation(vm: AppViewModel, chat: HermesChat, modifier: Modifier) {
    var message by rememberSaveable { mutableStateOf("") }
    val state = rememberLazyListState()
    val rows = remember(chat.items) { rowsOf(chat.items) }
    Column(modifier) {
        TranscriptChat(
            rows = rows,
            working = chat.running,
            workingLabel = chat.status,
            agentName = "Hermes",
            pending = null,
            more = false,
            size = vm.current?.textSize ?: 13.5f,
            family = vm.readingFont.family,
            actions = ChatActions(onQuote = { q -> message = q.lines().joinToString("\n") { "> $it" } + "\n\n" }, onStop = { chat.interrupt() }),
            state = state,
            footer = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    chat.asks.forEach { a ->
                        when (a) {
                            is Ask.Approval -> ApprovalCard(a, onChoice = { chat.approve(a, it) })
                            is Ask.Clarify -> ClarifyCard(a, onSubmit = { chat.answer(a, it) }, onCancel = { chat.cancelAsk(a) })
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)) {
            ChatComposer(
                value = message,
                onValueChange = { message = it },
                placeholder = if (chat.running) "Steer Hermes while it works…" else "Message Hermes",
                canSend = message.isNotBlank(),
                onSend = { chat.send(message.trim()); message = "" },
                working = chat.running,
                onStop = { chat.interrupt() },
                steerLabel = "Steer",
                onAttach = { uri -> vm.attach(uri) { path -> message = (message.trimEnd() + " " + path).trim() + " " } },
                attachBusy = vm.uploading,
                commands = chat.commands,
            )
        }
    }
}

private fun approvalLabel(c: String) = when (c) {
    "once" -> "Allow once"
    "session" -> "Allow for this session"
    "always" -> "Always allow"
    "deny" -> "Deny"
    else -> c.replaceFirstChar { it.uppercase() }
}

@Composable
private fun ApprovalCard(a: Ask.Approval, onChoice: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Palette.Card, border = BorderStroke(2.dp, Palette.Orange)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.OrangeInk, modifier = Modifier.size(18.dp))
                Text("HERMES WANTS TO RUN", color = Palette.OrangeInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (a.description.isNotBlank()) Text(a.description, color = Palette.Ink, fontSize = 15.sp)
            if (a.command.isNotBlank()) CodeBlock(a.command, "sh", 13f)
            val choices = a.choices.filter { it != "deny" }
            choices.forEachIndexed { i, c ->
                if (i == 0) {
                    Button(
                        onClick = { onChoice(c) }, shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnBlue),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(approvalLabel(c)) }
                } else {
                    OutlinedButton(onClick = { onChoice(c) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(approvalLabel(c), color = Palette.Ink)
                    }
                }
            }
            TextButton(onClick = { onChoice("deny") }, modifier = Modifier.fillMaxWidth()) { Text("Deny", color = Palette.OrangeInk) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClarifyCard(c: Ask.Clarify, onSubmit: (Map<String, String?>) -> Unit, onCancel: () -> Unit) {
    val answers = remember { mutableStateMapOf<String, String>() }
    Surface(shape = RoundedCornerShape(18.dp), color = Palette.Card, border = BorderStroke(2.dp, Palette.Blue)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("HERMES IS ASKING", color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            c.questions.forEach { q ->
                Text(q.text, color = Palette.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (q.choices.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        q.choices.forEach { choice ->
                            ComposerChip(choice, answers[q.qid] == choice) { answers[q.qid] = choice }
                        }
                    }
                }
                OutlinedTextField(
                    value = answers[q.qid].orEmpty(),
                    onValueChange = { answers[q.qid] = it },
                    placeholder = { Text(if (q.choices.isEmpty()) "Your answer" else "Or type an answer") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSubmit(c.questions.associate { it.qid to answers[it.qid] }) }) { Text("Answer") }
                    TextButton(onClick = onCancel) { Text("Skip") }
                }
            }
        }
    }
}
