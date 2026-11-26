package com.billmongan.herdrdroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.questions.EntryState
import com.billmongan.herdrdroid.questions.QuestionEntry
import kotlinx.coroutines.delay

/*
 * 2.2.8 (Bill, 2026-11-24): the Questions view, opened from the drawer's Questions item. Every open
 * question or approval on the servers the app can see, oldest waiting first, grouped by server, each
 * with the answers its pane's own card shows ([PromptCard]), sent through the same checks.
 */

/** The drawer's Questions item, pinned under the agent list, with its count. */
@Composable
internal fun QuestionsDrawerItem(count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Palette.BlueSoft else Palette.Ground)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp)
            .testTag("drawer-questions")
            .semantics { contentDescription = if (count == 0) "Questions, none open" else "Questions, $count open" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Questions", color = Palette.Ink, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f))
        if (count > 0) Text(
            count.toString(), color = Palette.OnBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("drawer-questions-badge").background(Palette.OrangeInk, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** "first seen 4m ago", "first seen just now": since the app first saw the question waiting. */
internal fun waitedText(since: Long, now: Long): String = "first seen " + com.billmongan.herdrdroid.herdr.ago(since, now)

/** Why an entry with a question can't be answered from here, or null when it can. */
internal fun cannotAnswerWhy(e: QuestionEntry): String? = when {
    e.state != EntryState.READY -> null
    !e.source.current -> "On ${e.source.serverLabel}, which isn't the server open now. Open the pane to answer it there."
    e.target == null -> "The app couldn't bind this question to its pane. Open the pane to answer it."
    else -> null
}

@Composable
fun QuestionsScreen(vm: AppViewModel) {
    val feed = vm.questionsFeed
    val front = vm.inFront
    DisposableEffect(front) {
        if (front) vm.questionsShown() else vm.questionsHidden()
        onDispose { vm.questionsHidden() }
    }
    // Live: any change in the lists the app keeps (the status push, the watcher) is a round now.
    val sessions = vm.sessions()
    // The occupant's whole identity too, so another agent or session in a pane shows at once.
    val signature = sessions.joinToString("\n") { s -> with(s.pane) {
        "${s.server.id}|$id|${s.status}|$agent|$terminalId|$sessionId|$tabId|$workspaceId|$cwd|$isProbe"
    } }
    LaunchedEffect(signature) { feed.poke() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    BackHandler(enabled = !vm.drawerOpen) { vm.goHome() }
    var typing by remember { mutableStateOf<TypedAnswer?>(null) }
    // Hidden (the app went behind): the list, its targets and holds are forgotten, so the typed-answer box goes too.
    LaunchedEffect(front) { if (!front) typing = null }
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.drawerOpen = true }) { Icon(Icons.Filled.Menu, contentDescription = "All agents", tint = Palette.Ink) }
            Text("Questions", color = Palette.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp).weight(1f))
            IconButton(onClick = { feed.poke() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh questions", tint = Palette.Ink) }
        }
        vm.notice?.let { ErrorBanner(it) { vm.notice = null } }
        QuestionsList(
            entries = vm.visibleQuestions(), now = now,
            sending = { it.source.current && vm.answerInFlight(it.source.pane.id) },
            onOpen = { e -> vm.drawerOpen = false; vm.switchTo(e.source.serverId, e.source.pane.id) },
            onPress = { e, down -> if (down) feed.holdPress(e.key) else feed.releasePress(e.key) },
            onChoice = { e, p, c ->
                val t = e.target as? AppViewModel.CardTarget
                vm.answerChoice(e.source.pane.id, p, c, target = t, feedback = { feed.note(e.key, vm.questionsRefusal(t, it)) })
                feed.poke()
            },
            onKey = { e, p, k ->
                val label = "Pressed " + when (k) { "esc" -> "Esc"; "tab" -> "Tab"; "shift+tab" -> "Shift+Tab"; "ctrl+c" -> "Ctrl+C"; else -> k }
                val t = e.target as? AppViewModel.CardTarget
                vm.answerCardKey(t, p, k, label, feedback = { feed.note(e.key, vm.questionsRefusal(t, it)) })
                feed.poke()
            },
            onType = { e, p, c -> typing = TypedAnswer(e, p, c) },
        )
    }
    vm.approveAllPending?.takeIf {
        front && it.serverId == vm.current?.id && it.target?.questionsVisit?.let { visit -> visit >= 0 } == true
    }?.let { ApproveAllDialog(vm, it, it.target?.pane?.agentName) }
    val typingProbe = typing?.entry?.source?.let { vm.probePane(it.serverId, it.pane.id) } == true
    LaunchedEffect(typingProbe) { if (typingProbe) typing = null }
    typing?.takeUnless { typingProbe }?.let { t ->
        // Held while the box is on screen, and released however it leaves (sent, cancelled, or the view closed).
        DisposableEffect(t) {
            feed.holdTyping(t.entry.key)
            onDispose { feed.releaseTyping(t.entry.key) }
        }
        var problem by remember(t) { mutableStateOf<String?>(null) }
        TypedAnswerDialog(
            t, sending = vm.answerInFlight(t.entry.source.pane.id), problem = problem,
            onDismiss = { typing = null },
            onSend = { text ->
                problem = null
                val target = t.entry.target as? AppViewModel.CardTarget
                vm.answerTyped(target, t.prompt, t.choice, text, feedback = {
                    // Why it wasn't sent (or that it may have been) shows in the box and on the entry.
                    val why = vm.questionsRefusal(target, it)
                    problem = why
                    feed.note(t.entry.key, why)
                }) {
                    if (typing === t) typing = null
                }
            },
        )
    }
}

internal data class TypedAnswer(val entry: QuestionEntry, val prompt: Prompt, val choice: Choice?)

@Composable
private fun TypedAnswerDialog(t: TypedAnswer, sending: Boolean, problem: String?, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember(t) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Answer ${sessionName(t.entry.source.pane)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                t.choice?.let { Text("Through “${it.label}”", color = Palette.Muted, fontSize = 13.sp) }
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().testTag("questions-typed"), minLines = 2)
                problem?.let { Text(it, color = Palette.OrangeInk, fontSize = 13.sp, modifier = Modifier.testTag("questions-typed-problem")) }
            }
        },
        confirmButton = { TextButton(enabled = !sending && text.isNotBlank(), onClick = { onSend(text.trim()) }) { Text(if (sending) "Sending…" else "Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The list itself, grouped by server; stateless, for the tests. */
@Composable
internal fun QuestionsList(
    entries: List<QuestionEntry>,
    now: Long,
    sending: (QuestionEntry) -> Boolean,
    onOpen: (QuestionEntry) -> Unit,
    onPress: (QuestionEntry, Boolean) -> Unit,
    onChoice: (QuestionEntry, Prompt, Choice) -> Unit,
    onKey: (QuestionEntry, Prompt, String) -> Unit,
    onType: (QuestionEntry, Prompt, Choice?) -> Unit,
) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No open questions", color = Palette.Muted, fontSize = 16.sp, modifier = Modifier.testTag("questions-empty"))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("questions-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val groups = entries.groupBy { it.source.serverId }
        for ((serverId, rows) in groups) {
            item(key = "h:$serverId") {
                Text(rows.first().source.serverLabel.uppercase(), color = Palette.Muted, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            }
            items(rows, key = { it.key }) { e ->
                QuestionEntryCard(e, now, sending(e), onOpen = { onOpen(e) }, onPress = { onPress(e, it) },
                    onChoice = { p, c -> onChoice(e, p, c) }, onKey = { p, k -> onKey(e, p, k) }, onType = { p, c -> onType(e, p, c) })
            }
        }
    }
}

@Composable
private fun QuestionEntryCard(
    e: QuestionEntry, now: Long, sending: Boolean,
    onOpen: () -> Unit, onPress: (Boolean) -> Unit,
    onChoice: (Prompt, Choice) -> Unit, onKey: (Prompt, String) -> Unit, onType: (Prompt, Choice?) -> Unit,
) {
    val pane = e.source.pane
    var expanded by remember(e.key, e.since) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp), color = Palette.Card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("question-entry")
            // Held still while a finger is on it: the list doesn't re-read, reorder or remove it.
            .pointerInput(e.key) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(PointerEventPass.Initial)
                        if (down.changes.none { it.pressed }) continue
                        onPress(true)
                        // Released when the press ends, and also when the gesture is cancelled or the entry leaves.
                        try {
                            do { val ev = awaitPointerEvent(PointerEventPass.Initial) } while (ev.changes.any { it.pressed })
                        } finally {
                            onPress(false)
                        }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentTile(pane.initial, if (e.state == EntryState.ANSWERED) Status.IDLE else Status.NEEDS_YOU, size = 30)
                Column(Modifier.weight(1f).clickable(onClick = onOpen).semantics { contentDescription = "Open ${sessionName(pane)} on ${e.source.serverLabel}" }) {
                    Text(sessionName(pane), color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${e.source.serverLabel} · ${waitedText(e.since, now)}", color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
                }
                if (e.state == EntryState.ANSWERED) Text("Answered", color = Palette.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("question-answered"))
            }
            e.note?.let { Text(it, color = Palette.OrangeInk, fontSize = 13.sp, modifier = Modifier.testTag("question-note")) }
            val p = e.prompt
            when {
                e.state == EntryState.ANSWERED -> Unit
                e.state == EntryState.READING -> Text("Reading the question…", color = Palette.Muted, fontSize = 13.sp)
                e.state == EntryState.UNREAD || p == null -> {
                    Text("The app couldn't read a question on this pane's screen.", color = Palette.Muted, fontSize = 13.sp)
                    OutlinedButton(onClick = onOpen) { Text("Open pane") }
                }
                else -> {
                    if (expanded) PromptQuestion(p, pane.agentName)
                    else Text(QuestionText.prose(p.question).ifBlank { "${pane.agentName} is waiting for you." }, color = Palette.Ink, fontSize = 14.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("question-text"))
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all") }
                    val why = cannotAnswerWhy(e)
                    if (why != null) {
                        Text(why, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.testTag("question-why"))
                        OutlinedButton(onClick = onOpen) { Text("Open pane") }
                    } else PromptCard(
                        p, agent = pane.agentName, sending = sending,
                        onChoice = { c -> onChoice(p, c) },
                        onKey = { k -> onKey(p, k) },
                        onTypeAnswer = { onType(p, null) },
                        onTypedChoice = { c -> onType(p, c) },
                        // The terminal is the pane's own: open it there.
                        onShowTerminal = onOpen,
                    )
                }
            }
        }
    }
}
