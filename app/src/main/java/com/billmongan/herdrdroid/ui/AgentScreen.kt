package com.billmongan.herdrdroid.ui

import androidx.compose.ui.platform.LocalClipboardManager
import com.billmongan.herdrdroid.herdr.screenLinks
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import androidx.compose.foundation.lazy.rememberLazyListState
import com.billmongan.herdrdroid.TranscriptMode
import com.billmongan.herdrdroid.transcript.rowsOf
import com.billmongan.herdrdroid.Link
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Reflow
import com.billmongan.herdrdroid.herdr.Seg
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt

/** A modifier key: off, on for the next key only (tap), or held until tapped again (long-press). */
enum class Hold { OFF, ONCE, LOCKED }

/** Builds herdr's key name, such as "ctrl+r" or "ctrl+alt+up". */
private fun chord(ctrl: Hold, alt: Hold, key: String): String =
    buildString {
        if (ctrl != Hold.OFF) append("ctrl+")
        if (alt != Hold.OFF) append("alt+")
        append(key)
    }

/** herdr's name for a typed character, or null when herdr has no name for it. */
private fun keyName(c: Char): String? = when {
    c == ' ' -> "space"
    c.isLetter() && c.code < 128 -> c.lowercaseChar().toString()
    c.isDigit() -> c.toString()
    c in "[]\\/;',.-=`" -> c.toString()
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(vm: AppViewModel, paneId: String) {
    val pane = vm.pane(paneId)
    var message by rememberSaveable(paneId) { mutableStateOf(vm.takePrefill(paneId) ?: "") }
    var fullTerminal by rememberSaveable { mutableStateOf(false) }
    var confirmCtrlC by remember { mutableStateOf(false) }
    var typingAnswer by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(Hold.OFF) }
    var alt by remember { mutableStateOf(Hold.OFF) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    /** The slash command whose screen is showing, until you go back to the chat. */
    var commandScreen by rememberSaveable(paneId) { mutableStateOf<String?>(null) }
    LaunchedEffect(fullTerminal) { if (!fullTerminal) commandScreen = null }
    var menu by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }
    var hermesMenu by remember { mutableStateOf(false) }
    /** Reading mode: the message box and extra rows step aside; a Reply button brings them back. */
    var reading by rememberSaveable(paneId) { mutableStateOf(false) }
    /** Find in chat: what to look for, and which match is showing. */
    var finding by rememberSaveable(paneId) { mutableStateOf(false) }
    var findText by rememberSaveable(paneId) { mutableStateOf("") }
    var findAt by remember(paneId) { mutableStateOf(0) }
    /** A message open full screen. */
    var opened by remember { mutableStateOf<String?>(null) }
    val keyboardUp = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    val context = LocalContext.current
    val textSize = vm.current?.textSize ?: 12.5f
    var pinch by remember { mutableFloatStateOf(1f) }
    val liveSize = (textSize * pinch).coerceIn(8f, 24f)
    val isHermes = pane?.agent?.lowercase() == "hermes"
    var hermesLive by rememberSaveable(paneId) { mutableStateOf(false) }
    val live = isHermes && hermesLive
    val liveNow by rememberUpdatedState(live)
    val structured = vm.transcriptState == TranscriptMode.ON && !fullTerminal
    val listState = androidx.compose.runtime.key(paneId) { rememberLazyListState() }

    // The agent's own session log, every 1.5 seconds, where there is one to read. When there
    // isn't, the screen view below is used and the log is looked for again every 15 seconds.
    LaunchedEffect(paneId) {
        vm.resetTranscript(paneId)
        var tick = 0
        var streamFailures = 0
        while (true) {
            if (!liveNow && vm.hasTranscriptReader(paneId)) {
                // Once the log is found, stream it (replies show within about a third of a
                // second); if streaming doesn't work here, poll every 1.5 seconds instead.
                if (vm.transcriptState == TranscriptMode.ON && streamFailures < 3) {
                    if (vm.followTranscript(paneId)) { streamFailures = 0; continue } else streamFailures++
                }
                if (vm.transcriptState != TranscriptMode.OFF || tick % 10 == 0) vm.readTranscript(paneId)
            }
            tick++
            kotlinx.coroutines.delay(1_500)
        }
    }
    // The screen itself: the terminal view, the fallback chat view, and questions the agent asks.
    LaunchedEffect(paneId, structured) {
        while (true) {
            vm.readPane(paneId)
            kotlinx.coroutines.delay(if (structured) 2_500 else 1_500)
        }
    }
    LaunchedEffect(paneId) {
        while (true) {
            kotlinx.coroutines.delay(4_000)
            vm.refreshPanes()
        }
    }
    // What runs under this agent (subagents, background tasks, processes), every 5 seconds.
    LaunchedEffect(paneId) {
        while (true) {
            vm.refreshWork(paneId)
            kotlinx.coroutines.delay(5_000)
        }
    }

    /** Sends one key with whatever modifiers are on, then lets go of the one-shot ones. */
    fun press(key: String) {
        vm.sendKeys(paneId, chord(ctrl, alt, key))
        if (ctrl == Hold.ONCE) ctrl = Hold.OFF
        if (alt == Hold.ONCE) alt = Hold.OFF
    }
    val modifiersOn = ctrl != Hold.OFF || alt != Hold.OFF
    fun send() {
        if (message.isNotBlank() && pane != null) {
            val text = message.trim()
            vm.sendMessage(pane, text); message = ""; typingAnswer = false
            // A slash command answers on the screen (a menu, a sign-in code), not in the agent's log,
            // so show the screen and the keys to move through it.
            if (text.startsWith("/") && !live) { fullTerminal = true; commandScreen = text.substringBefore(' ') }
        }
    }
    val quote: (String) -> Unit = { q -> message = q.lines().joinToString("\n") { "> $it" } + "\n\n" }

    // As the list shows it: Done becomes Idle once you have opened it here.
    val status = pane?.let { vm.shownStatus(it) } ?: Status.UNKNOWN
    val prompt: Prompt? = if (typingAnswer) null else detectPrompt(vm.paneText, status == Status.NEEDS_YOU)
    val scroll = rememberScrollState()
    LaunchedEffect(vm.paneText, structured) { if (!structured) scroll.animateScrollTo(scroll.maxValue) }
    val quota = vm.quotaFor(pane)
    val onZoom by rememberUpdatedState<(Float) -> Unit> { z -> pinch = (pinch * z) }
    val onZoomEnd by rememberUpdatedState<() -> Unit> {
        if (pinch != 1f) {
            vm.setTextSize(liveSize)
            pinch = 1f
        }
    }
    val pinchToZoom = Modifier.pointerInput(Unit) {
        // Two fingers change the text size; one finger still scrolls.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoomed = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val z = event.calculateZoom()
                    if (z != 1f) {
                        onZoom(z)
                        zoomed = true
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (zoomed) onZoomEnd()
        }
    }
    val promptCard: @Composable () -> Unit = {
        prompt?.let { p ->
            PromptCard(
                p,
                agent = pane?.agentName ?: "The agent",
                onChoice = { c -> vm.answerChoice(paneId, p, c) },
                // What the agent said since your last message (in full, with its images and files),
                // and the edit waiting for approval: the last tool call in the log, still running.
                preview = run {
                    val items = if (structured) vm.transcriptItems else emptyList()
                    val since = items.drop(items.indexOfLast { it is com.billmongan.herdrdroid.transcript.UserItem } + 1)
                    val said = since.filterIsInstance<com.billmongan.herdrdroid.transcript.TextItem>().takeLast(2)
                    val media = since.filterIsInstance<com.billmongan.herdrdroid.transcript.ImageItem>() +
                        since.filterIsInstance<com.billmongan.herdrdroid.transcript.ToolItem>().flatMap { t ->
                            t.attach.map { path -> com.billmongan.herdrdroid.transcript.ImageItem("att:$path", path = path, mime = com.billmongan.herdrdroid.transcript.mimeFor(path), name = path.substringAfterLast('/')) }
                        }
                    val edit = (items.lastOrNull { it is com.billmongan.herdrdroid.transcript.ToolItem } as? com.billmongan.herdrdroid.transcript.ToolItem)
                        ?.takeIf { it.state == com.billmongan.herdrdroid.transcript.ToolState.RUNNING && (it.diff != null || !it.patch.isNullOrBlank()) }
                    if (said.isEmpty() && media.isEmpty() && edit == null) null else ({
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            said.forEach { MarkdownText(it.text, liveSize, vm.readingFont.family) }
                            media.forEach { AttachmentView(it) }
                            edit?.let { t -> EditPreview(t, liveSize) }
                        }
                    })
                },
                onKey = { k -> vm.sendKeys(paneId, k) },
                onTypeAnswer = { typingAnswer = true },
            )
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding().imePadding()) {
        // ---- Header: the agent, what it's doing, and its model -----------------------------
        // Swipe the header left or right to move to the next or previous agent.
        var swipe by remember { mutableFloatStateOf(0f) }
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = if (keyboardUp) 0.dp else 6.dp, bottom = if (keyboardUp) 0.dp else 6.dp)
                .pointerInput(paneId) {
                    detectHorizontalDragGestures(
                        onDragStart = { swipe = 0f },
                        onDragEnd = {
                            if (kotlin.math.abs(swipe) > 120f) vm.neighbor(paneId, if (swipe < 0) 1 else -1)?.let { vm.openPane(it) }
                            swipe = 0f
                        },
                        onHorizontalDrag = { _, d -> swipe += d },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.screen = Screen.Tabs }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to all tabs", tint = Palette.Ink)
            }
            AgentTile(pane?.initial ?: "?", status, size = 34)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                // On Hermes the name opens the Hermes menu: this pane or live chat, your Telegram
                // conversation, all conversations, a new chat, model and usage.
                Box {
                    Text(
                        (if (isHermes) (if (live) "Hermes · live chat" else "Hermes · this pane") + "  ▾"
                        else vm.transcriptTitle?.takeIf { structured } ?: (pane?.agentName ?: paneId)),
                        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = if (isHermes) Modifier.clickable { hermesMenu = true; vm.hermesChat()?.refreshSessions() } else Modifier,
                    )
                    if (isHermes) DropdownMenu(expanded = hermesMenu, onDismissRequest = { hermesMenu = false }) {
                        DropdownMenuItem(text = { Text((if (!live) "✓ " else "    ") + "This pane (the Hermes CLI)") }, onClick = { hermesMenu = false; hermesLive = false })
                        DropdownMenuItem(text = { Text((if (live) "✓ " else "    ") + "Live chat (streaming, approvals as buttons)") }, onClick = { hermesMenu = false; hermesLive = true })
                        HorizontalDivider(color = Palette.Line)
                        // Your Hermes conversations by name, newest first (Telegram, the CLI, earlier
                        // chats); tap one to switch to it in live chat.
                        val chat = vm.hermesChat()
                        DropdownMenuItem(text = { Text("＋ New chat", fontWeight = FontWeight.SemiBold) }, onClick = { hermesMenu = false; hermesLive = true; chat?.newChat() })
                        val list = chat?.sessions.orEmpty().take(10)
                        if (list.isEmpty()) {
                            DropdownMenuItem(text = { Text(if (chat?.loadingSessions == true) "Loading conversations…" else "No other conversations yet", color = Palette.Muted) }, onClick = {}, enabled = false)
                        }
                        list.forEach { sess ->
                            val on = live && chat?.openId == sess.id
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text((if (on) "✓ " else "") + sess.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(listOfNotNull(sourceName(sess.source), sess.model, if (sess.running) "working" else null).joinToString(" · "),
                                            color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
                                    }
                                },
                                onClick = { hermesMenu = false; hermesLive = true; chat?.open(sess) },
                            )
                        }
                        DropdownMenuItem(text = { Text("All conversations…") }, onClick = { hermesMenu = false; hermesLive = true; chat?.showPicker() })
                        HorizontalDivider(color = Palette.Line)
                        DropdownMenuItem(text = { Text("Model and reasoning…") }, onClick = { hermesMenu = false; choosingModel = true })
                        if (quota != null) DropdownMenuItem(text = { Text(if (showUsage) "Hide usage" else "Usage") }, onClick = { hermesMenu = false; showUsage = !showUsage })
                    }
                }
                if (keyboardUp) {
                    // Keyboard up: the name line only, so the chat keeps the room.
                } else if (vm.link == Link.CONNECTED) {
                    // Tap the model line to choose another model (or effort).
                    Row(
                        Modifier.clickable(enabled = pane?.agent != null) { choosingModel = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).background(look(status).ink, CircleShape))
                        Text(
                            "  " + look(status).label + " · " + (vm.modelLine(paneId) ?: pane?.folder ?: paneId),
                            color = Palette.Muted, fontSize = 12.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    LinkLabel(vm, "")
                }
            }
            // Shells and subagents still running under the agent, as one small pill in the header
            // (a row of its own appeared and vanished as they started and finished, moving the chat).
            vm.work[paneId]?.let { w ->
                val subs = w.activeSubagents
                val shells = w.activeProcs(pane?.agent).size
                if (subs > 0 || shells > 0) {
                    Text(
                        listOfNotNull(shells.takeIf { it > 0 }?.let { "$it sh" }, subs.takeIf { it > 0 }?.let { "$it sub" }).joinToString(" · "),
                        color = Palette.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.BlueSoft)
                            .clickable { vm.screen = Screen.Work(paneId) }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (!live && vm.transcriptState == TranscriptMode.ON) {
                TextButton(onClick = { fullTerminal = !fullTerminal }) {
                    Text(if (fullTerminal) "Chat" else "Terminal", fontSize = 14.sp)
                }
            }
            // Everything else lives in one menu, so the header stays readable on a phone.
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Palette.Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (quota != null) DropdownMenuItem(text = { Text(if (showUsage) "Hide usage" else "Usage") }, onClick = { menu = false; showUsage = !showUsage })
                    DropdownMenuItem(text = { Text("Usage history, all agents") }, onClick = { menu = false; vm.screen = Screen.Usage })
                    if (pane != null) DropdownMenuItem(text = { Text("Files") }, onClick = { menu = false; vm.openFiles(pane.cwd.ifBlank { "~" }) })
                    if (pane?.agent != null && vm.hasTranscriptReader(paneId)) {
                        DropdownMenuItem(text = { Text("History") }, onClick = { menu = false; vm.screen = Screen.History(paneId) })
                    }
                    if (pane?.agent != null) DropdownMenuItem(text = { Text("Model…") }, onClick = { menu = false; choosingModel = true })
                    if (structured && vm.transcriptItems.isNotEmpty()) DropdownMenuItem(text = { Text("Find in chat") }, onClick = { menu = false; finding = true })
                    if (!live) DropdownMenuItem(text = { Text(if (reading) "Leave reading mode" else "Reading mode") }, onClick = { menu = false; reading = !reading })
                    DropdownMenuItem(text = { Text("Running underneath") }, onClick = { menu = false; vm.screen = Screen.Work(paneId) })
                    if (structured && vm.transcriptItems.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Export as Markdown") }, onClick = {
                            menu = false
                            vm.exportMarkdown(context, vm.transcriptTitle ?: "${pane?.agentName ?: "Agent"} in ${pane?.folder ?: ""}", pane?.agentName ?: "Agent", vm.transcriptItems)
                        })
                    }
                    if (!live && vm.transcriptState != TranscriptMode.ON) {
                        DropdownMenuItem(text = { Text(if (fullTerminal) "Chat view" else "Terminal view") }, onClick = { menu = false; fullTerminal = !fullTerminal })
                    }
                }
            }
        }
        // What the agent has running underneath: tap to drill in.
        // One chip: how much is still active underneath (subagents, background tasks, processes the
        // agent started). It drops as they finish; tap for active and finished.
        // Two bubbles on their own row under the header: the shells the agent started that are
        // still running, and its running subagents. Tap either for active and finished.
        HorizontalDivider(color = Palette.Line)
        ServerBanners(vm)
        val rowsNow = remember(vm.transcriptItems) { rowsOf(vm.transcriptItems) }
        val matches = remember(rowsNow, findText, finding) {
            if (!finding || findText.trim().length < 2) emptyList() else rowsNow.withIndex().filter { (_, r) ->
                val it = (r as? com.billmongan.herdrdroid.transcript.OneRow)?.item
                val t = when (it) {
                    is com.billmongan.herdrdroid.transcript.UserItem -> it.text
                    is com.billmongan.herdrdroid.transcript.TextItem -> it.text
                    else -> null
                }
                t?.contains(findText.trim(), ignoreCase = true) == true
            }.map { it.index to it.value.key }
        }
        LaunchedEffect(matches) { findAt = (matches.size - 1).coerceAtLeast(0) }
        LaunchedEffect(findAt, matches) { matches.getOrNull(findAt)?.let { (i, _) -> listState.animateScrollToItem(i + 1) } }
        if (finding && structured) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    findText, { findText = it }, singleLine = true,
                    placeholder = { Text("Find in this chat", color = Palette.Muted) },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card),
                    modifier = Modifier.weight(1f).height(52.dp),
                )
                Text(
                    if (findText.trim().length < 2) "" else if (matches.isEmpty()) "None" else "${findAt + 1} of ${matches.size}",
                    color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(onClick = { if (matches.isNotEmpty()) findAt = (findAt - 1 + matches.size) % matches.size }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Earlier match", tint = Palette.Ink)
                }
                IconButton(onClick = { if (matches.isNotEmpty()) findAt = (findAt + 1) % matches.size }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Later match", tint = Palette.Ink, modifier = Modifier.graphicsLayer(rotationZ = 180f))
                }
                TextButton(onClick = { finding = false; findText = "" }) { Text("Done") }
            }
        }

        // ---- The conversation ---------------------------------------------------------------
        if (live) {
            HermesLiveChat(vm, Modifier.weight(1f).fillMaxWidth())
        } else if (structured) {
            val rows = rowsNow
            val family: FontFamily = vm.readingFont.family
            Box(Modifier.weight(1f).fillMaxWidth()) { ProvideAttachments(vm::attachmentBytes) { androidx.compose.runtime.CompositionLocalProvider(LocalChatZoom provides liveSize / 12.5f) { TranscriptChat(
                rows = rows,
                working = status == Status.WORKING,
                workingLabel = "",
                agentName = pane?.agentName ?: "The agent",
                pending = vm.pendingFor(paneId),
                more = vm.transcriptMore,
                size = liveSize,
                family = family,
                actions = ChatActions(
                    onQuote = quote, onStop = pane?.let { p -> { vm.stopPane(p) } },
                    undelivered = vm.undeliveredFor(paneId), onResend = vm::resendPending, onDismissPending = vm::dismissPending,
                    onLoadEarlier = if (vm.canLoadEarlier) ({ vm.loadEarlier(paneId) }) else null, loadingEarlier = vm.loadingEarlier,
                    queuedNote = "Queued in ${pane?.agentName ?: "the agent"} · it reads these when its current step ends",
                    onOpen = { t -> opened = t },
                    focusKey = matches.getOrNull(findAt)?.second,
                ),
                header = { if (showUsage && quota != null) QuotaDetails(quota, vm.quotaHistoryFor(pane)) { showUsage = false } },
                footer = promptCard,
                state = listState,
                modifier = Modifier.fillMaxSize().then(pinchToZoom),
            ) } } }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(pinchToZoom)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showUsage && quota != null) QuotaDetails(quota, vm.quotaHistoryFor(pane)) { showUsage = false }
                val text = vm.paneText
                if (text.isBlank()) {
                    Text("Loading…", color = Palette.Muted, fontSize = 15.sp)
                } else if (fullTerminal) {
                    SelectionContainer {
                        Text(
                            text,
                            color = Palette.TerminalInk,
                            fontFamily = Fonts.Mono,
                            fontSize = (liveSize * 0.88f).sp,
                            lineHeight = (liveSize * 0.88f * 1.35f).sp,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Palette.Terminal, RoundedCornerShape(12.dp))
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                } else {
                    val pending = vm.lastSent?.takeIf { it.first == paneId }?.second
                    ChatView(text, pending, liveSize, vm.readingFont)
                }
                promptCard()
            }
        }

        // ---- Keys and the message box -------------------------------------------------------
        if (reading && !live) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { reading = false }, shape = RoundedCornerShape(20.dp)) { Text("Reply") }
            }
        }
        if (!live && !reading) Column(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = if (keyboardUp) 2.dp else 6.dp, bottom = if (keyboardUp) 4.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (modifiersOn) {
                val names = listOfNotNull("Ctrl".takeIf { ctrl != Hold.OFF }, "Alt".takeIf { alt != Hold.OFF }).joinToString("+")
                val held = ctrl == Hold.LOCKED || alt == Hold.LOCKED
                Text(
                    if (held) "$names is held for every key and letter. Tap it to let go."
                    else "$names goes with the next key or letter you type.",
                    color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            // Messages waiting for this agent to be ready ("When idle").
            vm.queued.filter { it.paneId == paneId }.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().background(Palette.BlueSoft, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Queued until idle: " + q.text.lines().first(), color = Palette.Blue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("Cancel", color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { vm.cancelQueued(q) }.padding(start = 8.dp))
                }
            }
            if (fullTerminal) {
                val found = remember(vm.paneText) { screenLinks(vm.paneText) }
                if (commandScreen != null || found.urls.isNotEmpty() || found.codes.isNotEmpty()) {
                    val clipboard = LocalClipboardManager.current
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        commandScreen?.let { c ->
                            ComposerChip("Back to chat", false) { fullTerminal = false }
                            Text("$c: use the keys to choose", color = Palette.Muted, fontSize = 12.5.sp, maxLines = 1, softWrap = false)
                        }
                        found.urls.forEach { u ->
                            ComposerChip("Open " + u.substringAfter("://").take(40), false) {
                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) }
                            }
                        }
                        found.codes.forEach { c ->
                            ComposerChip("Copy $c", false) { clipboard.setText(androidx.compose.ui.text.AnnotatedString(c)) }
                        }
                    }
                }
            }
            if ((vm.showKeys && !keyboardUp) || modifiersOn || commandScreen != null) {
                KeyPad(
                    ctrl = ctrl, alt = alt,
                    onCtrl = { ctrl = it }, onAlt = { alt = it },
                    onKey = ::press,
                    onCtrlC = { confirmCtrlC = true },
                )
            }
            ChatComposer(
                value = message,
                onValueChange = { new ->
                    // With Ctrl or Alt on, a typed character becomes a key chord instead of text.
                    if (modifiersOn && new.length == message.length + 1 && new.startsWith(message)) {
                        keyName(new.last())?.let { press(it) }
                    } else {
                        message = new
                    }
                },
                placeholder = if (modifiersOn) "Type a letter to send it with ${if (ctrl != Hold.OFF) "Ctrl" else "Alt"}"
                else "Message ${pane?.agentName ?: "this tab"}",
                canSend = message.isNotBlank() && pane != null,
                onSend = ::send,
                working = status == Status.WORKING,
                onStop = pane?.let { p -> { vm.stopPane(p) } },
                // herdr's agent prompt reaches a working agent, so a message sent now steers it.
                steerLabel = if (pane?.agent != null) "Steer" else null,
                onAttach = { uri -> vm.attach(uri) { path -> message = (message.trimEnd() + " " + path).trim() + " " } },
                attachBusy = vm.uploading,
                commands = SlashCommands.forAgent(pane?.agent),
                // One row: Keys, Snippets and When idle live in the "⋯" menu with attach and dictate.
                compact = true,
                menuItems = buildList {
                    add((if (vm.showKeys) "Hide keys" else "Show keys") to { vm.setKeysShown(!vm.showKeys) })
                    add("Snippets" to { showSnippets = true })
                    if (status == Status.WORKING && message.isNotBlank()) add("Send when idle" to { vm.enqueue(paneId, message.trim()); message = "" })
                    add("Reading mode" to { reading = true })
                },
            )
        }
    }

    if (showSnippets) {
        SnippetsDialog(vm, onInsert = { t -> message = if (message.isBlank()) t else message.trimEnd() + "\n" + t }, onDismiss = { showSnippets = false })
    }

    opened?.let { t ->
        FullMessage(t, liveSize, vm.readingFont.family, onQuote = { quote(t); opened = null; reading = false }, onClose = { opened = null })
    }

    if (choosingModel && pane != null) {
        val presets = ModelPresets.forAgent(pane.agent)
        val seen = if (ModelPresets.takesName(pane.agent)) vm.knownModels(pane.agent) else emptyList()
        val efforts = ModelPresets.efforts(pane.agent)
        val now = vm.models[paneId]
        val idle = status != Status.WORKING
        AlertDialog(
            onDismissRequest = { choosingModel = false },
            title = { Text("Model for ${pane.agentName}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Now: " + (vm.modelLine(paneId) ?: "not known yet"), color = Palette.Muted, fontSize = 13.sp)
                    if (!idle) {
                        Text("${pane.agentName} is working. The model can be changed once it's done.", color = Palette.OrangeInk, fontSize = 14.sp)
                    }
                    val choice: @Composable (String, String, Boolean) -> Unit = { label, cmd, current ->
                        Surface(
                            shape = RoundedCornerShape(12.dp), color = if (current) Palette.BlueSoft else Palette.Card,
                            border = BorderStroke(1.dp, if (current) Palette.Blue else Palette.Line),
                            modifier = Modifier.fillMaxWidth().clickable(enabled = idle) {
                                choosingModel = false
                                vm.runCommand(paneId, cmd)
                            },
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                Text(label + if (current) "  (now)" else "", color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(cmd, color = Palette.Muted, fontFamily = Fonts.Mono, fontSize = 12.sp)
                            }
                        }
                    }
                    presets.forEach { (label, cmd) -> choice(label, cmd, now?.contains(label, ignoreCase = true) == true) }
                    val extra = seen.filter { m -> presets.none { m.equals(it.first, ignoreCase = true) } }
                    if (extra.isNotEmpty()) {
                        Text("Used before", color = Palette.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        extra.forEach { m -> choice(m, "/model $m", m == now) }
                    }
                    if (efforts.isNotEmpty()) {
                        Text("Reasoning effort", color = Palette.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            efforts.forEach { (label, cmd) ->
                                ComposerChip(label, vm.efforts[paneId].equals(label, ignoreCase = true)) {
                                    if (idle) { choosingModel = false; vm.runCommand(paneId, cmd) }
                                }
                            }
                        }
                    }
                    Text(
                        "\"Open the picker\" sends the agent's own model command and shows the terminal, where you choose with the keys.",
                        color = Palette.Muted, fontSize = 12.5.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = idle, onClick = {
                    choosingModel = false
                    vm.runCommand(paneId, ModelPresets.pickerCommand(pane.agent))
                    fullTerminal = true
                    vm.setKeysShown(true)
                }) { Text("Open the picker") }
            },
            dismissButton = { TextButton(onClick = { choosingModel = false }) { Text("Cancel") } },
        )
    }

    if (confirmCtrlC) {
        AlertDialog(
            onDismissRequest = { confirmCtrlC = false },
            title = { Text("Send Ctrl+C?") },
            text = { Text("This interrupts whatever ${pane?.agentName ?: "the tab"} is doing right now.") },
            confirmButton = {
                Button(onClick = { confirmCtrlC = false; vm.sendKeys(paneId, "ctrl+c") }) { Text("Interrupt") }
            },
            dismissButton = { TextButton(onClick = { confirmCtrlC = false }) { Text("Cancel") } },
        )
    }
}

// ---- Keys -------------------------------------------------------------------------------------

private val KEY_HEIGHT = 42.dp

/**
 * Two rows that always fit the screen: Ctrl, Alt and the four arrows together on top, then
 * Esc, Tab, Backspace, Enter and Ctrl+C.
 */
@Composable
private fun KeyPad(
    ctrl: Hold,
    alt: Hold,
    onCtrl: (Hold) -> Unit,
    onAlt: (Hold) -> Unit,
    onKey: (String) -> Unit,
    onCtrlC: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModifierKey("Ctrl", ctrl, onCtrl, Modifier.weight(1.2f))
            ModifierKey("Alt", alt, onAlt, Modifier.weight(1.2f))
            ArrowKeys(onKey, Modifier.weight(4f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton("Esc", Modifier.weight(1f)) { onKey("esc") }
            KeyButton("Tab", Modifier.weight(1f)) { onKey("tab") }
            KeyButton("⌫", Modifier.weight(1f)) { onKey("backspace") }
            KeyButton("Enter", Modifier.weight(1.3f)) { onKey("enter") }
            KeyButton("Ctrl+C", Modifier.weight(1.4f), danger = true) { onCtrlC() }
        }
    }
}

/** The four arrows side by side in one rounded strip, like a keyboard's arrow cluster. */
@Composable
private fun ArrowKeys(onKey: (String) -> Unit, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Palette.Key,
        border = BorderStroke(1.dp, Palette.KeyEdge),
        modifier = modifier.height(KEY_HEIGHT),
    ) {
        Row {
            listOf("←" to "left", "↑" to "up", "↓" to "down", "→" to "right").forEachIndexed { i, (label, key) ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.KeyEdge))
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onKey(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = Palette.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (danger) Palette.OrangeSoft else Palette.Key,
        border = BorderStroke(1.dp, if (danger) Palette.OrangeEdge else Palette.KeyEdge),
        modifier = modifier.height(KEY_HEIGHT).widthIn(min = 44.dp).clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (danger) Palette.OrangeInk else Palette.Ink,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
    }
}

/** Tap: on for the next key. Long-press: held until tapped again. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModifierKey(label: String, state: Hold, onChange: (Hold) -> Unit, modifier: Modifier) {
    val on = state != Hold.OFF
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (on) Palette.Blue else Palette.Key,
        border = BorderStroke(if (state == Hold.LOCKED) 2.dp else 1.dp, if (on) Palette.Blue else Palette.KeyEdge),
        modifier = modifier
            .height(KEY_HEIGHT)
            .combinedClickable(
                onClick = { onChange(if (state == Hold.OFF) Hold.ONCE else Hold.OFF) },
                onLongClick = { onChange(if (state == Hold.LOCKED) Hold.OFF else Hold.LOCKED) },
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (state == Hold.LOCKED) "$label ●" else label,
                color = if (on) Palette.OnBlue else Palette.Ink,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
    }
}

// ---- The prompt card --------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PromptCard(
    p: Prompt,
    agent: String,
    onChoice: (Choice) -> Unit,
    onKey: (String) -> Unit,
    onTypeAnswer: () -> Unit,
    /** The change the agent asks to make (an edit's diff), shown above the question. */
    preview: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Palette.Card,
        border = BorderStroke(2.dp, Palette.Orange),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.OrangeInk, modifier = Modifier.size(18.dp))
                Text("$agent is asking".uppercase(), color = Palette.OrangeInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            // The question and its choices scroll inside the card when they don't all fit, so the
            // keys below stay in reach. Each choice grows to fit its whole label.
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                preview?.invoke()
                Text(p.question, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp, color = Palette.Ink)
                p.choices.forEachIndexed { i, c ->
                    val what = if (c.pressEnter) "sends ${c.keys} + Enter" else "sends ${c.keys}"
                    val first = i == 0
                    val body: @Composable () -> Unit = {
                        Column(Modifier.fillMaxWidth()) {
                            Text(c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                                color = if (first) Palette.OnBlue else Palette.Ink)
                            Text(what, fontSize = 12.sp, color = if (first) Palette.OnBlue.copy(alpha = 0.8f) else Palette.Muted)
                        }
                    }
                    val pad = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    if (first) {
                        Button(
                            onClick = { onChoice(c) },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnBlue),
                            contentPadding = pad,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { body() }
                    } else {
                        OutlinedButton(
                            onClick = { onChoice(c) },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = pad,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { body() }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton("Esc") { onKey("esc") }
                KeyButton("Tab") { onKey("tab") }
                KeyButton("Shift+Tab") { onKey("shift+tab") }
                KeyButton("Ctrl+C", danger = true) { onKey("ctrl+c") }
            }
            TextButton(onClick = onTypeAnswer, modifier = Modifier.fillMaxWidth()) { Text("Type a different answer") }
            Text(
                "Each button types its key into the tab, exactly as if you typed it there. For any other " +
                    "combination, turn on Ctrl or Alt below and type the letter.",
                color = Palette.Muted,
                fontSize = 12.sp,
            )
        }
    }
}

// ---- The chat view ----------------------------------------------------------------------------

private fun markGlyph(mark: String): String = when {
    mark == "-" || mark == "*" || mark == "·" -> "•"
    mark == "⎿" -> "↳"
    else -> mark
}

private fun markColor(mark: String): Color = when (mark) {
    "●", "⏺", "◆", "◈", "◇", "★" -> Palette.Blue
    "✓", "✔" -> Palette.Green
    "✗", "✘" -> Palette.OrangeInk
    else -> Palette.Muted
}

private fun norm(s: String) = s.filterNot { it.isWhitespace() }.take(40)

/**
 * The pane as a conversation: the agent's paragraphs rewrapped for the phone in the reading
 * font, your messages as bubbles on the right, tables and boxes in monospace strips that scroll
 * sideways, and the agent's own input box and status line as a quiet footer.
 */
@Composable
private fun ChatView(text: String, pending: String?, size: Float, font: ReadingFont) {
    val segs = remember(text) { Reflow.parse(text) }
    val family: FontFamily = font.family
    val prose = if (font == ReadingFont.MONO) size else size * 1.2f
    val showPending = pending != null && segs.none { it is Seg.You && norm(it.text).startsWith(norm(pending).take(20)) }

    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            segs.forEach { s ->
                when (s) {
                    Seg.Gap -> Spacer(Modifier.height(6.dp))
                    Seg.Rule -> HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 6.dp))
                    is Seg.You -> YouBubble(s.text, prose)
                    is Seg.Code -> Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Palette.Code,
                        border = BorderStroke(1.dp, Palette.Line),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text(
                            s.lines.joinToString("\n"),
                            fontFamily = Fonts.Mono,
                            fontSize = (size * 0.88f).sp,
                            lineHeight = (size * 0.88f * 1.4f).sp,
                            softWrap = false,
                            color = Palette.Ink,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    is Seg.Para -> {
                        val fs = if (s.detail) prose * 0.88f else prose
                        val ink = if (s.detail) Palette.Muted else Palette.Ink
                        Row(Modifier.fillMaxWidth().padding(start = (s.indent.coerceAtMost(16) * 6).dp)) {
                            if (s.mark != null) {
                                Text(
                                    markGlyph(s.mark),
                                    color = markColor(s.mark),
                                    fontFamily = family,
                                    fontSize = fs.sp,
                                    lineHeight = (fs * 1.5f).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.widthIn(min = (fs * 1.3f).dp).padding(end = 6.dp),
                                )
                            }
                            Text(
                                s.text,
                                color = ink,
                                fontFamily = family,
                                fontSize = fs.sp,
                                lineHeight = (fs * 1.5f).sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    is Seg.Footer -> Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(bottom = 6.dp))
                        s.lines.forEach { l ->
                            Text(
                                l,
                                fontFamily = Fonts.Mono,
                                fontSize = (size * 0.8f).sp,
                                color = Palette.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (showPending) YouBubble(pending!!, prose, sending = true)
        }
    }
}

@Composable
private fun YouBubble(text: String, size: Float, sending: Boolean = false) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            color = Palette.Ink.copy(alpha = if (sending) 0.6f else 1f),
            fontFamily = Fonts.Sans,
            fontSize = size.sp,
            lineHeight = (size * 1.45f).sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .heightIn(min = 20.dp)
                .background(Palette.Bubble, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** The edit an agent asks to make: its diff, or its patch text, above the question. */
@Composable
private fun EditPreview(t: com.billmongan.herdrdroid.transcript.ToolItem, size: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Proposed change" + (t.path?.let { " · " + it.substringAfterLast('/') } ?: ""), color = Palette.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        val d = t.diff
        if (d != null) DiffCard(d, size)
        else SelectionContainer {
            Text(t.patch.orEmpty().take(20_000), fontFamily = Fonts.Mono, fontSize = 11.5.sp, color = Palette.Ink,
                modifier = Modifier.fillMaxWidth().background(Palette.Code, RoundedCornerShape(10.dp)).horizontalScroll(rememberScrollState()).padding(10.dp))
        }
    }
}

/** One message on the whole screen, to read in peace, with Copy, Quote and Share. */
@Composable
private fun FullMessage(text: String, size: Float, family: FontFamily, onQuote: () -> Unit, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = Palette.Ground, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Palette.Ink) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text("Copy") }
                    TextButton(onClick = onQuote) { Text("Quote") }
                    TextButton(onClick = {
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
                        runCatching { context.startActivity(android.content.Intent.createChooser(i, "Share")) }
                    }) { Text("Share") }
                }
                HorizontalDivider(color = Palette.Line)
                SelectionContainer(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
                        MarkdownText(text, size * 1.2f, family)
                        Spacer(Modifier.height(40.dp))
                    }
                }
            }
        }
    }
}

/** Where a Hermes conversation came from, in words. */
private fun sourceName(source: String): String? = when (source.lowercase()) {
    "" -> null
    "telegram" -> "Telegram"
    "cli" -> "Hermes CLI"
    "tui", "local" -> "Hermes app"
    "api_server" -> "API"
    else -> source.replaceFirstChar { it.uppercase() }
}
