package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.billmongan.herdrdroid.herdr.PastSession
import com.billmongan.herdrdroid.transcript.Item
import com.billmongan.herdrdroid.transcript.Transcript
import com.billmongan.herdrdroid.transcript.rowsOf
import java.text.DateFormat
import java.util.Date

/**
 * The agent's past sessions in this folder, read from its own logs. Opening one shows it
 * read-only, with Export; the live session in the pane is not touched.
 */
@Composable
fun HistoryScreen(vm: AppViewModel, paneId: String, session: PastSession?) {
    val pane = vm.pane(paneId)
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        var items by remember(session) { mutableStateOf<List<Item>?>(null) }
        var more by remember(session) { mutableStateOf(false) }
        val title = session?.title ?: "Session"
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = if (session != null) Screen.History(paneId) else Screen.Agent(paneId) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Ink)
            }
            Column(Modifier.weight(1f)) {
                Text(if (session == null) "History" else title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pane?.agentName ?: "Agent"} · ${pane?.folder ?: ""}", fontSize = 12.sp, color = Palette.Muted, maxLines = 1)
            }
            val loaded = items
            if (session != null && !loaded.isNullOrEmpty()) {
                TextButton(onClick = { vm.exportMarkdown(context, title, pane?.agentName ?: "Agent", loaded) }) { Text("Export") }
            }
        }
        HorizontalDivider(color = Palette.Line)
        if (session == null) {
            SessionList(vm, paneId)
            return@Column
        }
        var problem by remember(session) { mutableStateOf<String?>(null) }
        var times by remember(session) { mutableStateOf<Map<String, Long>>(emptyMap()) }
        LaunchedEffect(session) {
            vm.readPastSession(paneId, session).onSuccess { r ->
                items = Transcript.merge(emptyList(), r.items, true)
                more = r.more
                times = Transcript.labelTimesOf(r.items)
            }.onFailure { problem = it.message }
        }
        val list = items
        when {
            problem != null -> Text(problem!!, color = Palette.OrangeInk, modifier = Modifier.padding(16.dp))
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Blue) }
            list.isEmpty() -> Text("Nothing to show in this session.", color = Palette.Muted, modifier = Modifier.padding(16.dp))
            else -> ProvideAttachments(vm::attachmentBytes) {
                TranscriptChat(
                    rows = remember(list) { rowsOf(list) },
                    itemTimes = times,
                    working = false,
                    workingLabel = "",
                    agentName = pane?.agentName ?: "The agent",
                    pending = emptyList(),
                    more = more,
                    size = vm.current?.textSize ?: 12.5f,
                    family = vm.readingFont.family,
                    actions = ChatActions(onQuote = {}, onStop = null, onForward = null),
                    header = {},
                    footer = {},
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SessionList(vm: AppViewModel, paneId: String) {
    var sessions by remember(paneId) { mutableStateOf<List<PastSession>?>(null) }
    var problem by remember(paneId) { mutableStateOf<String?>(null) }
    LaunchedEffect(paneId) {
        vm.pastSessions(paneId).onSuccess { sessions = it }.onFailure { problem = it.message }
    }
    val list = sessions
    when {
        problem != null -> Text(problem!!, color = Palette.OrangeInk, modifier = Modifier.padding(16.dp))
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Blue) }
        list.isEmpty() -> Text("No past sessions in this folder.", color = Palette.Muted, modifier = Modifier.padding(16.dp))
        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(" ", fontSize = 4.sp) }
            items(list, key = { it.ref }) { s ->
                Surface(
                    shape = RoundedCornerShape(14.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line),
                    modifier = Modifier.fillMaxWidth().clickable { vm.screen = Screen.History(paneId, s) },
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(s.title ?: "Untitled session", color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val whenText = if (s.whenSec > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.whenSec * 1000)) else ""
                        Text(whenText + if (s.kb > 0) " · ${s.kb} KB" else "", color = Palette.Muted, fontSize = 12.5.sp)
                    }
                }
            }
            item { Text(" ", fontSize = 12.sp) }
        }
    }
}
