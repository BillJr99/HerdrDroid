package com.billmongan.herdrdroid.ui

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.billmongan.herdrdroid.herdr.screenLinks
import com.billmongan.herdrdroid.herdr.openCodeBackgroundDrawer
import com.billmongan.herdrdroid.herdr.backgroundSupport
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
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
import com.billmongan.herdrdroid.herdr.canonicalAgent
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

private class CardAnswerDraft(
    val target: AppViewModel.CardTarget?, val prompt: Prompt, val choice: Choice?, val text: String = "",
) {
    fun withText(text: String) = CardAnswerDraft(target, prompt, choice, text)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentScreen(vm: AppViewModel, paneId: String, readScreen: suspend (String) -> Unit = vm::readPane) {
    val pane = vm.pane(paneId)
    // 2.2.7: the unsent text lives in the view model by server and pane, so it survives leaving
    // this screen and coming back (Settings, the server list, another pane, a brief app switch).
    val draftBox = remember(vm, vm.current?.id, paneId) { ComposerDraftBox(vm, paneId) }
    androidx.compose.runtime.LaunchedEffect(draftBox) { vm.takePrefill(paneId)?.let { vm.setComposerDraft(paneId, it) } }
    var normalMessage by draftBox
    val draftServer = vm.current?.id
    // Keep answers out of the ordinary (saveable) composer. A restored message must never
    // silently become an unbound answer or be sent as chat after losing its target.
    var answerDraft by remember(draftServer, paneId) { mutableStateOf<CardAnswerDraft?>(null) }
    val typingAnswer = answerDraft != null
    val message = answerDraft?.text ?: normalMessage
    fun editMessage(text: String) {
        val draft = answerDraft
        if (draft != null) answerDraft = draft.withText(text) else normalMessage = text
    }
    androidx.compose.runtime.SideEffect {
        if (draftServer != null) vm.noteVoiceComposerDraft(draftServer, paneId, message.isNotBlank())
    }
    var fullTerminal by rememberSaveable { mutableStateOf(false) }
    var confirmCtrlC by remember { mutableStateOf(false) }

    var ctrl by remember { mutableStateOf(Hold.OFF) }
    var alt by remember { mutableStateOf(Hold.OFF) }
    /** The slash command whose screen is showing, until you go back to the chat. */
    var commandScreen by rememberSaveable(paneId) { mutableStateOf<String?>(null) }
    LaunchedEffect(fullTerminal) { if (!fullTerminal) commandScreen = null }
    // 2.1.4: while the terminal shows a usage dialog the app opened, it stays open; going back to the
    // chat, to another pane or out of the app closes it, and the app checks that it closed.
    LaunchedEffect(paneId, commandScreen) {
        vm.usageViewShown(paneId, commandScreen?.let { com.billmongan.herdrdroid.herdr.opensUsageDialog(vm.pane(paneId)?.agent, it) } == true)
    }
    androidx.compose.runtime.DisposableEffect(paneId) { onDispose { vm.usageViewShown(paneId, false) } }
    var menu by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }
    // 2.2.7: Export pane screen's dialog (PaneScreenExportDialog.kt).
    var exportingScreen by remember(paneId) { mutableStateOf(false) }
    // 2.2.7: the pane reads kept on this phone (PaneReadPanel.kt).
    var showingPaneReads by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var hermesMenu by remember { mutableStateOf(false) }
    var closingSession by remember { mutableStateOf(false) }
    /** The agent's own model menu, read off its screen, while it is open. */
    var readingPicker by remember { mutableStateOf(false) }
    var pickerMenu by remember { mutableStateOf<Prompt?>(null) }
    /** 2.2.0: which picker is being read ("model" or "session"), and the mode, thinking and sessions dialogs. */
    var pickerWhat by remember { mutableStateOf("model") }
    var choosingMode by remember { mutableStateOf<String?>(null) }
    var choosingThinking by remember { mutableStateOf(false) }
    var choosingSession by remember { mutableStateOf(false) }
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
    // 2.2.7: until the pane is in the server's list its agent is unknown: the title bar says
    // "Loading…" (no "?" tile, no raw pane id), then after a short wait the pane's last known label.
    var identityWaitedOut by remember(vm.current?.id, paneId) { mutableStateOf(false) }
    val paneUnknown = pane == null
    LaunchedEffect(vm.current?.id, paneId, paneUnknown) {
        if (paneUnknown && !identityWaitedOut) {
            kotlinx.coroutines.delay(PANE_IDENTITY_WAIT_MS)
            identityWaitedOut = true
        }
    }
    val identity = paneIdentity(pane, paneId, identityWaitedOut, if (paneUnknown) vm.paneLabelGuess(paneId) else null)
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
            if (!liveNow && vm.inFront && vm.hasTranscriptReader(paneId)) {
                // Once the log is found, stream it (replies show within about a third of a
                // second); if streaming doesn't work here, poll every 1.5 seconds instead.
                if (vm.transcriptState == TranscriptMode.ON && streamFailures < 3) {
                    if (vm.followTranscript(paneId)) { streamFailures = 0; continue } else streamFailures++
                }
                // 2.2.2: and at once while the view waits for this visit's answer (an agent that started mid-visit).
                if (vm.transcriptState != TranscriptMode.OFF || tick % 10 == 0 || !vm.screenViewReady(paneId)) vm.readTranscript(paneId)
            }
            tick++
            kotlinx.coroutines.delay(1_500)
        }
    }
    // Questions can arrive in a working pane before its status flips to Needs you. Use the same
    // ordered screen read (and its independent SSH channel), not a second probe or the chat log.
    fun canReadScreen() = vm.inFront && !vm.locked && vm.link == Link.CONNECTED &&
        (vm.screen as? Screen.Agent)?.paneId == paneId
    val screenReadable = canReadScreen()
    LaunchedEffect(vm, paneId, structured, screenReadable, pane?.status == Status.NEEDS_YOU) {
        while (screenReadable && canReadScreen()) {
            readScreen(paneId)
            kotlinx.coroutines.delay(when {
                vm.pane(paneId)?.status == Status.NEEDS_YOU -> 750L
                !liveNow && vm.pane(paneId)?.status == Status.WORKING && vm.paneReadProblem == null -> 500L
                structured -> 2_000L
                else -> 1_500L
            })
        }
    }
    // The list every 4 s, every 2 s while the drawer and its badges are out (2.1.4); herdr's own
    // wait reads it at once on a change in between.
    LaunchedEffect(paneId, vm.drawerOpen) {
        while (true) {
            kotlinx.coroutines.delay(com.billmongan.herdrdroid.herdr.NeedsYouCadence.listGapMs(vm.drawerOpen))
            if (vm.inFront) vm.refreshPanes()
        }
    }
    // What runs under this agent (subagents, background tasks, processes), every 5 seconds.
    LaunchedEffect(paneId) {
        while (true) {
            if (vm.inFront) vm.refreshWork(paneId)
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
    val quote: (String) -> Unit = { q -> editMessage(q.lines().joinToString("\n") { "> $it" } + "\n\n") }

    // As the list shows it: Done becomes Idle once you have opened it here.
    val status = pane?.let {
        val reported = vm.shownStatus(it)
        if (reported == Status.NEEDS_YOU && vm.openCodeComposerIsCurrent(it)) Status.IDLE else reported
    } ?: Status.UNKNOWN
    // Only this agent's screen: never one read from the agent you just left.
    val liveScreenText = if (vm.paneTextPane == paneId) vm.paneText else ""
    val openingPreview = vm.prefetchedPaneText(paneId)
    val screenText = openingPreview ?: liveScreenText
    val backgroundDrawer = vm.paneReadProblem == null && openCodeBackgroundDrawer(pane?.agent, screenText)
    val shownTarget = vm.paneTextTarget?.takeIf { vm.paneTextPane == paneId && vm.paneReadProblem == null }
    // Never an empty card (1.4.2): one with no options always shows the screen region or a note.
    // While the latest read failed, no card is built from the last screen: its buttons would answer
    // whatever the pane shows now (1.4.2, from review). If the agent waits, the card says the screen
    // couldn't be read and offers the keys and Show terminal.
    val usageOverlay = vm.usageOverlayFor(paneId)
    val dismissingUsage = vm.usageDismissInFlight(paneId)
    val usageBlocksInput = usageOverlay != null || dismissingUsage
    // 2.2.1: an informational panel read off the last screen (status, session info, help...), for its card.
    val shownPanel = remember(screenText, pane?.agent) {
        if (vm.paneReadProblem == null) com.billmongan.herdrdroid.herdr.infoPanelOn(pane?.agent, screenText) else null
    }
    val waitingForHermes = vm.waitingForHermesComposer(paneId)
    val parsedPrompt: Prompt? = if (typingAnswer || usageOverlay != null) null
        else if (vm.paneReadProblem != null) (if (status == Status.NEEDS_YOU) Prompt("This agent is waiting for you.", emptyList()).forCard() else null)
        // 2.2.2: a question card shows once the reads confirmed it (a complete dialog, read the same
        // twice), parsed exactly as the read path parses it (pi's live question included); until then a
        // neutral card with no options or keys, never chat text as the question.
        else vm.questionCardFor(paneId, vm.cardParse(paneId, screenText)
            ?.takeIf { !it.codexQuestion || shownTarget != null }, pane?.agentName ?: "The agent")
    // An agent's error on screen (1.4.2): shown as a banner when there is no question.
    // Not from the last screen while the latest read failed (1.4.2, from review).
    // 2.2.0: as its own card, with OpenCode's failed turn read whole; acknowledged ones are hidden.
    val errorNotice = remember(screenText, pane?.agent) {
        pane?.let { com.billmongan.herdrdroid.herdr.agentErrorNotice(screenText, it.agent, it.agentName) }
    }.takeIf { parsedPrompt == null && vm.paneReadProblem == null }
    val agentError: String? = errorNotice?.summary
    val shownError = errorNotice?.takeUnless { pane != null && vm.errorAcknowledged(vm.current?.id, pane, it) }
    // 2.1.4 (Bill: "it showed the orange ... waiting on you icon but didn't actually render the
    // question in chat"): while the pane shows as needing you, a card is always there, the parsed
    // one or one that says the question couldn't be read, with Show terminal.
    val prompt: Prompt? = com.billmongan.herdrdroid.herdr.withNeedsYouCard(status, parsedPrompt,
        suppressed = typingAnswer || usageBlocksInput || backgroundDrawer || agentError != null, agent = pane?.agentName ?: "The agent")
    // The question a parser read; the unread-question card leaves the chat's open questions in place.
    val questionCard: Prompt? = prompt?.takeUnless { it.unreadQuestion || it.readingQuestion }
    fun send() {
        if (usageBlocksInput || backgroundDrawer) return
        val draft = answerDraft
        // 2.2.0: cross-server proposals are approved with Yes/No on the phone, never by typing here.
        if (draft != null) {
            val text = draft.text.trim()
            if (text.isNotEmpty() && !vm.answerInFlight(paneId)) {
                vm.answerTyped(draft.target, draft.prompt, draft.choice, text) {
                    if (answerDraft === draft) answerDraft = null
                }
            }
            return
        }
        if (normalMessage.isNotBlank() && pane != null) {
            val text = normalMessage.trim()
            // A question or approval is open: typing into the pane would land in the dialog (Oh My Pi's
            // "type to deny and steer", Claude's own answer field), so the message waits in the app's
            // queue and goes once the question is answered or gone; the note says so.
            if (waitingForHermes || prompt != null || openingPreview != null || vm.hasPendingQuestion(pane)) {
                // 2.2.8 (Codex's review 1): bound to the pane's occupant now; another one never gets it.
                vm.enqueue(paneId, text, behindQuestion = true)
                vm.clearComposerDraft(paneId)
                vm.flashNote(QUEUED_FOR_QUESTION)
                return
            }
            // 2.2.7: out of the box now; its draft ends once delivered, and comes back if it never was.
            vm.sendFromComposer(pane, text)
            // A slash command answers on the screen (a menu, a sign-in code), not in the agent's log,
            // so show the screen and the keys to move through it.
            // 2.1.4: not for /btw, whose answer the app reads into the chat and then closes.
            // 2.2.7: a slash command only ("/model"), never a path such as an attachment's ("/home/…").
            if (com.billmongan.herdrdroid.herdr.isSlashCommand(text) && !live && com.billmongan.herdrdroid.herdr.btwQuestion(pane.agent, text) == null) { fullTerminal = true; commandScreen = text.substringBefore(' ') }
        }
    }
    // The terminal (and screen) view follows new output only while you are at the bottom; once you
    // scroll up it stays where you put it, through updates and view switches (1.2.7; before, every
    // update jumped to the bottom). "Latest" below brings it back.
    val scroll = androidx.compose.runtime.key(paneId) { rememberScrollState() }
    val termScope = androidx.compose.runtime.rememberCoroutineScope()
    var termFollow by androidx.compose.runtime.key(paneId) { remember { mutableStateOf(true) } }
    val chatFollow = androidx.compose.runtime.key(paneId) { remember { ChatFollow() } }
    // Following stops the moment your finger starts dragging (not when it lifts, which let new
    // output pull the view down mid-drag) and resumes only if you let go at the bottom (1.2.8).
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect {
            when (it) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> termFollow = false
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> {
                    kotlinx.coroutines.delay(400) // let a fling settle
                    termFollow = scroll.value >= scroll.maxValue - 24
                }
            }
        }
    }
    LaunchedEffect(screenText, structured, termFollow) {
        if (!structured && termFollow && !scroll.isScrollInProgress) scroll.scrollTo(scroll.maxValue)
    }
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
    // 1.2.8 (Bill: "show the question and text in the chat area ... so I can scroll to read it,
    // and just the answer buttons in the card"): the question, and what the agent said before it,
    // sit in the chat or terminal as the agent's message; the orange card below holds the answers.
    val promptCard: @Composable () -> Unit = {
        prompt?.let { p ->
          // In the terminal view the card shrinks to one line with the way back (1.4.8, Bill: "the show
          // terminal button doesn't actually do anything"), so the terminal itself is what you see.
          CardOrTerminalBar(showingTerminal = fullTerminal, agent = pane?.agentName ?: "The agent", onBackToQuestion = { fullTerminal = false }) {
            // The transcript already shows prose and attachments. The parsed question has no
            // transcript tool-call ID, so a running tool's plan or diff may belong to another turn.
            // The screen the card was read from, for its layout only (2.1.1); never while a read failed.
            // 2.2.2: none for the card that is still reading its question, so no screen text shows in it.
            val cardScreen = screenText.takeIf { vm.paneReadProblem == null && !p.readingQuestion }
            // 2.2.0: a question pill moves Claude's dialog to that question (QuestionPills), never answers.
            PromptQuestion(p, agent = pane?.agentName ?: "The agent", screen = cardScreen,
                onStep = if (shownTarget != null && openingPreview == null &&
                    com.billmongan.herdrdroid.herdr.QuestionPills.tappable(pane?.agent, p)) { i, t ->
                    if (!vm.answerInFlight(paneId)) vm.moveToQuestion(shownTarget, p, i, t.title)
                } else null)
            Spacer(Modifier.height(10.dp))
            PromptCard(
                p,
                agent = pane?.agentName ?: "The agent",
                optionNotes = remember(cardScreen, p) { CardFormat.optionNotes(cardScreen, p.choices) },
                sending = vm.answerInFlight(paneId),
                readOnlyPreview = openingPreview != null ||
                    ((com.billmongan.herdrdroid.herdr.isOpenCode(pane?.agent) || p.hermesClarify) && shownTarget == null && !p.terminalReview),
                onChoice = { c -> vm.answerChoice(paneId, p, c, target = shownTarget) },
                onKey = { k ->
                    val label = "Pressed " + when (k) { "esc" -> "Esc"; "tab" -> "Tab"; "shift+tab" -> "Shift+Tab"; "ctrl+c" -> "Ctrl+C"; else -> k }
                    vm.answerCardKey(shownTarget, p, k, label)
                },
                onTypeAnswer = { answerDraft = CardAnswerDraft(shownTarget, p, null) },
                onTypedChoice = { c -> answerDraft = CardAnswerDraft(shownTarget, p, c) },
                // The terminal is always one tap away from a card, on every agent (1.4.2).
                onShowTerminal = { hermesLive = false; fullTerminal = true; termFollow = true },
            )
          }
        } ?: if (backgroundDrawer && !fullTerminal) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("OpenCode's background drawer is open. Close it in Terminal to resume chat.", color = Palette.Muted)
                TextButton(onClick = { hermesLive = false; fullTerminal = true; termFollow = true }) { Text("Show terminal") }
            }
        } else pane?.let { pn -> shownError?.let { notice ->
            // An agent's error (1.4.2; its own card since 2.2.0): never a question card, no answer keys.
            AgentErrorCardView(
                title = notice.title, summary = notice.summary, message = notice.message,
                onAcknowledge = { vm.acknowledgeError(pn, notice) },
                onRetry = vm.retryTextFor(pn, notice)?.let { { vm.retryAfterError(pn, notice) } },
                onSwitchModel = if (notice.switchModel && status != Status.WORKING) ({ choosingModel = true }) else null,
                onShowTerminal = if (!fullTerminal) ({ hermesLive = false; fullTerminal = true; termFollow = true }) else null,
            )
        } }
        // 2.1.0: a draft typed at the computer, with Send draft and Clear draft. Only while no
        // question is open, from this pane's own latest read.
        vm.paneDraft?.takeIf { prompt == null && !fullTerminal && !backgroundDrawer && usageOverlay == null &&
            vm.paneReadProblem == null && vm.paneTextPane == paneId && it.target.pane.id == paneId }?.let { d ->
            // Claude holds a question back while a draft is in its input box (2.1.1): say so here.
            if (pane?.agent == "claude" && com.billmongan.herdrdroid.herdr.claudeQuestionBehindDraft(screenText))
                Text("Claude has a question waiting behind this draft. Send or clear the draft to see it.",
                    color = Palette.OrangeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            PaneDraftCard(
                agent = pane?.agentName ?: "The agent",
                draft = d,
                text = d.text,
                canAct = d.actionable,
                canClear = d.clearable,
                busy = vm.paneDraftBusy(paneId),
                sendNote = d.sendNote,
                onSend = { shown -> vm.sendPaneDraft(shown) },
                // The draft captured when Clear draft was tapped, not whatever shows at confirm.
                onClear = { captured -> vm.clearPaneDraft(captured) },
                onShowTerminal = { hermesLive = false; fullTerminal = true; termFollow = true },
            )
        }
    }

    // 2.2.7: Send now on a queued message to a working Claude Code with no background hint: the
    // 2.2.2 Force Send question. Only Send anyway sends; Cancel or a tap outside keeps it queued.
    vm.sendNowConfirm?.takeIf { it.paneId == paneId && it.serverId == vm.current?.id }?.let {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.cancelSendNow() },
            modifier = Modifier.semantics { contentDescription = "Force send queued message" },
            title = { Text("Force send?") },
            text = { Text(forceDialogText(pane?.agentName ?: "Claude", vm.current?.name ?: "this server"), color = Palette.Ink) },
            confirmButton = { TextButton(onClick = { vm.confirmSendNow() }) { Text("Send anyway") } },
            dismissButton = { TextButton(onClick = { vm.cancelSendNow() }) { Text("Cancel") } },
        )
    }

    // 2.2.7 (pre-review L8): an attachment's path goes into this pane's message box as it is now,
    // once the computer has the whole file, even after a Retry from another screen.
    vm.attachedPath?.takeIf { it.serverId == vm.current?.id && it.paneId == paneId }?.let { a ->
        LaunchedEffect(a) {
            editMessage(com.billmongan.herdrdroid.herdr.withAttachment(message, a.path))
            vm.attachedPathTaken(a)
        }
    }
    // 2.2.7 (pre-review L3): a queued message that may already have reached the agent is sent again only after this.
    vm.resendConfirm?.takeIf { it.paneId == paneId && it.serverId == vm.current?.id }?.let {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.cancelResend() },
            modifier = Modifier.semantics { contentDescription = "Send the message again" },
            title = { Text("Send it again?") },
            text = { Text("This message may already have reached ${pane?.agentName ?: "the agent"}. Check its chat first; sending again could give it twice.", color = Palette.Ink) },
            confirmButton = { TextButton(onClick = { vm.confirmResend() }) { Text("Send again") } },
            dismissButton = { TextButton(onClick = { vm.cancelResend() }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding().imePadding()) {
        // ---- Header: the agent, what it's doing, and its model -----------------------------
        // Tap the agent's name to switch to another agent on this server (no swipe: a sideways
        // drag on the header switched agents by accident).
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = if (keyboardUp) 0.dp else 6.dp, bottom = if (keyboardUp) 0.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The drawer holds every agent on every server (or swipe in from the left edge).
            IconButton(onClick = { vm.drawerOpen = true }) {
                Icon(Icons.Filled.Menu, contentDescription = "All agents", tint = Palette.Ink)
            }
            if (identity.known) AgentTile(identity.initial.orEmpty(), status, size = 34)
            else Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                if (identity.loading) androidx.compose.material3.CircularProgressIndicator(
                    Modifier.size(20.dp).semantics { contentDescription = "Loading the pane" },
                    color = Palette.Muted, strokeWidth = 2.dp)
            }
            // 2.1.4: three lines (Bill, 2026-11-21): the pane's name, the server in a smaller style,
            // and the model in use smaller still (left off when it is unknown). Tap the name to switch
            // agents (or, on Hermes, for the Hermes menu); tap the model to choose another one.
            val headerModelText = com.billmongan.herdrdroid.herdr.headerModel(
                pane?.agent, vm.modelLine(paneId),
                screen = if (vm.paneTextPane == paneId) vm.paneText else "",
                terminalTitle = pane?.title.orEmpty(),
            )
            PaneTitleLines(
                name = if (isHermes) (if (live) "Hermes · live chat" else "Hermes · this pane") + "  ▾"
                else identity.title,
                server = vm.current?.name,
                model = if (identity.known) headerModelText else null,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                status = if (vm.link == Link.CONNECTED && identity.known) look(status, pane?.backgroundBusy == true).label else null,
                statusInk = look(status).ink,
                compact = keyboardUp,
                onName = { if (isHermes) { hermesMenu = true; vm.hermesChat()?.refreshSessions() } else vm.drawerOpen = true },
                onModel = if (pane?.agent != null) ({ choosingModel = true }) else null,
                nameAnchor = {
                    // On Hermes the name opens the Hermes menu: this pane or live chat, your Telegram
                    // conversation, all conversations, a new chat, model and usage.
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
                        DropdownMenuItem(text = { Text("All agents…") }, onClick = { hermesMenu = false; vm.drawerOpen = true })
                    }
                },
                statusLine = if (vm.link == Link.CONNECTED) null else ({ LinkLabel(vm, "") }),
            )
            // 2.2.7: the agent is still unknown after the wait: the terminal is the honest view.
            if (identity.kind == PaneIdentity.Kind.FALLBACK && !fullTerminal)
                TextButton(onClick = { hermesLive = false; fullTerminal = true; termFollow = true }) { Text("Show terminal") }
            // Shells and subagents still running under the agent, as one small pill in the header
            // (a row of its own appeared and vanished as they started and finished, moving the chat).
            // 2.1.5: the pill is the only way into the view; the "Running underneath" menu entry is gone
            // (Bill, 2026-11-22). With nothing running it stays as a muted "N done" while the session has
            // finished work to read, and is hidden when there is nothing at all.
            vm.work[paneId]?.let { w ->
                runningPill(w, pane?.agent, vm.endedProcs[paneId].orEmpty().size)?.let { RunningPillChip(vm, paneId, it) }
            }
            // Everything else lives in one menu, so the header stays readable on a phone.
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Palette.Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    // 1.2.7: the per-agent "Usage / Hide usage" toggle is gone. It drew its panel at the top
                    // of the chat, out of sight when the chat sits at the bottom, so it seemed to do nothing
                    // (Bill, 2026-11-13). Every agent's usage is on the screen below.
                    DropdownMenuItem(text = { Text("Usage history, all agents") }, onClick = { menu = false; vm.screen = Screen.Usage })
                    if (pane != null) DropdownMenuItem(text = { Text("Files") }, onClick = { menu = false; vm.openFiles(pane.cwd.ifBlank { "~" }) })
                    if (pane?.agent != null && vm.hasTranscriptReader(paneId)) {
                        DropdownMenuItem(text = { Text("History") }, onClick = { menu = false; vm.screen = Screen.History(paneId) })
                    }
                    if (pane?.agent != null) DropdownMenuItem(text = { Text("Model…") }, onClick = { menu = false; choosingModel = true })
                    // 2.2.0: the pane's sessions (switch, or start a new one), and its thinking level.
                    if (!live && com.billmongan.herdrdroid.herdr.SessionControls.forAgent(pane?.agent) != null)
                        DropdownMenuItem(text = { Text("Sessions…") }, onClick = { menu = false; choosingSession = true })
                    if (!live && com.billmongan.herdrdroid.herdr.PaneControls.thinking(pane?.agent) != null)
                        DropdownMenuItem(text = { Text("Thinking…") }, onClick = { menu = false; choosingThinking = true })
                    if (structured && vm.transcriptItems.isNotEmpty()) DropdownMenuItem(text = { Text("Find in chat") }, onClick = { menu = false; finding = true })
                    // 1.4.0: one pane's last screens, the parser's decision and the chat cursor, with
                    // secrets taken out, for a bug report. Only to this phone's clipboard or share sheet.
                    DropdownMenuItem(text = { Text("Copy debug bundle") }, onClick = {
                        menu = false
                        val text = vm.debugBundle(paneId)
                        runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("HerdrDroid debug bundle", text))
                        }
                        vm.notice = "Debug bundle copied (${text.length} characters, secrets redacted)"
                    })
                    DropdownMenuItem(text = { Text("Share debug bundle") }, onClick = {
                        menu = false
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, vm.debugBundle(paneId))
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share debug bundle")) }
                    })
                    DropdownMenuItem(text = { Text("Export pane screen") }, onClick = { menu = false; exportingScreen = true })
                    DropdownMenuItem(text = { Text("Pane reads…") }, onClick = { menu = false; showingPaneReads = true })
                    // The agent's own key for moving a running command to the background (Ctrl+B),
                    // offered only for agents where that is what the key does.
                    if (com.billmongan.herdrdroid.herdr.Backgrounding.supported(pane?.agent)) DropdownMenuItem(
                        text = { Text("Background task (Ctrl+B)") },
                        onClick = { menu = false; vm.sendKeys(paneId, com.billmongan.herdrdroid.herdr.Backgrounding.KEY) },
                    )
                    if (pane?.agent != null) DropdownMenuItem(text = { Text("Close session…", color = Palette.OrangeInk) }, onClick = { menu = false; closingSession = true })
                    if (structured && vm.transcriptItems.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Export as Markdown") }, onClick = {
                            menu = false
                            vm.exportMarkdown(context, vm.transcriptTitle ?: "${pane?.agentName ?: "Agent"} in ${pane?.folder ?: ""}", pane?.agentName ?: "Agent",
                                com.billmongan.herdrdroid.transcript.QuestionLog.withQuestions(vm.transcriptItems, vm.questionsFor(paneId), vm.transcriptTimes, vm.hostOffsetMs(), vm.transcriptSeen))
                        })
                    }
                    // Kept out of the header so the agent's name has the room.
                    if (!live) DropdownMenuItem(text = { Text(if (fullTerminal) "Chat view" else "Terminal view") }, onClick = { menu = false; fullTerminal = !fullTerminal })
                    // The permission mode (plan, accept edits, auto). 2.2.0: where the agent has a verified
                    // way to switch (PaneControls.modes), a menu of named modes, each switch confirmed from
                    // the footer; elsewhere the mode is only shown.
                    // 2.2.2: every mode menu the agent has (Copilot also has Approvals), shown where its
                    // current setting reads off the screen or every switch sets it outright (Codex).
                    val menus = com.billmongan.herdrdroid.herdr.PaneControls.modeMenus(pane?.agent)
                    if (!live && menus.isEmpty()) com.billmongan.herdrdroid.herdr.agentMode(pane?.agent, liveScreenText)?.let { mode ->
                        DropdownMenuItem(
                            text = { Text("Mode: " + if (mode == "bypass") "bypass (approves every tool)" else mode) },
                            enabled = false, onClick = {},
                        )
                    }
                    if (!live) menus.forEach { spec ->
                        val mode = spec.read(liveScreenText)
                        if (mode != null || spec.absolute) {
                            // 2.2.2 (from review): a mode that approves every tool keeps its warning on the item.
                            val option = spec.options.firstOrNull { it.name == mode }
                            val label = when {
                                option == null -> if (mode == "bypass") "bypass (approves every tool)" else mode
                                option.approvesAll -> option.label
                                else -> option.label.substringBefore(" (")
                            }
                            DropdownMenuItem(
                                text = { Text(spec.title + (label?.let { ": $it" } ?: "") + "…") },
                                onClick = { menu = false; choosingMode = spec.title },
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.Line)
        ServerBanners(vm)
        // A held send's problem shows only in its own pane's chat, on its own server (2.1.2).
        vm.sendNoticeFor(paneId)?.let { ErrorBanner(it) { vm.dismissSendNotice(paneId) } }
        // 2.1.5: cross-server cards from frames the agent rendered in this chat (2.2.0: kept to a
        // share of the window, so the message box below always fits).
        if (!live) CrossServerPanel(vm, paneId)
        if (!live) PaneReadPanel(vm, paneId)
        // 2.2.0: herdr's session record is behind the terminal; the chat follows the terminal anyway.
        if (!live && vm.herdrOutOfDate(paneId)) HerdrOutOfDate()
        vm.paneControlBusy(paneId)?.let { Text(it, color = Palette.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        if (openingPreview != null) {
            Text("Checking the live pane…", color = Palette.Muted, fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (usageBlocksInput || shownPanel != null) {
            UsageOverlayBanner(dismissingUsage, btw = usageOverlay?.overlay?.visible?.let { com.billmongan.herdrdroid.herdr.claudeBtwOverlay(it) } != null,
                panel = usageOverlay?.let { com.billmongan.herdrdroid.herdr.infoPanelOn(it.agent, it.overlay.visible) } ?: shownPanel,
                agentName = pane?.agentName ?: "The agent", problem = vm.panelCloseProblem(paneId), canClose = usageOverlay != null,
                onShowTerminal = if (!fullTerminal) ({ fullTerminal = true; termFollow = true }) else null) {
                usageOverlay?.let(vm::dismissUsage)
            }
        }
        if (waitingForHermes && !fullTerminal && !live) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Waiting for Hermes to return to its composer after Usage.", Modifier.weight(1f), color = Palette.Muted)
                TextButton(onClick = { fullTerminal = true; termFollow = true }) { Text("Show terminal") }
            }
        }
        // The chat with its question history in place (1.3.0). A question still open is left out
        // while its card is showing, so it isn't on screen twice.
        val questionsNow = vm.questionsFor(paneId)
        // Claude's sessions-list card (2.1.1) is not the question itself: the question stays in the chat.
        val cardUp = questionCard != null && !questionCard.claudeBackground
        // Placed again when item times or the clock offset change, not only the items (1.4.3, from review).
        val chatItems = remember(vm.transcriptItems, questionsNow, cardUp, vm.transcriptTimes, vm.transcriptSeen, vm.hostOffsets) {
            com.billmongan.herdrdroid.transcript.QuestionLog.withQuestions(vm.transcriptItems, questionsNow, vm.transcriptTimes, vm.hostOffsetMs(), vm.transcriptSeen)
                .filter { !cardUp || (it as? com.billmongan.herdrdroid.transcript.QuestionItem)?.state != com.billmongan.herdrdroid.transcript.QState.OPEN }
        }
        val rowsNow = remember(chatItems) { rowsOf(chatItems) }
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

        // Every message shows a time (2.1.4, Bill: "make sure all panes do in chat"): its own, Grok's on
        // its screen, or when the app first saw it.
        val chatTimes = remember(vm.transcriptLogTimes, vm.transcriptShown) { vm.chatTimes }
        val chatAgentName = canonicalAgent(pane?.agent.orEmpty())
        LaunchedEffect(screenText, vm.transcriptItems, structured) {
            if (structured && chatAgentName == "grok" && screenText.isNotBlank()) vm.noteScreenClocks(paneId, screenText)
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
                itemTimes = chatTimes,
                more = vm.transcriptMore,
                size = liveSize,
                family = family,
                actions = ChatActions(
                    onQuote = quote, onStop = pane?.let { p -> { vm.stopPane(p) } },
                    onLoadEarlier = if (vm.canLoadEarlier) ({ vm.loadEarlier(paneId) }) else null, loadingEarlier = vm.loadingEarlier,
                    queuedNote = "Queued in ${pane?.agentName ?: "the agent"} · it reads these when its current step ends",
                    onOpen = { t -> opened = t },
                    focusKey = matches.getOrNull(findAt)?.second,
                    // 2.2.8: long-press a greyed message for Send immediately (Ctrl+B until it goes).
                    onSendPendingNow = { t, at -> vm.sendPendingImmediately(t, at) },
                    sendPendingNowOff = vm.sendImmediatelyBlock(paneId),
                ),
                header = {},
                footer = promptCard,
                state = listState,
                modifier = Modifier.fillMaxSize().then(pinchToZoom),
                follow = chatFollow,
            ) } }
                if (vm.paneUpdating(paneId)) UpdatingChip(Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
            }
        } else Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(pinchToZoom)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 2.2.0: until this visit's reads settle, the pane's last settled screen (display only).
                val text = screenText.ifBlank { vm.heldScreenText(paneId).orEmpty() }
                if (fullTerminal) {
                    // The terminal view (1.4.8: before, a pane with no good read yet showed the same note as
                    // the card, so Show terminal looked like it did nothing).
                    TerminalView(text, vm.paneReadProblem, liveSize)
                } else if (identity.loading && vm.paneReadProblem == null) {
                    // 2.2.7: the agent is not known yet, so neither its chat nor a guessed screen view
                    // shows; the real view draws once the pane list says which agent this is.
                    Text(LOADING_TITLE, color = Palette.Muted, fontSize = 15.sp)
                } else if (!vm.screenViewReady(paneId) && vm.paneReadProblem == null) {
                    // 2.2.0: an agent with a chat log shows that log; the terminal parsed into a chat showed
                    // here for a moment first, then jumped to the chat. The card below still shows at once.
                    Text("Loading the chat…", color = Palette.Muted, fontSize = 15.sp)
                } else if (text.isBlank()) {
                    // Say why the screen isn't there, rather than "Loading…" for ever (1.4.2).
                    Text(vm.paneReadProblem?.let { "The app couldn't read this pane's screen yet ($it). It keeps trying." } ?: "Loading…",
                        color = Palette.Muted, fontSize = 15.sp)
                } else {
                    // Agents without a readable log (Antigravity, Kiro) keep their question history here (1.3.0).
                    val chatAgent = canonicalAgent(pane?.agent.orEmpty())
                    val antigravity = chatAgent == "agy"
                    val grok = chatAgent == "grok"
                    // The pane's history (1.4.3, Bill): each earlier screen, then the question asked under
                    // it, oldest first, so anything newer is below a question; the live screen comes last.
                    com.billmongan.herdrdroid.transcript.QuestionLog.screenHistory(
                        vm.questionsFor(paneId).filter { questionCard == null || it.state != com.billmongan.herdrdroid.transcript.QState.OPEN },
                    ).forEach { entry ->
                        when (entry) {
                            is com.billmongan.herdrdroid.transcript.ScreenSnapshot -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Earlier screen", color = Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                ChatTimestamp(entry.at)
                                ChatView(entry.text, null, liveSize, vm.readingFont, antigravity = antigravity, grok = grok, agent = chatAgent,
                                    times = vm.screenTimesFor(paneId), hostOffsetMs = vm.hostOffsetMs(), hostTz = vm.hostTzMinutes())
                            }
                            is com.billmongan.herdrdroid.transcript.QuestionItem -> QuestionView(entry, liveSize)
                        }
                    }
                    val pending = vm.lastSent?.takeIf { (id, text) ->
                        id == paneId && vm.removableSendsFor(paneId).none { it.text == text }
                    }?.second
                    // Under a saved history, only the part of the screen not already shown above (1.4.3, from review).
                    // The same questions the history shows (from review): an open one's screen stays live.
                    ChatView(com.billmongan.herdrdroid.transcript.QuestionLog.liveTail(text,
                        vm.questionsFor(paneId).filter { questionCard == null || it.state != com.billmongan.herdrdroid.transcript.QState.OPEN }), pending, liveSize, vm.readingFont,
                        pendingAt = vm.lastSentAt, antigravity = antigravity, grok = grok, agent = chatAgent,
                        times = vm.screenTimesFor(paneId), onSeen = { vm.noteScreenSeen(paneId, it) },
                        hostOffsetMs = vm.hostOffsetMs(), hostTz = vm.hostTzMinutes())
                }
                promptCard()
            }
            if (vm.paneUpdating(paneId) && !fullTerminal) UpdatingChip(Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
            if (!termFollow && screenText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(20.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line), shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                        .clickable { termFollow = true; termScope.launch { scroll.animateScrollTo(scroll.maxValue) } },
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to the latest", tint = Palette.Blue, modifier = Modifier.size(18.dp))
                        Text("Latest", color = Palette.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ---- Keys and the message box -------------------------------------------------------
        if (!live) Column(
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
            val idleQueue = vm.queued.filter { it.paneId == paneId && it.serverId == vm.current?.id }
            val unsent = vm.removableSendsFor(paneId)
            if (idleQueue.isNotEmpty() || unsent.isNotEmpty()) {
                Column(
                    Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    idleQueue.forEach { q ->
                        androidx.compose.runtime.key(q.id) {
                            val canSend = !q.inFlight() && vm.link == Link.CONNECTED && !usageBlocksInput && !waitingForHermes && prompt == null && !typingAnswer && status != Status.NEEDS_YOU
                            PendingMessageRow(
                                title = if (q.inFlight()) "Sending now" else "Queued until idle",
                                text = q.text,
                                note = q.note,
                                onRemove = { vm.cancelQueued(q) },
                                sendLabel = "Send immediately",
                                onSend = { vm.sendQueuedImmediately(q) },
                                sendEnabled = canSend,
                                // 2.2.7: long-press for Send now (Ctrl+B first where Claude shows its background hint).
                                // 2.2.8: named Send immediately there, with Share, for agents that background tasks.
                                forceSendMenu = true,
                                menuSendLabel = if (backgroundSupport(pane?.agent, pane?.agentName ?: "") == null) "Send immediately (background running tasks)" else "Send now",
                                onMenuSend = { vm.sendQueuedNow(q) },
                            )
                        }
                    }
                    unsent.forEach { p ->
                        androidx.compose.runtime.key(p.id) {
                            PendingMessageRow(
                                title = when {
                                    p.awaitingLink -> "Waiting to reconnect"
                                    p.waiting -> "Waiting for the input line"
                                    else -> "Delivery not confirmed"
                                },
                                text = p.text,
                                note = p.held,
                                onRemove = { vm.dismissPending(p.id) },
                                sendLabel = when {
                                    p.waiting -> "Force send immediately"
                                    p.undelivered -> "Resend"
                                    else -> null
                                },
                                onSend = { vm.forceSendPending(p.id) },
                                sendEnabled = vm.canForceSendPending && !usageBlocksInput && !waitingForHermes && prompt == null && !typingAnswer && status != Status.NEEDS_YOU,
                                forceSendMenu = true,
                            )
                        }
                    }
                }
            }
            if (fullTerminal) {
                val found = remember(liveScreenText) { screenLinks(liveScreenText) }
                if (commandScreen != null || found.urls.isNotEmpty() || found.codes.isNotEmpty()) {
                    val clipboard = LocalClipboardManager.current
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        commandScreen?.let { c ->
                            ComposerChip("Back to chat", false) { fullTerminal = false }
                            Text(if (c == "/usage" && pane?.agent == "hermes") "$c: usage report" else "$c: use the keys to choose",
                                color = Palette.Muted, fontSize = 12.5.sp, maxLines = 1, softWrap = false)
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
            // Shown whenever you ask for them, keyboard up or not.
            if (vm.showKeys || modifiersOn || commandScreen != null) {
                KeyPad(
                    ctrl = ctrl, alt = alt,
                    onCtrl = { ctrl = it }, onAlt = { alt = it },
                    onKey = ::press,
                    onCtrlC = { confirmCtrlC = true },
                    background = com.billmongan.herdrdroid.herdr.Backgrounding.supported(pane?.agent),
                )
            }
            ChatComposer(
                value = message,
                onValueChange = { new ->
                    // With Ctrl or Alt on, a typed character becomes a key chord instead of text.
                    if (modifiersOn && new.length == message.length + 1 && new.startsWith(message)) {
                        keyName(new.last())?.let { press(it) }
                    } else {
                        editMessage(new)
                    }
                },
                placeholder = if (modifiersOn) "Type a letter to send it with ${if (ctrl != Hold.OFF) "Ctrl" else "Alt"}"
                else if (typingAnswer) if (vm.answerInFlight(paneId)) "Checking answer…" else "Type your answer"
                else if (backgroundDrawer) "Close the background drawer to send"
                else if (waitingForHermes) "Send when Hermes is ready"
                else if (prompt != null || status == Status.NEEDS_YOU) "Send after answer"
                else "Message ${pane?.agentName ?: "this tab"}",
                // 2.2.8 (Bill: "I can't send a message while a question card is open ... let me send it
                // from the app at least"): Send stays on while a question is open; the message waits in
                // the app's queue (see [send]) and goes once the question is answered or gone.
                canSend = message.isNotBlank() && pane != null && !usageBlocksInput && !backgroundDrawer &&
                    !(typingAnswer && vm.answerInFlight(paneId)),
                onSend = ::send,
                working = status == Status.WORKING,
                onStop = pane?.let { p -> { vm.stopPane(p) } },
                // herdr's agent prompt reaches a working agent, so a message sent now steers it.
                steerLabel = if (pane?.agent != null && !typingAnswer && prompt == null) "Steer" else null,
                onQueueWhenIdle = if (!typingAnswer && !usageBlocksInput) ({ vm.enqueue(paneId, normalMessage.trim(), behindQuestion = prompt != null || openingPreview != null || pane?.let { vm.hasPendingQuestion(it) } == true); vm.clearComposerDraft(paneId) }) else null,
                onAttach = { uri -> vm.attach(uri, paneId) },
                attachBusy = vm.uploading,
                commands = SlashCommands.forAgent(pane?.agent),
                // One row: Keys, Snippets and When idle live in the "⋯" menu with attach and dictate.
                compact = true,
                menuItems = buildList {
                    if (typingAnswer && !vm.answerInFlight(paneId)) add("Discard answer draft" to { answerDraft = null })
                    add((if (vm.showKeys) "Hide keys" else "Show keys") to { vm.setKeysShown(!vm.showKeys) })
                    add("Snippets" to { showSnippets = true })
                    add(SEND_SECRET_ITEM to { showSecret = true })
                },
                insert = vm.secretInsertFor(paneId)?.first,
                takeInsert = { id -> vm.takeSecretInsert(paneId, id) },
            )
        }
    }

    if (showSecret) SendSecretDialog(vm, paneId) { showSecret = false }

    if (showSnippets) {
        SnippetsDialog(vm, onInsert = { t -> editMessage(if (message.isBlank()) t else message.trimEnd() + "\n" + t) }, onDismiss = { showSnippets = false })
    }

    opened?.let { t ->
        FullMessage(t, liveSize, vm.readingFont.family, onQuote = { quote(t); opened = null }, onClose = { opened = null })
    }

    if (exportingScreen) PaneScreenExportHost(vm, paneId) { exportingScreen = false }
    if (showingPaneReads) PaneReadsDialog(vm) { showingPaneReads = false }

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
                        "\"Open the picker\" sends the agent's own model command, reads its menu, and lists it here; your choice is sent as the keys.",
                        color = Palette.Muted, fontSize = 12.5.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = idle, onClick = {
                    choosingModel = false
                    vm.runCommand(paneId, ModelPresets.pickerCommand(pane.agent))
                    pickerWhat = "model"
                    readingPicker = true
                }) { Text("Open the picker") }
            },
            dismissButton = { TextButton(onClick = { choosingModel = false }) { Text("Cancel") } },
        )
    }

    if (choosingMode != null && pane != null) com.billmongan.herdrdroid.herdr.PaneControls.modeMenus(pane.agent).firstOrNull { it.title == choosingMode }?.let { spec ->
        ModeDialog(pane.agentName, spec, spec.read(liveScreenText),
            idle = status == Status.IDLE || status == Status.DONE, onPick = { vm.switchMode(paneId, it, spec.title) }, onDismiss = { choosingMode = null })
    }
    if (choosingThinking && pane != null) com.billmongan.herdrdroid.herdr.PaneControls.thinking(pane.agent)?.let { spec ->
        ThinkingDialog(pane.agentName, spec, spec.shown(liveScreenText) ?: vm.efforts[paneId],
            idle = status == Status.IDLE || status == Status.DONE, onPick = { vm.setThinking(paneId, it) }, onDismiss = { choosingThinking = false })
    }
    if (choosingSession && pane != null) com.billmongan.herdrdroid.herdr.SessionControls.forAgent(pane.agent)?.let { spec ->
        var sessions by remember(paneId) { mutableStateOf<List<com.billmongan.herdrdroid.herdr.PastSession>?>(null) }
        var problem by remember(paneId) { mutableStateOf<String?>(null) }
        LaunchedEffect(paneId) {
            vm.pastSessions(paneId).onSuccess { sessions = it }.onFailure { problem = it.message ?: "The sessions couldn't be read." }
        }
        val liveNow = vm.liveSession(paneId)
        val byId = spec.resumeById != null
        SessionsDialog(
            pane.agentName, sessions, problem,
            isCurrent = { s -> liveNow?.isSession(s.ref, s.db) ?: (s == sessions?.firstOrNull()) },
            canSwitch = byId || spec.picker != null,
            switchNote = if (byId) "Tap a session to switch with ${spec.resumeById} <id>."
                else if (spec.picker != null) "Tapping a session opens ${pane.agentName}'s own picker (${spec.picker}); choose it there."
                else "${pane.agentName} switches sessions only at the computer.",
            herdrOutOfDate = vm.herdrOutOfDate(paneId),
            idle = status == Status.IDLE || status == Status.DONE,
            onNew = { vm.newSession(paneId) },
            onPick = { s -> vm.resumeSession(paneId, s) { pickerWhat = "session"; readingPicker = true } },
            onPicker = spec.picker?.takeIf { byId }?.let { { vm.openSessionPicker(paneId) { pickerWhat = "session"; readingPicker = true } } },
            onDismiss = { choosingSession = false },
        )
    }

    // After "Open the picker": read the agent's menu off its screen for a few seconds and list it.
    // If no menu can be read, fall back to the terminal view and the keys.
    LaunchedEffect(readingPicker) {
        if (!readingPicker) return@LaunchedEffect
        var found: Prompt? = null
        repeat(12) {
            kotlinx.coroutines.delay(500)
            vm.readPane(paneId)
            found = detectPrompt(vm.paneText, blocked = true)?.takeIf { it.choices.size >= 2 }
                ?: com.billmongan.herdrdroid.herdr.detectUnnumberedMenu(vm.paneText)
            if (found != null) return@repeat
        }
        readingPicker = false
        if (found != null) pickerMenu = found else { fullTerminal = true; vm.setKeysShown(true) }
    }
    pickerMenu?.let { menuNow ->
        AlertDialog(
            onDismissRequest = { pickerMenu = null; vm.sendKeys(paneId, "esc") },
            title = { Text(menuNow.question.lines().firstOrNull()?.take(80)?.ifBlank { null } ?: if (pickerWhat == "session") "Choose a session" else "Choose a model") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    menuNow.choices.forEach { c ->
                        val now = menuNow.selected == c.keys
                        Surface(
                            shape = RoundedCornerShape(12.dp), color = if (now) Palette.BlueSoft else Palette.Card,
                            border = BorderStroke(1.dp, if (now) Palette.Blue else Palette.Line),
                            modifier = Modifier.fillMaxWidth().clickable { pickerMenu = null; vm.answerChoice(paneId, menuNow, c) },
                        ) {
                            Text(c.label, color = Palette.Ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickerMenu = null; vm.sendKeys(paneId, "esc") }) { Text("Cancel") } },
        )
    }
    if (readingPicker) {
        AlertDialog(onDismissRequest = {}, title = { Text(if (pickerWhat == "session") "Reading the session picker…" else "Reading the model menu…") },
            text = { Text(if (pickerWhat == "session") "Sent ${com.billmongan.herdrdroid.herdr.SessionControls.forAgent(pane?.agent)?.picker}." else "Sent ${ModelPresets.pickerCommand(pane?.agent)}.") }, confirmButton = {})
    }

    if (closingSession && pane != null) {
        val srv = vm.current
        if (srv != null) CloseSessionDialog(
            AppViewModel.Session(srv, pane, status, false, true),
            onDismiss = { closingSession = false },
        ) { closingSession = false; vm.closeSession(srv.id, pane) }
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

@Composable
internal fun UsageOverlayBanner(
    dismissing: Boolean,
    btw: Boolean = false,
    /** 2.2.1: an informational panel read off the screen; null keeps the 2.1.4 usage banner. */
    panel: com.billmongan.herdrdroid.herdr.InfoPanel? = null,
    agentName: String = "The agent",
    /** Why the last close didn't finish, if it didn't. */
    problem: String? = null,
    /** The panel is positively identified on the live screen, so Close panel may press Esc. */
    canClose: Boolean = true,
    onShowTerminal: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    if (panel != null && !btw) {
        PanelBanner(dismissing, panel, agentName, problem, canClose, onShowTerminal, onDismiss)
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // 2.1.4: Claude's /btw panel too; its exchange is in the chat, and Close shuts the panel.
        Text(if (btw) "Side question (/btw) is open; its answer is in the chat" else "Usage panel is open", Modifier.weight(1f), color = Palette.Ink)
        TextButton(onClick = onDismiss, enabled = !dismissing) {
            Text(if (dismissing) (if (btw) "Closing…" else "Dismissing…") else if (btw) "Close" else "Dismiss usage")
        }
    }
}

/**
 * 2.2.1: an agent's informational panel (status, usage, session info, help...) over its composer.
 * Positively identified: "<agent>'s <panel> is open" with Close panel (one checked Esc, at most two).
 * Otherwise, or when a close didn't finish: "<agent> has a <panel> open; close it at the computer".
 */
@Composable
internal fun PanelBanner(
    closing: Boolean,
    panel: com.billmongan.herdrdroid.herdr.InfoPanel,
    agentName: String,
    problem: String?,
    canClose: Boolean,
    onShowTerminal: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val closable = canClose && panel.known
    val label = panel.kind.label
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            if (closable && problem == null) "$agentName's $label is open" else "$agentName has a $label open; close it at the computer",
            color = Palette.Ink,
        )
        (problem ?: panel.why?.takeIf { !closable })?.let { Text(it.replaceFirstChar(Char::uppercase), color = Palette.Muted, fontSize = 12.5.sp) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (closable) TextButton(onClick = onClose, enabled = !closing) { Text(if (closing) "Closing…" else "Close panel") }
            onShowTerminal?.let { TextButton(onClick = it) { Text("Show terminal") } }
        }
    }
}

/**
 * Two rows that always fit the screen: Ctrl, Alt and the four arrows together on top, then
 * Esc, Tab, Backspace, Enter and Ctrl+C.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PendingMessageRow(
    title: String,
    text: String,
    onRemove: () -> Unit,
    sendLabel: String?,
    onSend: (() -> Unit)?,
    note: String? = null,
    sendEnabled: Boolean = true,
    forceSendMenu: Boolean = false,
    /** 2.2.7: the long-press menu's send entry and its action (a queued message's Send now). */
    menuSendLabel: String = "Force send immediately",
    onMenuSend: (() -> Unit)? = onSend,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(Palette.BlueSoft, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp),
    ) {
        val content = if (forceSendMenu) Modifier.combinedClickable(
            onClick = {},
            onLongClickLabel = "Message options",
            onLongClick = { menu = true },
        ) else Modifier
        Box(Modifier.fillMaxWidth()) {
            Column(content.fillMaxWidth().padding(top = 6.dp)) {
                Text(title, color = Palette.Blue, fontSize = 12.sp)
                Text(text, color = Palette.Ink, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                note?.let { Text(it, color = Palette.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (forceSendMenu) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(menuSendLabel) },
                        enabled = sendEnabled && onMenuSend != null,
                        onClick = { menu = false; onMenuSend?.invoke() },
                    )
                    DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) })
                    // 2.2.8: Share through the Android share sheet.
                    DropdownMenuItem(text = { Text("Share") }, onClick = {
                        menu = false
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
                    })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (sendLabel != null && onSend != null) {
                TextButton(onClick = onSend, enabled = sendEnabled) {
                    Text(sendLabel, fontSize = 12.sp, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onRemove) { Text("Remove", fontSize = 12.sp, softWrap = false, maxLines = 1) }
        }
    }
}

@Composable
private fun KeyPad(
    ctrl: Hold,
    alt: Hold,
    onCtrl: (Hold) -> Unit,
    onAlt: (Hold) -> Unit,
    onKey: (String) -> Unit,
    onCtrlC: () -> Unit,
    /** The agent backgrounds a running command with Ctrl+B: show that key as its own button. */
    background: Boolean = false,
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
            if (background) KeyButton("Ctrl+B", Modifier.weight(1.3f)) { onKey("ctrl+b") }
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
private fun KeyButton(label: String, modifier: Modifier = Modifier, danger: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (danger) Palette.OrangeSoft else if (selected) Palette.BlueSoft else Palette.Key,
        border = BorderStroke(1.dp, if (danger) Palette.OrangeEdge else if (selected) Palette.Blue else Palette.KeyEdge),
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

/**
 * The agent's question as part of the conversation (1.2.8): what it said just before (an edit's
 * diff, a plan, its last words) and the question itself, selectable and scrolling with the chat or
 * terminal. The orange [PromptCard] below it holds only the answers.
 */
@Composable
internal fun PromptQuestion(p: Prompt, agent: String, screen: String? = null,
    /** 2.2.0: a tap on a question pill, when the agent's dialog can move there ([QuestionPills]); null shows them for display only. */
    onStep: ((Int, com.billmongan.herdrdroid.herdr.QTab) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$agent asks", color = Palette.OrangeInk, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)

        val parts = p.shownParts()
        if (parts.tabs.isNotEmpty()) QuestionSteps(parts, onStep)
        // The question first, then paragraphs, lists, commands and diffs laid out for the phone
        // (2.1.1); Show raw gives the parser's text as before (1.2.9).
        CardBody(parts, screen)
    }
}

/**
 * Claude's multi-question dialog as steps: "Question 1 of 4", then a chip for each question and
 * Submit. With [onStep] (2.2.0) each chip but the current one is a button that moves the dialog there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionSteps(parts: com.billmongan.herdrdroid.herdr.QuestionParts,
    onStep: ((Int, com.billmongan.herdrdroid.herdr.QTab) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.step?.let { Text(it, color = Palette.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.tabs.forEachIndexed { i, t ->
                val (bg, edge, ink) = when {
                    t.current -> Triple(Palette.OrangeSoft, Palette.Orange, Palette.OrangeInk)
                    t.done -> Triple(Palette.GreenSoft, Palette.GreenSoft, Palette.Ink)
                    else -> Triple(Palette.Card, Palette.Line, Palette.Muted)
                }
                val tap = onStep?.takeIf { !t.current }
                Surface(shape = RoundedCornerShape(50), color = bg, border = BorderStroke(1.dp, edge),
                    modifier = Modifier.testTag(if (tap != null) "question-pill-tappable" else "question-pill")
                        .then(if (tap != null) Modifier.clip(RoundedCornerShape(50)).clickable(
                            onClickLabel = if (t.submit) "Go to the review step" else "Go to this question",
                            role = androidx.compose.ui.semantics.Role.Button) { tap(i, t) } else Modifier)) {
                    Text((if (t.done) "✓ " else "") + QuestionText.title(t.title), color = ink, fontSize = 12.5.sp,
                        fontWeight = if (t.current) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

/**
 * The pane's terminal (1.4.8): the last screen read, in its own font, with a note when the latest
 * read failed; with nothing read yet, a heading that says it is the terminal view and why it is empty.
 */
@Composable
internal fun TerminalView(text: String, readProblem: String?, size: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Terminal", color = Palette.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        if (text.isBlank()) {
            Text(readProblem?.let { "Nothing has been read from this pane yet ($it). The app keeps trying." }
                ?: "Reading this pane's screen…", color = Palette.Muted, fontSize = 15.sp)
            return@Column
        }
        // The last screen that was read, saying so when the latest read failed (1.4.2, from review).
        readProblem?.let { Text("The latest read failed ($it); this is the last screen the app read.", color = Palette.OrangeInk, fontSize = 12.5.sp) }
        SelectionContainer {
            Text(
                text,
                color = Palette.TerminalInk,
                fontFamily = Fonts.Mono,
                fontSize = (size * 0.88f).sp,
                lineHeight = (size * 0.88f * 1.35f).sp,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Terminal, RoundedCornerShape(12.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

/** 2.2.0: a small marker that herdr hasn't heard of the session the terminal is in yet. */
@Composable
internal fun HerdrOutOfDate() {
    Text("herdr out of date · the chat follows the terminal's session", color = Palette.OrangeInk, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).semantics { contentDescription = "herdr out of date" })
}

/** The screen card's rows (2.2.0): the focused row bold and blue, marked as the card's choice instead of the terminal's "❯". */
internal fun regionText(view: CardFormat.RegionView): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    view.rows.forEachIndexed { i, row ->
        if (i > 0) append("\n")
        if (i == view.focused) withStyle(androidx.compose.ui.text.SpanStyle(color = Palette.Blue, background = Palette.BlueSoft, fontWeight = FontWeight.Bold)) { append(row) }
        else append(row)
    }
}

/** The question card, or in the terminal view a one-line bar that leads back to it (1.4.8). */
@Composable
internal fun CardOrTerminalBar(showingTerminal: Boolean, agent: String, onBackToQuestion: () -> Unit, card: @Composable () -> Unit) {
    if (!showingTerminal) { card(); return }
    Surface(shape = RoundedCornerShape(14.dp), color = Palette.OrangeSoft, border = BorderStroke(1.dp, Palette.Orange)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.OrangeInk, modifier = Modifier.size(18.dp))
            Text("$agent is waiting for your answer", color = Palette.OrangeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = onBackToQuestion) { Text("Back to the question") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PromptCard(
    p: Prompt,
    agent: String,
    onChoice: (Choice) -> Unit,
    onKey: (String) -> Unit,
    onTypeAnswer: () -> Unit,
    /** A choice that means "I'll type it" ("Type something.", "Other"): move there, then type (1.4.0). */
    onTypedChoice: (Choice) -> Unit = { onTypeAnswer() },
    /** Switches to the terminal view (1.4.2): always offered, so the screen itself is never out of reach. */
    onShowTerminal: (() -> Unit)? = null,
    sending: Boolean = false,
    readOnlyPreview: Boolean = false,
    /** The line an agent draws under an option, by its keys (2.1.1): shown under the label, display only. */
    optionNotes: Map<String, String> = emptyMap(),
) {
    @Suppress("NAME_SHADOWING") val p = p.forCard()
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Palette.Card,
        border = BorderStroke(2.dp, Palette.Orange),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.OrangeInk, modifier = Modifier.size(18.dp))
                // One line only; the question itself is just above the card, in the chat (1.2.8).
                Text(if (readOnlyPreview) "Preview" else if (sending) "Checking and sending…"
                    else if (p.choices.isEmpty()) "$agent is waiting for you" else "Your answer", color = Palette.OrangeInk,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (readOnlyPreview) {
                onShowTerminal?.let { show ->
                    TextButton(onClick = show, modifier = Modifier.fillMaxWidth()) { Text("Show terminal") }
                }
                return@Column
            }
            // Each choice grows to fit its whole label; the page scrolls, the card has no scroll of its own.
            if (p.terminalReview) {
                Text(when {
                    p.hermesClarify -> "Answer this Hermes question in Terminal."
                    p.codexQuestion -> "Answer this Codex question in Terminal."
                    // 2.1.2: a question the app reads but can't answer for you (several questions or answers).
                    p.openCodeForm -> "Answer this OpenCode question in Terminal: it asks more than one question or takes more than one answer, which the app can't send for you."
                    // 2.1.4: the pane needs you, but its question couldn't be read from the screen.
                    p.unreadQuestion -> "The app couldn't read this question from the screen. Open the terminal to see it and answer it there."
                    // 2.2.2: the dialog is still drawing, or hasn't read the same twice yet; its answers come next.
                    p.readingQuestion -> "Its answers show here once the question has finished drawing on the screen."
                    else -> "This preview is incomplete. Open the terminal to review the full command before answering."
                },
                    color = Palette.Muted, fontSize = 12.5.sp)
                onShowTerminal?.let { show ->
                    TextButton(onClick = show, modifier = Modifier.fillMaxWidth()) { Text("Show terminal") }
                }
                return@Column
            }
            // Claude's session behind its sessions list (2.1.1): its own hint says Esc returns to it,
            // and then its question dialog shows and becomes the usual card. Nothing is sent until tapped.
            if (p.claudeBackground) {
                Text("Its terminal shows Claude's sessions list instead of the session, so its question isn't on screen. " +
                    "Esc returns to the session.", color = Palette.Muted, fontSize = 12.5.sp)
                Button(
                    enabled = !sending,
                    onClick = { onKey("esc") },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnBlue),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Return to the session (Esc)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                onShowTerminal?.let { show ->
                    TextButton(onClick = show, modifier = Modifier.fillMaxWidth()) { Text("Show terminal") }
                }
                return@Column
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The fallback card (1.4.0): the screen looked like a question no parser understood, so
                // the card shows that part of the screen and generic keys instead of choices.
                // The screen couldn't be read: say so and offer the terminal, but no keys, which would go
                // into a screen nobody has seen (1.4.2, from review).
                val unread = p.choices.isEmpty() && p.region == listOf(com.billmongan.herdrdroid.herdr.UNREAD_SCREEN)
                if (unread) Text("The app couldn't read this pane's screen yet. Tap Show terminal to see the last screen it read, or open it at the computer.",
                    color = Palette.Muted, fontSize = 12.5.sp)
                p.region?.takeIf { p.choices.isEmpty() && !unread }?.let { region ->
                    Text(if (region == listOf(com.billmongan.herdrdroid.herdr.UNREAD_SCREEN)) "The app couldn't read this pane's screen yet; the keys below still go to it:"
                        else "The app couldn't read this question's options, so here is the screen and the keys to answer it:",
                        color = Palette.Muted, fontSize = 12.5.sp)
                    // 2.2.0: without the dialog's gutter and focus marker; the focused row is the card's own highlight.
                    val view = remember(region) { CardFormat.regionView(region) }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(regionText(view), color = Palette.TerminalInk, fontFamily = Fonts.Mono, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().background(Palette.Terminal, RoundedCornerShape(10.dp)).padding(10.dp))
                    }
                    val numbers = region.mapNotNull { Regex("""^[❯›>▶➤\s]*(\d{1,2})[.)]\s""").find(it)?.groupValues?.get(1) }.distinct().take(9)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        numbers.forEach { n -> KeyButton(n, selected = n == view.focusedKey) { onKey(n) } }
                        KeyButton("↑") { onKey("up") }
                        KeyButton("↓") { onKey("down") }
                        KeyButton("Space") { onKey("space") }
                        KeyButton("Enter") { onKey("enter") }
                    }
                }
                if (p.hermesClarify) Text("Use Terminal to type a custom answer or cancel.",
                    color = Palette.Muted, fontSize = 12.5.sp)
                if (p.multi) Text("Several can be picked. Tapping a checkbox only turns that choice on or off.",
                    color = Palette.Muted, fontSize = 12.5.sp)
                // 2.1.4: "Type something." and "Chat about this" show as one "Type your answer".
                val allRows = p.answerRows()
                if (com.billmongan.herdrdroid.herdr.typedChatPair(allRows) != null)
                    Text("\u201cChat about this\u201d isn't offered here; use Terminal for it.", color = Palette.Muted, fontSize = 12.5.sp)
                com.billmongan.herdrdroid.herdr.shownChoices(allRows).forEach { c ->
                    // Say what tapping really sends: arrows and Enter for a menu with a highlighted row,
                    // Esc for a "(esc)" option, otherwise the key itself (1.2.7; it always said "sends N").
                    val typed = !p.hermesClarify && com.billmongan.herdrdroid.herdr.isTypedAnswerChoice(c.label)
                    val what = when {
                        typed -> "moves there, then you type your answer"
                        p.openCodePermission && c.keys == "1" -> "checks the live selection before confirming"
                        else -> keysLabel(com.billmongan.herdrdroid.herdr.menuKeys(p, c), c)
                    }
                    val caution = com.billmongan.herdrdroid.herdr.grantsStandingPermission(c.label)
                    // The highlighted row (the one Enter picks) is the blue one, not always the first.
                    val first = c.keys == (p.selected ?: p.choices.firstOrNull()?.keys?.takeUnless { p.openCodePermission })
                    val body: @Composable () -> Unit = {
                        Column(Modifier.fillMaxWidth()) {
                            // A key hint ("(esc)") or tail reads quieter; the label's text is unchanged (2.1.1).
                            Text(optionLabel(com.billmongan.herdrdroid.herdr.shownChoiceLabel(allRows, c), if (first) Palette.OnBlue.copy(alpha = 0.8f) else Palette.Muted, c.keys),
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                                color = if (first) Palette.OnBlue else Palette.Ink,
                                // 2.2.2: the highlighted row is the card's own blue button; tagged for the UI tests.
                                modifier = Modifier.testTag(if (first) "choice-label-focused" else "choice-label"))
                            optionNotes[c.keys]?.let { note ->
                                // 2.2.2: the description under its label, smaller and quieter, one paragraph.
                                OptionNote(note, if (first) Palette.OnBlue.copy(alpha = 0.9f) else Palette.Muted)
                            }
                            Text(what, fontSize = 12.sp, color = if (first) Palette.OnBlue.copy(alpha = 0.8f) else Palette.Muted)
                            // Standing permission (1.4.0): say so before it is tapped.
                            if (caution) Text("Caution: this lets the agent do it again without asking you.", fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = if (first) Palette.OnBlue else Palette.OrangeInk)
                        }
                    }
                    val pad = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    if (first) {
                        Button(
                            enabled = !sending,
                            onClick = { if (typed) onTypedChoice(c) else onChoice(c) },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnBlue),
                            contentPadding = pad,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { body() }
                    } else {
                        OutlinedButton(
                            enabled = !sending,
                            onClick = { if (typed) onTypedChoice(c) else onChoice(c) },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = pad,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { body() }
                    }
                }
            }
            // 2.1.2: OpenCode's question takes your own words too, on its last row: the card offers that row as well.
            p.openCodeCustom?.let { digit ->
                val custom = Choice("Type your own answer", digit, false)
                OutlinedButton(
                    enabled = !sending,
                    onClick = { onTypedChoice(custom) },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(custom.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, color = Palette.Ink)
                        Text("opens its answer row, then you type your answer", fontSize = 12.sp, color = Palette.Muted)
                    }
                }
            }
            if (!p.hermesClarify && !p.openCodePermission && !sending && !(p.choices.isEmpty() && p.region == listOf(com.billmongan.herdrdroid.herdr.UNREAD_SCREEN))) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton("Esc") { onKey("esc") }
                // OpenCode's question dialog takes Esc ("esc dismiss") and its digits, nothing else from here (2.1.2).
                if (p.openCodeQuestionFrame == null) {
                    KeyButton("Tab") { onKey("tab") }
                    KeyButton("Shift+Tab") { onKey("shift+tab") }
                    KeyButton("Ctrl+C", danger = true) { onKey("ctrl+c") }
                }
            }
            if (!p.hermesClarify && !p.openCodePermission && p.openCodeQuestionFrame == null) TextButton(onClick = onTypeAnswer, enabled = !sending, modifier = Modifier.fillMaxWidth()) { Text("Type a different answer") }
            onShowTerminal?.let { show -> TextButton(onClick = show, modifier = Modifier.fillMaxWidth()) { Text("Show terminal") } }
            if (!p.hermesClarify) Text(
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
 * A quiet "Updating" chip over the chat (2.2.0) while a pane just opened shows its last good view and
 * its fresh one is still settling.
 */
@Composable
internal fun UpdatingChip(modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line),
        modifier = modifier.testTag("pane-updating")) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(strokeWidth = 1.5.dp, color = Palette.Muted, modifier = Modifier.size(10.dp))
            Text("Updating", color = Palette.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * The pane as a conversation: the agent's paragraphs rewrapped for the phone in the reading
 * font, your messages as bubbles on the right, tables and boxes in monospace strips that scroll
 * sideways, and the agent's own input box and status line as a quiet footer.
 */
@Composable
internal fun ChatView(
    text: String, pending: String?, size: Float, font: ReadingFont,
    pendingAt: Long? = null, antigravity: Boolean = false, grok: Boolean = false, agent: String? = null,
    /** First-seen times of the screen's messages (2.1.4, [com.billmongan.herdrdroid.herdr.ScreenTimes]). */
    times: Map<String, Long> = emptyMap(),
    /** Called with the messages on screen, so the ones not seen before are given a time; null for an earlier screen. */
    onSeen: ((List<com.billmongan.herdrdroid.herdr.ScreenTimes.Msg>) -> Unit)? = null,
    hostOffsetMs: Long = 0L,
    hostTz: Int? = null,
) {
    val segs = remember(text, antigravity, grok, agent) { Reflow.parse(text, antigravity, grok, agent) }
    // Each message's time (2.1.4): Grok's own from its screen, else when the app first saw it.
    val msgs = remember(segs, text, grok) {
        com.billmongan.herdrdroid.herdr.ScreenTimes.messages(segs, if (grok) com.billmongan.herdrdroid.herdr.GrokClock.stamps(text) else emptyList())
    }
    val seenCb by androidx.compose.runtime.rememberUpdatedState(onSeen)
    LaunchedEffect(msgs) { seenCb?.invoke(msgs) }
    val timeAfter = remember(msgs, times, hostOffsetMs, hostTz) {
        val now = System.currentTimeMillis() - hostOffsetMs
        msgs.mapNotNull { m ->
            val at = m.clock?.let { com.billmongan.herdrdroid.herdr.GrokClock.toEpoch(it, now, hostTz) } ?: times[m.key]
            at?.let { m.last to (it to (segs.getOrNull(m.first) is Seg.You)) }
        }.toMap()
    }
    val family: FontFamily = font.family
    val prose = if (font == ReadingFont.MONO) size else size * 1.2f
    val showPending = pending != null && segs.none { it is Seg.You && norm(it.text).startsWith(norm(pending).take(20)) }

    // 2.2.0: long-press a message for Copy, Copy for each box in it and Select text; long-press a box
    // to copy just it. Select text puts back the selection the screen view had before.
    var selecting by remember { mutableStateOf(false) }
    var menuAt by remember(text) { mutableStateOf<Int?>(null) }
    val copy = rememberCopier()
    val copies = remember(segs, msgs) { ScreenCopy(segs, msgs) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selecting) Text("Done selecting", color = Palette.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { selecting = false }.padding(horizontal = 8.dp, vertical = 6.dp))
            segs.forEachIndexed { i, s ->
                val press = if (selecting || copies.whole(i) == null) Modifier
                    else if (s is Seg.Code) Modifier.onLongPress("Copy this box") { copy(BoxCopy.clean(s.lines), "Copied box") }
                    else Modifier.onLongPress("Copy options") { menuAt = i }
                Box(press) {
                when (s) {
                    Seg.Gap -> Spacer(Modifier.height(6.dp))
                    Seg.Rule -> HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 6.dp))
                    is Seg.You -> YouBubble(s.text, prose)
                    is Seg.Activity -> ScreenActivity(s)
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
                if (menuAt == i) copies.whole(i)?.let { whole ->
                    CopyMenu(true, whole, copies.boxes(i), onDismiss = { menuAt = null }, onSelect = { selecting = true })
                }
                }
                timeAfter[i]?.let { (at, mine) -> ChatTimestamp(at, end = mine) }
            }
            if (showPending) {
                YouBubble(pending!!, prose, sending = true)
                ChatTimestamp(pendingAt, end = true)
            }
        }
    }
    if (selecting) SelectionContainer { content() } else content()
}

/**
 * What a long press copies in the screen view (2.2.0): the message a piece belongs to (yours, or the
 * agent's reply from its first paragraph through its last box), and that message's boxes, each as
 * the text it shows ([BoxCopy.clean]).
 */
internal class ScreenCopy(private val segs: List<Seg>, msgs: List<com.billmongan.herdrdroid.herdr.ScreenTimes.Msg>) {
    private val owner: List<IntRange?> = segs.indices.map { i -> msgs.firstOrNull { i in it.first..it.last }?.let { it.first..it.last } }

    private fun textOf(s: Seg): String? = when (s) {
        is Seg.You -> s.text
        is Seg.Para -> (s.mark?.takeIf { LIST_MARK.matches(it) }?.let { (if (it.first().isDigit()) it else "-") + " " } ?: "") + s.text
        is Seg.Code -> BoxCopy.clean(s.lines)
        else -> null
    }

    private fun range(i: Int): IntRange? = owner.getOrNull(i) ?: i.takeIf { segs.getOrNull(it)?.let(::textOf) != null }?.let { it..it }

    /** The whole message piece [i] is in, or null when it is not text (a rule, a gap, the footer). */
    fun whole(i: Int): String? = if (segs.getOrNull(i)?.let(::textOf) == null) null else range(i)?.mapNotNull { segs.getOrNull(it)?.let(::textOf) }?.filter { it.isNotBlank() }
        ?.joinToString("\n\n")?.takeIf { it.isNotBlank() }

    /** The boxes in the message piece [i] is in, each as it shows. */
    fun boxes(i: Int): List<String> = range(i)?.mapNotNull { (segs.getOrNull(it) as? Seg.Code)?.let { c -> BoxCopy.clean(c.lines) } }
        ?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        val LIST_MARK = Regex("""^([-*•·]|\d{1,3}[.)])$""")
    }
}

/**
 * Antigravity's thinking and tool-call lines, folded into one chip (2.1.1), like the activity row
 * the agents with a transcript get. Tap it for the steps, one per line, with their arguments.
 */
@Composable
private fun ScreenActivity(a: Seg.Activity) {
    var open by rememberSaveable(a.steps.firstOrNull()?.title, a.steps.size) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Palette.Activity,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (a.running) {
                    androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.Blue, modifier = Modifier.size(14.dp))
                } else {
                    Text(if (a.failed > 0) "!" else "✓", color = if (a.failed > 0) Palette.OrangeInk else Palette.Green,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(a.summary, color = Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val last = a.steps.lastOrNull { it.kind != com.billmongan.herdrdroid.herdr.AgyMeta.Kind.THINK } ?: a.steps.last()
                    Text(last.title + (last.detail?.let { " ($it)" } ?: ""), color = Palette.Muted, fontSize = 12.5.sp,
                        fontFamily = Fonts.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (open) "▾" else "▸", color = Palette.Muted, fontSize = 13.sp)
            }
            if (open) Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                a.steps.forEach { st ->
                    Column {
                        Text(st.title + if (st.failed > 0 && st.kind != com.billmongan.herdrdroid.herdr.AgyMeta.Kind.MORE) " · failed" else "",
                            color = if (st.failed > 0) Palette.OrangeInk else Palette.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        st.detail?.let { Text(it, color = Palette.Muted, fontSize = 12.sp, fontFamily = Fonts.Mono) }
                    }
                }
            }
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

/** The keys a question-box choice sends, as its button says them: "sends ↓ ↓ Enter", "sends Esc". */
internal fun keysLabel(nav: List<String>?, c: com.billmongan.herdrdroid.herdr.Choice): String {
    if (c.keys.startsWith("grok-privacy:")) return "types /privacy in Grok and picks it there"
    if (nav == null) return if (c.pressEnter) "sends ${c.keys} + Enter" else "sends ${c.keys}"
    val names = nav.map { when (it) { "up" -> "↑"; "down" -> "↓"; "left" -> "←"; "right" -> "→"; "enter" -> "Enter"; "esc" -> "Esc"; else -> it } }
    return "sends " + names.joinToString(" ")
}

/** The header pill; a tap opens what runs under the pane. */
@Composable
internal fun RunningPillChip(vm: AppViewModel, paneId: String, pill: RunningPill) {
    Text(
        pill.label,
        color = if (pill.active) Palette.Blue else Palette.Muted,
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (pill.active) Palette.BlueSoft else Palette.Line)
            .clickable(onClickLabel = "Show what's running", role = androidx.compose.ui.semantics.Role.Button) { vm.screen = Screen.Work(paneId) }
            .semantics { contentDescription = pill.description }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** The header pill for what runs under an agent: its text, its spoken description, and whether anything is active. */
internal data class RunningPill(val label: String, val description: String, val active: Boolean)

/**
 * Shells (processes the agent started, or its running background tasks when those are more) and
 * running subagents. With none, a muted count of finished work so its output stays reachable; null
 * when there is nothing to show.
 */
internal fun runningPill(w: com.billmongan.herdrdroid.herdr.WorkInfo, agent: String?, endedProcs: Int): RunningPill? {
    val subs = w.activeSubagents
    val shells = maxOf(w.activeProcs(agent).size, w.runningTasks)
    if (subs > 0 || shells > 0) {
        fun n(k: Int, one: String) = "$k $one" + if (k == 1) "" else "s"
        return RunningPill("$shells sh - $subs sub", n(shells, "shell") + " and " + n(subs, "subagent") + " running", true)
    }
    val done = w.subagents.count { !it.active } + w.tasks.count { !it.running } + endedProcs
    return if (done > 0) RunningPill("$done done", "Nothing running, $done finished", false) else null
}

/** 2.2.7: the message box's text as a property backed by the view model's draft for this pane. */
internal class ComposerDraftBox(private val vm: AppViewModel, private val paneId: String) {
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String = vm.composerDraft(paneId)
    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) = vm.setComposerDraft(paneId, value)
}

/** 2.2.8: the note when a message waits for an open question (never typed into the dialog). */
const val QUEUED_FOR_QUESTION = "The agent is waiting on a question. Your message is queued here and goes once the question is answered or gone."
