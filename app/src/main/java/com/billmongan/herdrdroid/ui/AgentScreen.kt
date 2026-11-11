package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt

private data class QuickKey(val label: String, val key: String)

private val QUICK_KEYS = listOf(
    QuickKey("Esc", "esc"),
    QuickKey("Tab", "tab"),
    QuickKey("←", "left"),
    QuickKey("↑", "up"),
    QuickKey("↓", "down"),
    QuickKey("→", "right"),
    QuickKey("⌫", "backspace"),
    QuickKey("Enter", "enter"),
)

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

    // Re-read the pane every 1.5 seconds while this screen is open.
    LaunchedEffect(paneId) {
        while (true) {
            vm.readPane(paneId)
            kotlinx.coroutines.delay(1_500)
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

    val status = pane?.status ?: Status.UNKNOWN
    val prompt: Prompt? = if (typingAnswer) null else detectPrompt(vm.paneText, status == Status.NEEDS_YOU)
    val scroll = rememberScrollState()
    LaunchedEffect(vm.paneText) { scroll.animateScrollTo(scroll.maxValue) }
    val quota = vm.quotaFor(pane)
    val onZoom by rememberUpdatedState<(Float) -> Unit> { z -> pinch = (pinch * z) }
    val onZoomEnd by rememberUpdatedState<() -> Unit> {
        if (pinch != 1f) {
            vm.setTextSize(liveSize)
            pinch = 1f
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding().imePadding()) {
        Surface(color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.screen = Screen.Tabs }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to all tabs", tint = Palette.Ink)
                }
                AgentTile(pane?.initial ?: "?", status, size = 36)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(pane?.agentName ?: paneId, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Ink)
                    if (vm.link == com.billmongan.herdrdroid.Link.CONNECTED) {
                        Text(
                            look(status).label + " · " + (vm.models[paneId] ?: paneId),
                            color = look(status).ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    } else {
                        LinkLabel(vm, "")
                    }
                }
                if (quota != null) {
                    TextButton(onClick = { showUsage = !showUsage }) { Text("Usage") }
                }
                TextButton(onClick = { fullTerminal = !fullTerminal }) {
                    Text(if (fullTerminal) "Wrapped" else "Terminal")
                }
            }
        }
        ServerBanners(vm)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
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
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showUsage && quota != null) QuotaDetails(quota) { showUsage = false }
            vm.lastSent?.takeIf { it.first == paneId }?.let { (_, text) ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text,
                        color = Palette.OnBlue,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .background(Palette.Blue, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            if (fullTerminal) {
                SelectionContainer {
                    Text(
                        vm.paneText.ifBlank { "Loading…" },
                        color = Palette.TerminalInk,
                        fontFamily = FontFamily.Monospace,
                        fontSize = (liveSize * 0.88f).sp,
                        lineHeight = (liveSize * 0.88f * 1.35f).sp,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.Terminal, RoundedCornerShape(10.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Palette.Card,
                    border = BorderStroke(1.dp, Palette.Line),
                ) {
                    val text = vm.paneText
                    if (text.isBlank()) {
                        Text("Loading…", color = Palette.Muted, modifier = Modifier.padding(14.dp))
                    } else {
                        WrappedScreen(text, liveSize, Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    }
                }
            }
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

        Surface(color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (modifiersOn) {
                    val names = listOfNotNull("Ctrl".takeIf { ctrl != Hold.OFF }, "Alt".takeIf { alt != Hold.OFF }).joinToString("+")
                    val held = ctrl == Hold.LOCKED || alt == Hold.LOCKED
                    Text(
                        if (held) "$names is held: every key and letter you type goes with it. Tap it to let go."
                        else "$names is on for the next key. Type a letter or tap a key.",
                        color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModifierKey("Ctrl", ctrl) { ctrl = it }
                    ModifierKey("Alt", alt) { alt = it }
                    QUICK_KEYS.forEach { k -> KeyButton(k.label) { press(k.key) } }
                    KeyButton("Ctrl+C", danger = true) { confirmCtrlC = true }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { new ->
                            // With Ctrl or Alt on, a typed character becomes a key chord instead of text.
                            if (modifiersOn && new.length == message.length + 1 && new.startsWith(message)) {
                                keyName(new.last())?.let { press(it) }
                            } else {
                                message = new
                            }
                        },
                        placeholder = {
                            Text(if (modifiersOn) "Type a letter to send it with the modifier" else "Message ${pane?.agentName ?: "this tab"}…")
                        },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (message.isNotBlank() && pane != null) {
                                vm.sendMessage(pane, message); message = ""; typingAnswer = false
                            }
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(
                        onClick = {
                            if (message.isNotBlank() && pane != null) {
                                vm.sendMessage(pane, message); message = ""; typingAnswer = false
                            }
                        },
                        enabled = message.isNotBlank() && pane != null,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Blue),
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Palette.OnBlue) }
                }
            }
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

@Composable
private fun KeyButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (danger) Palette.OrangeEdge else Palette.KeyEdge),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (danger) Palette.OrangeSoft else Palette.Key,
            contentColor = if (danger) Palette.OrangeInk else Palette.Ink,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp),
        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

/** Tap: on for the next key. Long-press: held until tapped again. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModifierKey(label: String, state: Hold, onChange: (Hold) -> Unit) {
    val on = state != Hold.OFF
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (on) Palette.Blue else Palette.Key,
        border = BorderStroke(if (state == Hold.LOCKED) 2.dp else 1.dp, if (on) Palette.Blue else Palette.KeyEdge),
        modifier = Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 52.dp)
            .combinedClickable(
                onClick = { onChange(if (state == Hold.OFF) Hold.ONCE else Hold.OFF) },
                onLongClick = { onChange(if (state == Hold.LOCKED) Hold.OFF else Hold.LOCKED) },
            ),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                if (state == Hold.LOCKED) "$label ●" else label,
                color = if (on) Palette.OnBlue else Palette.Ink,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

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

// ---- The wrapped view ---------------------------------------------------------------------

private const val BOX_CHARS = "─│┌┐└┘├┤┬┴┼╭╮╰╯═║╔╗╚╝━┃▔▁█░▓▒■□"
private val RULE = Regex("""^[\s─━═▔▁\-_]{12,}$""")
private val ALIGNED = Regex("""\S {3,}\S""")
private val BULLET = Regex("""^(\s*)([-*•·▸▹›>]\s+|\d{1,3}[.)]\s+|[⏺●○◦✓✗✔✘❯⎿]\s*)""")

private sealed interface Block {
    data class Prose(val lines: List<String>) : Block
    data class Wide(val lines: List<String>) : Block
    data object Rule : Block
}

/**
 * A line that only reads correctly at full width: box drawing, a table, column-aligned output,
 * or a progress bar. These keep their layout and scroll sideways; everything else wraps.
 */
private fun isWide(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    if (t.count { it in BOX_CHARS } >= 2) return true
    if (t.startsWith("|") && t.count { it == '|' } >= 2) return true
    return ALIGNED.containsMatchIn(t) && t.length > 24
}

private fun blocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val prose = mutableListOf<String>()
    val wide = mutableListOf<String>()
    fun flushProse() { if (prose.isNotEmpty()) { out += Block.Prose(prose.toList()); prose.clear() } }
    fun flushWide() { if (wide.isNotEmpty()) { out += Block.Wide(wide.toList()); wide.clear() } }
    val lines = text.lines().map { it.trimEnd() }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim('\n').lines()
    for (l in lines) {
        when {
            RULE.matches(l) && l.isNotBlank() -> { flushProse(); flushWide(); out += Block.Rule }
            isWide(l) -> { flushProse(); wide += l }
            else -> { flushWide(); prose += l }
        }
    }
    flushProse(); flushWide()
    return out
}

/**
 * The pane as it reads best on a phone: ordinary lines wrap at word boundaries with their
 * continuation lines indented under the first word (so lists and indented code stay lined up),
 * while tables and boxes keep their exact layout in a strip that scrolls sideways.
 */
@Composable
private fun WrappedScreen(text: String, size: Float, modifier: Modifier) {
    val charWidth = size * 0.6f
    val list = remember(text) { blocks(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { b ->
                when (b) {
                    Block.Rule -> HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    is Block.Wide -> Text(
                        b.lines.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = (size * 0.9f).sp,
                        lineHeight = (size * 0.9f * 1.3f).sp,
                        softWrap = false,
                        color = Palette.Ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.Code)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                    is Block.Prose -> Text(
                        prose(b.lines, charWidth),
                        fontFamily = FontFamily.Monospace,
                        fontSize = size.sp,
                        lineHeight = (size * 1.45f).sp,
                        color = Palette.Ink,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }
}

private fun prose(lines: List<String>, charWidth: Float): AnnotatedString = buildAnnotatedString {
    lines.forEach { line ->
        val m = BULLET.find(line)
        val lead = line.length - line.trimStart().length
        val hang = (m?.value?.length ?: lead).coerceAtMost(24)
        withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = (hang * charWidth).sp))) {
            append(line.ifEmpty { " " })
        }
    }
}
