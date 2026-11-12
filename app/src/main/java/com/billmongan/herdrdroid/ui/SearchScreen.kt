package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.SearchHit
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.transcript.Item
import com.billmongan.herdrdroid.transcript.rowsOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Searches every agent's past conversations on the open server; a result opens read-only. */
@Composable
fun SearchScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<SearchHit?>(null) }
    val scope = rememberCoroutineScope()
    fun run() {
        val q = query.trim()
        if (q.length < 2 || busy) return
        busy = true; problem = null
        scope.launch {
            vm.searchSessions(q).onSuccess { hits = it }.onFailure { problem = it.message }
            busy = false
        }
    }
    val o = opened
    if (o != null) {
        HitView(vm, o) { opened = null }
        return
    }
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.Tabs }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Ink) }
            Text("Search conversations", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        }
        HorizontalDivider(color = Palette.Line)
        OutlinedTextField(
            query, { query = it }, singleLine = true,
            label = { Text("Words to find") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            trailingIcon = { TextButton(onClick = ::run, enabled = query.trim().length >= 2 && !busy) { Text("Search") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Text(
            "Every agent's logs on ${vm.current?.label ?: "this server"}: Claude, Codex, pi, Oh My Pi, Grok, Copilot and Hermes. One result per conversation, newest first.",
            color = Palette.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        when {
            busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Blue) }
            problem != null -> Text(problem!!, color = Palette.OrangeInk, modifier = Modifier.padding(16.dp))
            hits?.isEmpty() == true -> Text("Nothing found.", color = Palette.Muted, modifier = Modifier.padding(16.dp))
            hits != null -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Spacer(Modifier.height(8.dp)) }
                items(hits!!, key = { it.agent + it.ref }) { h ->
                    Surface(
                        shape = RoundedCornerShape(14.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line),
                        modifier = Modifier.fillMaxWidth().clickable { opened = h },
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val name = Pane("", "", "", h.agent, Status.UNKNOWN, "", "").agentName
                            val whenText = if (h.whenSec > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(h.whenSec * 1000)) else ""
                            Text("$name · $whenText", color = Palette.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(h.title ?: "Untitled conversation", color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("…" + h.snippet.trim() + "…", color = Palette.Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun HitView(vm: AppViewModel, hit: SearchHit, onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember(hit) { mutableStateOf<List<Item>?>(null) }
    var more by remember(hit) { mutableStateOf(false) }
    var problem by remember(hit) { mutableStateOf<String?>(null) }
    LaunchedEffect(hit) { vm.readHit(hit).onSuccess { (i, m) -> items = i; more = m }.onFailure { problem = it.message } }
    val name = Pane("", "", "", hit.agent, Status.UNKNOWN, "", "").agentName
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Ink) }
            Column(Modifier.weight(1f)) {
                Text(hit.title ?: "Conversation", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$name · read-only", fontSize = 12.sp, color = Palette.Muted)
            }
            val loaded = items
            if (!loaded.isNullOrEmpty()) TextButton(onClick = { vm.exportMarkdown(context, hit.title ?: "conversation", name, loaded) }) { Text("Export", softWrap = false) }
        }
        HorizontalDivider(color = Palette.Line)
        val list = items
        when {
            problem != null -> Text(problem!!, color = Palette.OrangeInk, modifier = Modifier.padding(16.dp))
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Blue) }
            else -> ProvideAttachments(vm::attachmentBytes) {
                TranscriptChat(
                    rows = remember(list) { rowsOf(list) }, working = false, workingLabel = "", agentName = name,
                    pending = emptyList(), more = more, size = vm.current?.textSize ?: 12.5f, family = vm.readingFont.family,
                    actions = ChatActions(onQuote = {}, onStop = null, onForward = null),
                    header = {}, footer = {}, state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
