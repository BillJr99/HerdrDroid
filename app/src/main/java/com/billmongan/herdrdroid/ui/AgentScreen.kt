package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.graphics.Color
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

@Composable
fun AgentScreen(vm: AppViewModel, paneId: String) {
    val pane = vm.pane(paneId)
    var message by rememberSaveable(paneId) { mutableStateOf("") }
    var fullTerminal by rememberSaveable { mutableStateOf(false) }
    var confirmCtrlC by remember { mutableStateOf(false) }
    var typingAnswer by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(Hold.OFF) }
    var alt by remember { mutableStateOf(Hold.OFF) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    val textSize = vm.current?.textSize ?: 12.5f
    var pinch by remember { mutableFloatStateOf(1f) }
    val liveSize = (textSize * pinch).coerceIn(8f, 24f)
    val isHermes = pane?.agent?.lowercase() == "hermes"
    var hermesLive by rememberSaveable(paneId) { mutableStateOf(false) }
    val live = isHermes && hermesLive
    val liveNow by rememberUpdatedState(live)
    val structured = vm.transcriptState == TranscriptMode.ON && !fullTerminal
    val listState = rememberLazyListState()

    // The agent's own session log, every 1.5 seconds, where there is one to read. When there
    // isn't, the screen view below is used and the log is looked for again every 15 seconds.
    LaunchedEffect(paneId) {
        vm.resetTranscript(paneId)
        var tick = 0
        while (true) {
            if (!liveNow && vm.hasTranscriptReader(paneId) && (vm.transcriptState != TranscriptMode.OFF || tick % 10 == 0)) {
                vm.readTranscript(paneId)
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

    /** Sends one key with whatever modifiers are on, then lets go of the one-shot ones. */
    fun press(key: String) {
        vm.sendKeys(paneId, chord(ctrl, alt, key))
        if (ctrl == Hold.ONCE) ctrl = Hold.OFF
        if (alt == Hold.ONCE) alt = Hold.OFF
    }
    val modifiersOn = ctrl != Hold.OFF || alt != Hold.OFF
    fun send() {
        if (message.isNotBlank() && pane != null) {
            vm.sendMessage(pane, message.trim()); message = ""; typingAnswer = false
        }
    }
    val quote: (String) -> Unit = { q -> message = q.lines().joinToString("\n") { "> $it" } + "\n\n" }

    val status = pane?.status ?: Status.UNKNOWN
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
                onChoice = { c -> vm.answer(paneId, c.keys, c.pressEnter) },
                onKey = { k -> vm.sendKeys(paneId, k) },
                onTypeAnswer = { typingAnswer = true },
            )
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding().imePadding()) {
        // ---- Header: the agent, what it's doing, and its model -----------------------------
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.screen = Screen.Tabs }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to all tabs", tint = Palette.Ink)
            }
            AgentTile(pane?.initial ?: "?", status, size = 34)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    vm.transcriptTitle?.takeIf { structured } ?: (pane?.agentName ?: paneId),
                    fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (vm.link == Link.CONNECTED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
            if (quota != null) {
                TextButton(onClick = { showUsage = !showUsage }) { Text("Usage", fontSize = 14.sp) }
            }
            if (!live) {
                TextButton(onClick = { fullTerminal = !fullTerminal }) {
                    Text(if (fullTerminal) "Chat" else "Terminal", fontSize = 14.sp)
                }
            }
        }
        if (isHermes) {
            // Hermes can be driven two ways: its CLI session in this pane, or a live chat through
            // its gateway with streaming, tool progress and approvals as buttons.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(Palette.Gray, RoundedCornerShape(12.dp)).padding(3.dp),
            ) {
                listOf(false to "This pane", true to "Live chat").forEach { (value, label) ->
                    val on = hermesLive == value
                    Box(
                        Modifier.weight(1f)
                            .background(if (on) Palette.Card else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { hermesLive = value }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (on) Palette.Ink else Palette.Muted, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.Line)
        ServerBanners(vm)

        // ---- The conversation ---------------------------------------------------------------
        if (live) {
            HermesLiveChat(vm, Modifier.weight(1f).fillMaxWidth())
        } else if (structured) {
            val rows = remember(vm.transcriptItems) { rowsOf(vm.transcriptItems) }
            val family: FontFamily = vm.readingFont.family
            Box(Modifier.weight(1f).fillMaxWidth()) { ProvideAttachments(vm::attachmentBytes) { TranscriptChat(
                rows = rows,
                working = status == Status.WORKING,
                workingLabel = "",
                agentName = pane?.agentName ?: "The agent",
                pending = vm.pendingFor(paneId),
                more = vm.transcriptMore,
                size = liveSize,
                family = family,
                actions = ChatActions(onQuote = quote, onStop = pane?.let { p -> { vm.stopPane(p) } }),
                header = { if (showUsage && quota != null) QuotaDetails(quota) { showUsage = false } },
                footer = promptCard,
                state = listState,
                modifier = Modifier.fillMaxSize().then(pinchToZoom),
            ) } }
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
                if (showUsage && quota != null) QuotaDetails(quota) { showUsage = false }
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
        if (!live) Column(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
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
            if (vm.showKeys || modifiersOn) {
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
                onAttach = { uri -> vm.attach(uri) { path -> message = (message.trimEnd() + " " + path).trim() + " " } },
                attachBusy = vm.uploading,
                commands = SlashCommands.forAgent(pane?.agent),
                leading = { ComposerChip(if (vm.showKeys) "Keys ▾" else "Keys ▴", vm.showKeys) { vm.setKeysShown(!vm.showKeys) } },
            )
        }
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
private fun PromptCard(
    p: Prompt,
    agent: String,
    onChoice: (Choice) -> Unit,
    onKey: (String) -> Unit,
    onTypeAnswer: () -> Unit,
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
            Text(p.question, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp, color = Palette.Ink)
            p.choices.forEachIndexed { i, c ->
                val what = if (c.pressEnter) "sends ${c.keys} + Enter" else "sends ${c.keys}"
                if (i == 0) {
                    Button(
                        onClick = { onChoice(c) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnBlue),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("${c.label}  ·  $what") }
                } else {
                    OutlinedButton(
                        onClick = { onChoice(c) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("${c.label}  ·  $what", color = Palette.Ink) }
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
