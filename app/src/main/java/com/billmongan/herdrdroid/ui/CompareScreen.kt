package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.UserItem
import kotlinx.coroutines.delay

/**
 * The same task's answers from several agents, one card each: the agent's status and its latest
 * reply since your message, read from each agent's own log every few seconds.
 */
@Composable
fun CompareScreen(vm: AppViewModel) {
    LaunchedEffect(vm.compareTargets) {
        while (true) {
            vm.refreshCompare()
            vm.refreshPanes()
            delay(3_000)
        }
    }
    var forwarding by remember { mutableStateOf<Pair<String, String>?>(null) }
    var judging by remember { mutableStateOf(false) }
    // Each agent's reply after your last message, for the cards and for a judge.
    fun replyOf(id: String): String {
        val items = vm.compareItems[id].orEmpty()
        val lastYou = items.indexOfLast { it is UserItem }
        return items.drop(lastYou + 1).filterIsInstance<TextItem>().joinToString("\n\n") { it.text }
    }
    val answered = vm.compareTargets.filter { replyOf(it).isNotBlank() }
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.Tabs }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Ink) }
            Text("Compare answers", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f))
            // A judge gets every answer, labeled, and is asked to pick the best and say why.
            TextButton(enabled = answered.size >= 2, onClick = { judging = true }) { Text("Ask a judge…") }
        }
        HorizontalDivider(color = Palette.Line)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(" ", fontSize = 4.sp) }
            items(vm.compareTargets, key = { it }) { id ->
                val p = vm.pane(id)
                val items = vm.compareItems[id].orEmpty()
                val reply = replyOf(id)
                Surface(shape = RoundedCornerShape(16.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AgentTile(p?.initial ?: "?", p?.status ?: com.billmongan.herdrdroid.herdr.Status.UNKNOWN, size = 32)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(p?.agentName ?: id, color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(p?.let { look(it.status).label } ?: "gone", color = Palette.Muted, fontSize = 12.5.sp)
                            }
                            TextButton(onClick = { vm.openPane(id) }) { Text("Open") }
                        }
                        if (reply.isBlank()) {
                            Text(if (p?.status == com.billmongan.herdrdroid.herdr.Status.WORKING) "Working on it…" else "No reply yet.", color = Palette.Muted, fontSize = 14.sp)
                        } else {
                            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                MarkdownText(reply, 14f, vm.readingFont.family)
                            }
                            TextButton(onClick = { forwarding = id to reply }) { Text("Use this answer…") }
                        }
                    }
                }
            }
            item { Text(" ", fontSize = 12.sp) }
        }
    }
    if (judging) {
        ForwardPicker(vm, exclude = null, onPick = { judge ->
            judging = false
            val task = vm.compareTargets.firstNotNullOfOrNull { id -> vm.compareItems[id].orEmpty().lastOrNull { it is UserItem } as? UserItem }?.text
            vm.sendMessage(judge, judgePrompt(task, answered.map { id -> (vm.pane(id)?.agentName ?: id) to replyOf(id) }))
            vm.openPane(judge.id)
        }, onDismiss = { judging = false })
    }
    forwarding?.let { (from, text) ->
        ForwardPicker(vm, exclude = from, onPick = { to -> forwarding = null; vm.forward(text, vm.pane(from), to) }, onDismiss = { forwarding = null })
    }
}

/** The message a judge agent gets: the task, then each answer under its agent's name. */
fun judgePrompt(task: String?, answers: List<Pair<String, String>>): String = buildString {
    append("Several agents answered the same task. Compare their answers, say which is best and why, ")
    append("and note anything the best one missed that another got right.\n\n")
    if (!task.isNullOrBlank()) append("Task:\n").append(task.trim()).append("\n\n")
    answers.forEachIndexed { i, (who, text) ->
        append("--- Answer ").append(i + 1).append(" (").append(who).append(") ---\n").append(text.trim()).append("\n\n")
    }
}.trimEnd()
