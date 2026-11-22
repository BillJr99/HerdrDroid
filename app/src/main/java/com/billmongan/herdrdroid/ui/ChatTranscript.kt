package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.border

import androidx.compose.material3.TextButton
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.transcript.ActivityRow
import com.billmongan.herdrdroid.transcript.DiffData
import com.billmongan.herdrdroid.transcript.ImageItem
import com.billmongan.herdrdroid.transcript.Item
import com.billmongan.herdrdroid.transcript.NoteItem
import com.billmongan.herdrdroid.transcript.OneRow
import com.billmongan.herdrdroid.transcript.QState
import com.billmongan.herdrdroid.transcript.QuestionItem
import com.billmongan.herdrdroid.transcript.QuestionLog
import com.billmongan.herdrdroid.transcript.Row as ChatRow
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.ThinkItem
import com.billmongan.herdrdroid.transcript.ToolItem
import com.billmongan.herdrdroid.transcript.ToolState
import com.billmongan.herdrdroid.transcript.Transcript
import com.billmongan.herdrdroid.transcript.UserItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the chat needs from its screen. */
class ChatActions(
    val onQuote: (String) -> Unit,
    val onStop: (() -> Unit)?,
    /** Sends a reply on to another agent (opens it with the reply in its message box). */
    val onForward: ((String) -> Unit)? = null,
    /** Reads the part of the log before what is shown, and adds it at the top. */
    val onLoadEarlier: (() -> Unit)? = null,
    val loadingEarlier: Boolean = false,
    /** Shown above pending messages while the agent works: they wait in its own queue. */
    val queuedNote: String? = null,
    /** Opens a message full screen, to read, copy, quote or share. */
    val onOpen: ((String) -> Unit)? = null,
    /** The row "Find in chat" is on: drawn with an outline. */
    val focusKey: String? = null,
)

/**
 * The structured conversation, drawn like the commercial chat apps: your messages as bubbles,
 * the agent's replies as formatted text, and its work (tool calls, reasoning) folded into one
 * expandable activity row between replies. New items fade in; the view follows the bottom while
 * you are there and offers a "↓" button when you have scrolled up.
 */
/**
 * Whether the chat follows the bottom, kept with the pane rather than inside the chat view, so that
 * switching to the terminal view and back, or leaving the app and returning, leaves the chat where
 * you put it (Bill, 2026-11-13: "don't always auto scroll [to] the bottom ... leave it where I put it").
 */
class ChatFollow {
    val stick = androidx.compose.runtime.mutableStateOf(true)
    val first = androidx.compose.runtime.mutableStateOf(true)
}

@Composable
fun TranscriptChat(
    rows: List<ChatRow>,
    working: Boolean,
    workingLabel: String,
    agentName: String,
    /** Messages you sent that the agent's log doesn't show yet, oldest first. */
    pending: List<Pair<String, Long>>,
    more: Boolean,
    size: Float,
    family: FontFamily,
    actions: ChatActions,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    state: LazyListState,
    modifier: Modifier = Modifier,
    follow: ChatFollow = remember(state) { ChatFollow() },
    itemTimes: Map<String, Long> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    val total = rows.size + 3
    // At the bottom when the list can't scroll further, or its last item ends within a few pixels
    // of the end. Going by the last visible item's index alone left the "N new" pill up at the very
    // bottom (2026-11-13): the trailing items (unsent messages, the working line) can be empty and
    // then don't count as visible.
    val slackPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    val atBottom by remember(state, slackPx) {
        derivedStateOf {
            val info = state.layoutInfo
            if (info.totalItemsCount == 0 || !state.canScrollForward) return@derivedStateOf true
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            // 1.2.8: only the true end counts. Before, seeing either of the last two items counted
            // as the bottom, so reading a tall question card (or the message above it) and then any
            // update snapped the chat down (Bill: "it snap scrolls when I try to scroll up").
            last.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + slackPx
        }
    }
    var unseen by remember { mutableStateOf(0) }
    var lastCount by remember { mutableStateOf(rows.size) }
    var first by follow.first
    val lastKey = rows.lastOrNull()?.key
    val lastSig = rows.lastOrNull()?.let { r -> if (r is ActivityRow) r.items.size.toString() + r.running else (r as OneRow).item.hashCode().toString() }
    // The chat sticks to the bottom: on opening, on coming back to the app, and while anything
    // below grows (the fresh read after the cached chat, images, a long reply), until the user drags
    // it up. Dragging back down to the bottom sticks it again. The app's own jumps never count.
    var stick by follow.stick
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect {
            when (it) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> stick = false
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> {
                    kotlinx.coroutines.delay(400) // let a fling settle
                    if (!state.canScrollForward) stick = true
                }
            }
        }
    }
    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { Triple(state.layoutInfo.totalItemsCount, state.canScrollForward, stick) }
            .collect { (count, more, s) ->
                if (s && count > 0 && more && !state.isScrollInProgress) {
                    state.scrollToItem(count - 1)
                    unseen = 0
                }
            }
    }
    // Back in front (from another app, or the screen turned on): to the bottom only if it was
    // following the bottom; a chat you had scrolled up stays where you left it (1.2.7).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, state) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && stick) {
                scope.launch { val n = state.layoutInfo.totalItemsCount; if (n > 0) state.scrollToItem(n - 1) }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // A message you just sent takes the chat to the bottom once; one that stays unsent does not
    // pull it down again on every update (1.2.8).
    var lastPending by remember(state) { mutableStateOf(pending.size) }
    LaunchedEffect(rows.size, lastKey, lastSig, pending.map { it.first }, working) {
        if (rows.isEmpty()) return@LaunchedEffect
        val pendingGrew = pending.size > lastPending
        lastPending = pending.size
        if (first) {
            first = false
            state.scrollToItem(total - 1)
        } else if (!state.isScrollInProgress && (stick || atBottom || pendingGrew)) {
            if (pendingGrew) stick = true
            state.animateScrollToItem(total - 1)
            unseen = 0
        } else if (rows.size > lastCount) {
            unseen += rows.size - lastCount
        }
        if (atBottom) unseen = 0
        lastCount = rows.size
    }
    // Whenever the list is at the bottom, nothing is unseen: after you scroll down, after new items
    // land while you are there, and after the chat reloads.
    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { atBottom }.collect { if (it) unseen = 0 }
    }

    Box(modifier) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "_head") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    header?.invoke()
                    if (more) {
                        val load = actions.onLoadEarlier
                        if (load != null) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = load, enabled = !actions.loadingEarlier) {
                                    Text(if (actions.loadingEarlier) "Loading earlier messages…" else "Load earlier messages")
                                }
                            }
                        } else Text(
                            "Showing the latest part of this conversation",
                            color = Palette.Muted, fontSize = 12.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    if (rows.isEmpty() && pending.isEmpty()) {
                        Text(
                            "No messages in this session yet.",
                            color = Palette.Muted, fontSize = 14.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
            }
            items(rows, key = { it.key }) { row ->
                val focused = row.key == actions.focusKey
                // Rows slide into place but never fade: a re-read that rebuilt a row made it blink.
                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).then(
                    if (focused) Modifier.border(BorderStroke(2.dp, Palette.Blue), RoundedCornerShape(12.dp)).padding(6.dp) else Modifier,
                )) {
                    when (row) {
                        is OneRow -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val item = row.item
                            ItemView(item, rows, size, family, actions, itemTimes)
                            if (item !is QuestionItem) ChatTimestamp(itemTimes[item.key], end = item is UserItem || (item is ImageItem && item.user))
                        }
                        is ActivityRow -> ActivityView(row, live = working && row.key == lastKey, size, itemTimes)
                    }
                }
            }
            // Grok's live card answers the pane's current question, not anything in its queue.
            // Keep the queue below the complete card in its own bounded, labelled section.
            val grokQueue = agentName == "Grok"
            val pendingMessages: @Composable () -> Unit = {
                AnimatedVisibility(pending.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (grokQueue || pending.size > 1 || working) {
                            Text(
                                if (grokQueue) "Queued in Grok · not yet in the conversation"
                                else if (working) actions.queuedNote ?: "${pending.size} waiting for ${agentName}"
                                else "${pending.size} on the way",
                                color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Column(
                            modifier = if (grokQueue) Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState()) else Modifier,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pending.forEach { (text, at) ->
                                Column {
                                    UserBubble(text, size, sending = true, onQuote = actions.onQuote)
                                    ChatTimestamp(at, end = true)
                                }
                            }
                        }
                    }
                }
            }
            if (!grokQueue) item(key = "_pending") { pendingMessages() }
            item(key = "_tail") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(working, enter = fadeIn(), exit = fadeOut()) {
                        WorkingLine(workingLabel, agentName, actions.onStop)
                    }
                    footer?.invoke()
                }
            }
            if (grokQueue) item(key = "_pending") { pendingMessages() }
        }
        AnimatedVisibility(
            !atBottom && rows.isNotEmpty(),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Palette.Card,
                border = BorderStroke(1.dp, Palette.Line),
                shadowElevation = 4.dp,
                modifier = Modifier.clickable { stick = true; scope.launch { state.animateScrollToItem(total - 1) } },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to the latest", tint = Palette.Blue, modifier = Modifier.size(18.dp))
                    Text(
                        if (unseen > 0) " $unseen new" else " Latest",
                        color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemView(item: Item, rows: List<ChatRow>, size: Float, family: FontFamily, actions: ChatActions, itemTimes: Map<String, Long>) {
    when (item) {
        is UserItem -> UserBubble(item.text, size, sending = item.sending, onQuote = actions.onQuote)
        is TextItem -> {
            val idx = rows.indexOfFirst { it.key == item.key }
            // The reply's actions sit under the last piece of the agent's text in a turn.
            val endsTurn = rows.drop(idx + 1).firstOrNull { it is OneRow && (it.item is UserItem || it.item is TextItem) }
                .let { it == null || (it as OneRow).item is UserItem }
            val files = remember(item.text) { mentionedFiles(item.text) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionContainer {
                    MarkdownText(item.text, size * 1.12f, family)
                }
                MentionedFiles(files)
                if (endsTurn) ReplyActions(item.text, actions.onQuote, actions.onOpen)
                else if (item.text.length > 700 && actions.onOpen != null) Pill("Expand") { actions.onOpen.invoke(item.text) }
            }
        }
        is ThinkItem -> ThinkView(item, size)
        is NoteItem -> Text(
            item.text,
            color = Palette.Muted, fontSize = 12.5.sp, fontFamily = Fonts.Mono,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        is ToolItem -> ToolView(item, size, live = false)
        is ImageItem -> AttachmentView(item)
        is QuestionItem -> if (item.btw) BtwView(item, size, family)
            else QuestionView(item, size, item.key.takeIf { it.startsWith("ask:") }?.removePrefix("ask:")?.let(itemTimes::get))
    }
}

/**
 * A /btw side question in the chat (2.1.4): a small "btw" label, the question as you asked it, and
 * the answer as ordinary Markdown, without the panel's rule, indent or key hints. Kept on the phone
 * with the pane's question history; never sent anywhere.
 */
@Composable
internal fun BtwView(q: QuestionItem, size: Float, family: FontFamily = FontFamily.Default, onClose: (() -> Unit)? = null) {
    val part = q.parts.firstOrNull() ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("btw", color = Palette.OnBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Palette.Blue, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            Text("Side question to ${q.agent ?: "the agent"}", color = Palette.Muted, fontSize = 12.5.sp)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(part.question.removePrefix("/btw ").trim(), color = Palette.Ink, fontSize = (size * 1.05f).sp,
                    fontWeight = FontWeight.SemiBold, lineHeight = (size * 1.05f * 1.4f).sp)
                part.answer?.takeIf { it.isNotBlank() }?.let { MarkdownText(it, size * 1.05f, family) }
                    ?: Text("No answer was shown.", color = Palette.Muted, fontSize = 12.5.sp)
            }
        }
        q.action?.let { Text(it, color = Palette.Muted, fontSize = 12.sp) }
        onClose?.let { close -> Pill("Close") { close() } }
        ChatTimestamp(q.at)
    }
}

/**
 * A question the agent asked, kept in the chat as plain text (1.3.0): who asked and how it ended,
 * the question (each step of a multi-question dialog in turn), the options offered and the answer.
 * Not a card: the orange card is only for answering a question that is open now.
 */
@Composable
fun QuestionView(q: QuestionItem, size: Float, loggedAt: Long? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        QuestionContent(q, size)
        ChatTimestamp(loggedAt ?: q.at)
    }
}

@Composable
internal fun ChatTimestamp(at: Long?, end: Boolean = false) {
    if (at == null || at <= 0L) return
    val context = LocalContext.current
    val date = java.util.Date(at)
    val label = android.text.format.DateFormat.getMediumDateFormat(context).format(date) + " · " +
        android.text.format.DateFormat.getTimeFormat(context).format(date)
    Text(label, color = Palette.Muted, fontSize = 11.sp, textAlign = if (end) TextAlign.End else TextAlign.Start,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun QuestionContent(q: QuestionItem, size: Float) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(QuestionLog.label(q), color = Palette.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            q.parts.forEach { p ->
                p.title?.takeIf { q.parts.size >= 2 }?.let { Text(it, color = Palette.Muted, fontSize = (size * 0.95f).sp, fontWeight = FontWeight.SemiBold) }
                if (p.question.isNotBlank()) Text(p.question, color = Palette.Ink, fontSize = (size * 1.08f).sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = (size * 1.08f * 1.4f).sp)
                p.details.forEach { Text(it, color = Palette.Ink, fontSize = (size * 1.0f).sp, lineHeight = (size * 1.4f).sp) }
                p.command?.let { Text(it, color = Palette.Ink, fontFamily = Fonts.Mono, fontSize = (size * 0.92f).sp) }
                if (p.options.isNotEmpty()) Column(Modifier.padding(start = 8.dp)) {
                    p.options.forEach { o ->
                        val picked = p.answer != null && o == p.answer
                        Text("- $o" + if (picked) "   (your answer)" else "", color = if (picked) Palette.Blue else Palette.Muted,
                            fontSize = (size * 0.98f).sp, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                p.answer?.takeIf { it !in p.options }?.let {
                    Text("Your answer: $it", color = Palette.Blue, fontSize = (size * 1.0f).sp, fontWeight = FontWeight.SemiBold)
                }
            }
            q.action?.let { Text("Then: $it", color = Palette.Blue, fontSize = (size * 0.98f).sp) }
            q.recorded?.let { Text("Recorded answer: $it", color = Palette.Muted, fontSize = (size * 0.95f).sp) }
            if (q.state == QState.ELSEWHERE && q.parts.none { it.answer != null }) Text("Answered outside the app; the answer wasn't seen here.", color = Palette.Muted, fontSize = 12.5.sp)
        }
    }
}

// ---- Your messages ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(text: String, size: Float, sending: Boolean = false, onQuote: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth().padding(start = 48.dp), contentAlignment = Alignment.CenterEnd) {
        Box {
            Text(
                text,
                color = Palette.Ink,
                fontFamily = Fonts.Sans,
                fontSize = (size * 1.12f).sp,
                lineHeight = (size * 1.12f * 1.45f).sp,
                modifier = Modifier
                    .alpha(if (sending) 0.55f else 1f)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(Palette.Bubble)
                    .combinedClickable(onClick = {}, onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true
                    })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            MessageMenu(menu, text, onDismiss = { menu = false }, onQuote = onQuote)
        }
    }
}

@Composable
private fun MessageMenu(open: Boolean, text: String, onDismiss: () -> Unit, onQuote: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Copy") }, onClick = { clipboard.setText(AnnotatedString(text)); onDismiss() })
        DropdownMenuItem(text = { Text("Share") }, onClick = { share(context, text); onDismiss() })
        DropdownMenuItem(text = { Text("Quote in reply") }, onClick = { onQuote(text); onDismiss() })
    }
}

private fun share(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** Copy, Share and Quote under a finished reply. */
@Composable
private fun ReplyActions(text: String, onQuote: (String) -> Unit, onOpen: ((String) -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { delay(1500); copied = false }
    // Short labels in one row that fits a phone's width, with no sideways scrolling.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (onOpen != null) Pill("Expand") { onOpen(text) }
        Pill(if (copied) "Copied" else "Copy") { clipboard.setText(AnnotatedString(text)); copied = true }
        Pill("Share") { share(context, text) }
        Pill("Quote") { onQuote(text) }
        Pill(if (Speaker.speakingKey == text.hashCode().toString()) "Stop" else "Read") { Speaker.speak(context, text.hashCode().toString(), text) }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

// ---- Reasoning -------------------------------------------------------------------------------

@Composable
private fun ThinkView(item: ThinkItem, size: Float) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val label = item.ms?.let { "Thought for ${seconds(it)}" } ?: "Thought"
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✻ ", color = Palette.Muted, fontSize = 13.sp)
            Text(label, color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(if (open) "  ▾" else "  ▸", color = Palette.Muted, fontSize = 12.sp)
        }
        if (open && item.text.isNotBlank()) {
            Row(Modifier.padding(start = 6.dp, top = 4.dp)) {
                Box(Modifier.width(2.dp).heightIn(min = 16.dp).background(Palette.Line))
                Text(
                    item.text.trim(),
                    color = Palette.Muted, fontStyle = FontStyle.Italic,
                    fontSize = (size * 0.98f).sp, lineHeight = (size * 1.5f).sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

private fun seconds(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

// ---- The agent's work ------------------------------------------------------------------------

private fun summary(row: ActivityRow): String {
    val tools = row.tools
    if (tools.isEmpty()) return "Thought"
    val byKind = tools.groupingBy { it.kind }.eachCount()
    fun n(k: String) = byKind[k] ?: 0
    val parts = buildList {
        if (n("shell") > 0) add(if (n("shell") == 1) "ran a command" else "ran ${n("shell")} commands")
        if (n("edit") > 0) add(if (n("edit") == 1) "edited a file" else "edited ${n("edit")} files")
        if (n("read") > 0) add(if (n("read") == 1) "read a file" else "read ${n("read")} files")
        if (n("search") > 0) add(if (n("search") == 1) "searched" else "searched ${n("search")} times")
        if (n("web") > 0) add(if (n("web") == 1) "looked something up" else "made ${n("web")} web lookups")
        if (n("agent") > 0) add(if (n("agent") == 1) "ran a subagent" else "ran ${n("agent")} subagents")
        if (n("plan") > 0) add("updated the plan")
        if (n("other") > 0) add(if (n("other") == 1) "used a tool" else "used ${n("other")} tools")
    }
    val s = parts.take(3).joinToString(", ") + if (parts.size > 3) ", and more" else ""
    return s.replaceFirstChar { it.uppercase() }
}

private fun icon(kind: String) = when (kind) {
    "shell" -> "›_"
    "edit" -> "✎"
    "read" -> "◫"
    "search" -> "⌕"
    "web" -> "◍"
    "agent" -> "◈"
    "plan" -> "☰"
    else -> "◆"
}

@Composable
private fun ActivityView(row: ActivityRow, live: Boolean, size: Float, itemTimes: Map<String, Long>) {
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    val running = live && row.running
    val current = row.tools.lastOrNull { it.state == ToolState.RUNNING }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Palette.Activity,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.Blue, modifier = Modifier.size(14.dp))
                } else {
                    Text(if (row.failed > 0) "!" else "✓", color = if (row.failed > 0) Palette.OrangeInk else Palette.Green, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        summary(row) + if (row.failed > 0) " · ${row.failed} failed" else "",
                        color = Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val sub = (if (running) current?.title else row.tools.lastOrNull()?.title)
                    if (sub != null) {
                        Text(
                            sub, color = Palette.Muted, fontSize = 12.5.sp, fontFamily = Fonts.Mono,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(if (open) "▾" else "▸", color = Palette.Muted, fontSize = 13.sp)
            }
            if (!open) itemTimes[row.items.first().key]?.let { at ->
                Box(Modifier.padding(start = 36.dp, end = 12.dp, bottom = 8.dp)) { ChatTimestamp(at) }
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.items.forEach { it2 ->
                        when (it2) {
                            is ToolItem -> ToolView(it2, size, live = live)
                            is ThinkItem -> Box(Modifier.padding(horizontal = 6.dp)) { ThinkView(it2, size) }
                            else -> {}
                        }
                        ChatTimestamp(itemTimes[it2.key])
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolView(t: ToolItem, size: Float, live: Boolean) {
    var open by rememberSaveable(t.key) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (open) Palette.Card else Color.Transparent)
            .animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(icon(t.kind), color = Palette.Muted, fontSize = 12.sp, fontFamily = Fonts.Mono, modifier = Modifier.width(22.dp))
            Text(
                t.title, color = Palette.Ink, fontSize = 13.sp, fontFamily = Fonts.Mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            when (t.state) {
                ToolState.RUNNING -> if (live) CircularProgressIndicator(strokeWidth = 1.5.dp, color = Palette.Blue, modifier = Modifier.size(12.dp))
                ToolState.ERROR -> Text("failed", color = Palette.OrangeInk, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                ToolState.OK -> {}
            }
        }
        if (open) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                t.path?.let { MentionedFiles(listOf(it)) }
                t.diff?.let { DiffCard(it, size) }
                t.patch?.let { CodeBlock(it, "diff", size) }
                t.input?.takeIf { it.isNotBlank() }?.let { Mono("Input", it, size) }
                t.out?.takeIf { it.isNotBlank() }?.let { Mono(if (t.state == ToolState.ERROR) "Error" else "Output", it, size) }
                if (t.out == null && t.state != ToolState.RUNNING) Text("No output", color = Palette.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Mono(label: String, text: String, size: Float) {
    val clipboard = LocalClipboardManager.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = Palette.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "Copy", color = Palette.Blue, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { clipboard.setText(AnnotatedString(text)) }.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        SelectionContainer {
            Text(
                text,
                fontFamily = Fonts.Mono, fontSize = (size * 0.8f).sp, lineHeight = (size * 0.8f * 1.45f).sp,
                color = Palette.Ink, softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Code, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
        }
    }
}

/** An edit as removed and added lines, with the file name on top. Long edits fold. */
@Composable
fun DiffCard(d: DiffData, size: Float) {
    var all by remember { mutableStateOf(false) }
    val old = if (d.old.isEmpty()) emptyList() else d.old.lines()
    val new = if (d.new.isEmpty()) emptyList() else d.new.lines()
    val lines = old.map { '-' to it } + new.map { '+' to it }
    val shown = if (all) lines else lines.take(40)
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.Code, border = BorderStroke(1.dp, Palette.Line)) {
        Column {
            Row(Modifier.fillMaxWidth().background(Palette.CodeHead).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(d.path?.substringAfterLast('/') ?: "edit", color = Palette.Ink, fontSize = 12.5.sp, fontFamily = Fonts.Mono, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (old.isNotEmpty()) Text("−${old.size} ", color = Palette.DiffDelInk, fontSize = 12.sp, fontFamily = Fonts.Mono)
                Text("+${new.size}", color = Palette.DiffAddInk, fontSize = 12.sp, fontFamily = Fonts.Mono)
            }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                shown.forEach { (sign, l) ->
                    Text(
                        "$sign $l",
                        fontFamily = Fonts.Mono, fontSize = (size * 0.8f).sp, lineHeight = (size * 0.8f * 1.4f).sp,
                        color = if (sign == '+') Palette.DiffAddInk else Palette.DiffDelInk, softWrap = false,
                        modifier = Modifier.background(if (sign == '+') Palette.DiffAdd else Palette.DiffDel).padding(horizontal = 10.dp, vertical = 1.dp).widthIn(min = 320.dp),
                    )
                }
            }
            if (lines.size > 40) {
                Text(
                    if (all) "Show less" else "Show all ${lines.size} lines",
                    color = Palette.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { all = !all }.padding(10.dp),
                )
            }
        }
    }
}

// ---- Working ---------------------------------------------------------------------------------

@Composable
private fun WorkingLine(label: String, agentName: String, onStop: (() -> Unit)?) {
    var since by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        since = System.currentTimeMillis()
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).alpha(a).background(Palette.Blue, CircleShape))
        Text(
            "  ${label.ifBlank { "$agentName is working" }} · ${seconds(now - since)}",
            color = Palette.Muted, fontSize = 13.5.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (onStop != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Palette.OrangeSoft,
                border = BorderStroke(1.dp, Palette.OrangeEdge),
                modifier = Modifier.clickable(onClick = onStop),
            ) {
                Text("Stop", color = Palette.OrangeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
    }
}

/** The plain text of a whole conversation, for "Copy all". */
fun conversationText(items: List<Item>): String = items.joinToString("\n\n") { i ->
    when (i) {
        is UserItem -> "You: " + i.text
        is TextItem -> i.text
        else -> "[" + Transcript.plain(i).lines().first() + "]"
    }
}
