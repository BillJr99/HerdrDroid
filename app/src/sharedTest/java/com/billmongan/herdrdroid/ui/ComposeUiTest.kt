package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.QuotaItem
import com.billmongan.herdrdroid.herdr.QuotaReading
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.UserItem
import com.billmongan.herdrdroid.transcript.rowsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests. They run on the JVM under Robolectric with the unit tests, and the same file
 * runs on an emulator in CI (src/sharedTest is in both source sets).
 */
@RunWith(AndroidJUnit4::class)
class ComposeUiTest {
    @get:Rule val rule = createComposeRule()

    private fun pane(status: Status, agent: String = "claude") =
        Pane("w1:p1", "w1:t1", "w1", agent, status, "", "/home/user/agents/claude")

    @Test fun usageDismissButtonDispatchesOnlyAnExplicitTapAndDisablesWhilePending() {
        val pending = androidx.compose.runtime.mutableStateOf(false)
        var clicks = 0
        rule.setContent { UsageOverlayBanner(pending.value) { clicks++; pending.value = true } }
        rule.onNodeWithText("Usage panel is open").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, clicks) }
        rule.onNodeWithText("Dismiss usage").performClick()
        rule.onNodeWithText("Dismissing…").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun steerSendsAndOnlyArrowOpensQueueMenuWithoutTakingComposerFocus() {
        var sent = 0
        var queued = 0
        rule.setContent {
            ChatComposer(value = "sample", onValueChange = {}, placeholder = "Message", canSend = true,
                onSend = { sent++ }, working = true, steerLabel = "Steer", onQueueWhenIdle = { queued++ })
        }
        val composer = rule.onNode(hasSetTextAction())
        composer.assertIsNotFocused()
        rule.onNodeWithText("Steer").performClick()
        composer.assertIsNotFocused()
        rule.onNodeWithText("Send when idle").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, sent) }

        composer.performClick().assertIsFocused()
        rule.onNodeWithContentDescription("Send options").performClick()
        composer.assertIsFocused()
        rule.onNodeWithText("Send when idle").performClick()
        composer.assertIsFocused()
        rule.runOnIdle {
            assertEquals(1, queued)
            assertEquals(1, sent)
        }
        rule.onNodeWithText("Steer").performClick()
        composer.assertIsFocused()
        rule.runOnIdle { assertEquals(2, sent) }
    }

    @Test fun sessionDrawerReturnsToTopWhenReopened() {
        val open = androidx.compose.runtime.mutableStateOf(true)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.billmongan.herdrdroid.AppViewModel(app)
        rule.setContent {
            Box(Modifier.height(240.dp)) { SessionDrawer(vm, opening = open.value) }
        }
        val list = rule.onNode(hasScrollToIndexAction())
        fun position(): Float {
            val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            return rule.runOnIdle { range.value() }
        }

        list.performScrollToIndex(2)
        assertTrue(position() > 0f)
        rule.runOnIdle { open.value = false }
        assertTrue(position() > 0f)
        rule.runOnIdle { open.value = true }
        assertEquals(0f, position(), 0f)
    }

    @Test fun removingOneOfflineMessageDropsOnlyItsQueuedSend() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.billmongan.herdrdroid.AppViewModel(app)
        val target = pane(Status.WORKING)
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                vm.removableSendsFor(target.id).forEach { pending ->
                    PendingMessageRow(
                        title = "Waiting to reconnect",
                        text = pending.text,
                        onRemove = { vm.dismissPending(pending.id) },
                        sendLabel = "Resend",
                        onSend = null,
                    )
                }
            }
        }
        rule.runOnIdle {
            vm.sendMessage(target, "First message")
            vm.sendMessage(target, "Second message")
        }
        rule.onAllNodesWithText("Remove").assertCountEquals(2)
        rule.onAllNodesWithText("Remove")[0].performClick()
        rule.onNodeWithText("First message").assertDoesNotExist()
        rule.onNodeWithText("Second message").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, vm.queuedCount)
            assertEquals(listOf("Second message"), vm.removableSendsFor(target.id).map { it.text })
        }
    }

    @Test fun unsentMessageLongPressOffersForceSendAndRemove() {
        var forced = 0
        var removed = 0
        rule.setContent {
            PendingMessageRow(
                title = "Waiting for the input line",
                text = "A pending message",
                onRemove = { removed++ },
                sendLabel = null,
                onSend = { forced++ },
                forceSendMenu = true,
            )
        }
        val message = rule.onNodeWithText("A pending message")
        message.performTouchInput { longClick() }
        rule.onNodeWithText("Force send immediately").performClick()
        rule.runOnIdle { assertEquals(1, forced) }
        message.performTouchInput { longClick() }
        rule.onAllNodesWithText("Remove").assertCountEquals(2)[1].performClick()
        rule.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun heldMessageShowsForceAndRemoveOnANarrowPhone() {
        rule.setContent {
            Box(Modifier.width(240.dp)) {
                PendingMessageRow(
                    title = "Waiting for the input line",
                    text = "A held message",
                    onRemove = {},
                    sendLabel = "Force send immediately",
                    onSend = {},
                    forceSendMenu = true,
                )
            }
        }
        rule.onNodeWithText("Force send immediately").assertIsDisplayed()
        rule.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test fun paneWaitingForAnAnswerBlocksImmediateQueueDelivery() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.billmongan.herdrdroid.AppViewModel(app)
        assertTrue(vm.hasPendingQuestion(pane(Status.NEEDS_YOU)))
        assertEquals(false, vm.hasPendingQuestion(pane(Status.IDLE)))
        val openChoice = """
            ┃ Web Search
            ┃ Allow OpenCode to search the web?
            ┃ 1. Allow search via Example
            ┃ 2. Choose another provider
            ┃ 3. Disable web search
            ┃ ↑↓ select  enter submit  esc dismiss
        """.trimIndent()
        assertTrue(vm.blocksNormalSend(pane(Status.IDLE, "opencode"), openChoice))
    }

    @Test fun questionChoiceShowsSendingAndDisablesChoices() {
        val sending = androidx.compose.runtime.mutableStateOf(false)
        var sent = 0
        val question = Prompt("Which color?", listOf(Choice("Blue", "1", true), Choice("Green", "2", true)))
        rule.setContent {
            PromptCard(question, "Claude", onChoice = {
                sent++
                sending.value = true
            }, onKey = {}, onTypeAnswer = {}, sending = sending.value)
        }
        rule.onNodeWithText("Blue").performClick()
        rule.onNodeWithText("Checking and sending…").assertIsDisplayed()
        rule.onNodeWithText("Green").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, sent) }
    }

    @Test fun statusPillsShowEachState() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                Status.entries.forEach { StatusPill(it) }
            }
        }
        listOf("Needs you", "Working", "Done", "Idle", "Shell").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun quotaChipsShowEveryLimitWithItsPercentAndReset() {
        val r = QuotaReading(0, listOf(
            QuotaItem("Current session", 12.0, "3pm"),
            QuotaItem("Current week (all models)", 24.0, "Nov 19, 7:59am"),
        ))
        rule.setContent { QuotaChips(r) }
        rule.onNodeWithText("24%").assertIsDisplayed()
        rule.onNodeWithText("12%").assertIsDisplayed()
        rule.onNodeWithText(com.billmongan.herdrdroid.herdr.shortReset("Nov 19, 7:59am")).assertIsDisplayed()
    }

    @Test fun paneCardShowsTitleModelAndUsageAndOpens() {
        var opened = 0
        val q = QuotaReading(0, listOf(QuotaItem("Current week (all models)", 24.0, null)))
        rule.setContent {
            PaneCard(pane(Status.WORKING), preview = "", model = "Opus 5.5 · high", quota = q, checking = false) { opened++ }
        }
        rule.onNodeWithText("Working").assertIsDisplayed()
        rule.onNodeWithText("Opus 5.5 · high").assertIsDisplayed()
        rule.onNodeWithText("24%").assertIsDisplayed()
        rule.onNodeWithText("Opus 5.5 · high").performClick()
        assertEquals(1, opened)
    }

    /**
     * 1.2.8: the question sits in the conversation, scrolling with it; the orange card holds only
     * the answers (Bill: "the question boxes show too much text").
     */
    @Test fun questionInTheChatAnswersInTheCard() {
        val long = "Which of these should the deploy use? " + "It decides where the build runs and what it costs. ".repeat(6)
        val p = Prompt(long.trim(), listOf(Choice("Staging", "1", false), Choice("Production", "2", false), Choice("Skip it", "3", false)), selected = "1")
        val picked = mutableListOf<String>()
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                PromptQuestion(p, agent = "Claude")
                PromptCard(p, agent = "Claude", onChoice = { picked += it.keys }, onKey = {}, onTypeAnswer = {})
            }
        }
        // The question appears once, as the agent's message (1.2.9: heading, then the explanation),
        // and the card says only "Your answer".
        rule.onAllNodesWithText("Which of these should the deploy use?").assertCountEquals(1)
        rule.onAllNodesWithText(long.substringAfter("? ").trim()).assertCountEquals(1)
        rule.onNodeWithText("Claude asks").assertExists()
        rule.onNodeWithText("Your answer").assertExists()
        rule.onNodeWithText("Production").performScrollTo().performClick()
        assertEquals(listOf("2"), picked)
    }

    /** 1.2.9: Claude's tab bar becomes "Question 1 of 4" and chips; a command gets its own block. */
    @Test fun multiQuestionStepsAndCommandBlock() {
        val p = Prompt("Close residue 537? Both were repaired.", listOf(Choice("Close it", "1", false), Choice("Keep it", "2", false)), selected = "1",
            parts = com.billmongan.herdrdroid.herdr.questionParts(listOf(
                com.billmongan.herdrdroid.herdr.QLine("←  ☒ Close 537  ☐ Stop hook  ☐ Revise 329  ☐ Markup line  ✔ Submit  →"),
                com.billmongan.herdrdroid.herdr.QLine("Close residue 537? Both were repaired.", gapBefore = true),
                com.billmongan.herdrdroid.herdr.QLine("$ git status", gapBefore = true))))
        rule.setContent { PromptQuestion(p, agent = "Claude") }
        rule.onNodeWithText("Question 2 of 4").assertExists()
        rule.onNodeWithText("✓ Close 537").assertExists()
        rule.onNodeWithText("Stop hook").assertExists()
        rule.onNodeWithText("Submit").assertExists()
        rule.onNodeWithText("Close residue 537?").assertExists()
        rule.onNodeWithText("Both were repaired.").assertExists()
        rule.onNodeWithText("git status").assertExists()
        rule.onNodeWithText("←", substring = true).assertDoesNotExist()
        rule.onNodeWithText("☐", substring = true).assertDoesNotExist()
    }

    /**
     * 1.4.8 (Bill: "the show terminal button doesn't actually do anything", "only broken if there's a
     * question card"): with a card open, Show terminal switches to the terminal and the card becomes a
     * bar that leads back. Muse's card and Claude's.
     */
    @Test fun showTerminalWithMusesCardOpen() = showTerminal("Muse",
        Prompt("Would you like to run the following command?", listOf(Choice("Allow this stage once (y)", "1", false), Choice("Abort the entire command (esc)", "2", false)), selected = "1"))

    @Test fun showTerminalWithClaudesCardOpen() = showTerminal("Claude",
        Prompt("Do you want to proceed?", listOf(Choice("Yes", "1", false), Choice("No, and tell Claude what to do differently (esc)", "2", false)), selected = "1"))

    private fun showTerminal(agent: String, p: Prompt) {
        val first = p.choices.first().label
        val full = androidx.compose.runtime.mutableStateOf(false)
        val shown = androidx.compose.runtime.mutableStateOf("")
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                if (full.value) TerminalView(shown.value, if (shown.value.isEmpty()) "timed out" else null, 12.5f)
                CardOrTerminalBar(full.value, agent, onBackToQuestion = { full.value = false }) {
                    PromptCard(p, agent = agent, onChoice = {}, onKey = {}, onTypeAnswer = {}, onShowTerminal = { full.value = true })
                }
            }
        }
        for (screen in listOf("demo screen line one\n⟩ 1. $first", "")) {
            shown.value = screen
            rule.waitForIdle()
            rule.onNodeWithText(first).assertExists()
            rule.onNodeWithText("Show terminal").performScrollTo().performClick()
            rule.waitForIdle()
            assertTrue(full.value)
            rule.onNodeWithText("Terminal").assertExists()
            if (screen.isEmpty()) rule.onNodeWithText("Nothing has been read from this pane yet (timed out). The app keeps trying.").assertExists()
            else rule.onNodeWithText(screen).assertExists()
            // The card is a one-line bar now, with the way back.
            rule.onNodeWithText(first).assertDoesNotExist()
            rule.onNodeWithText("$agent is waiting for your answer").assertExists()
            rule.onNodeWithText("Back to the question").performClick()
            rule.waitForIdle()
            rule.onNodeWithText(first).assertExists()
            rule.onNodeWithText("Terminal").assertDoesNotExist()
        }
    }

    @Test fun cardAloneHasNoQuestionBody() {
        val p = Prompt("Do you want to proceed with this very particular question?", listOf(Choice("Yes", "1", false), Choice("No", "2", false)), selected = "1")
        rule.setContent { PromptCard(p, agent = "Codex", onChoice = {}, onKey = {}, onTypeAnswer = {}) }
        rule.onNodeWithText("Do you want to proceed with this very particular question?").assertDoesNotExist()
        rule.onNodeWithText("Yes").assertExists()
    }

    /**
     * 1.2.8 (Bill: "it snap scrolls when I try to scroll up to read back to the bottom"): once you
     * scroll up, steady new output and a question card at the end must not move the chat.
     */
    @Test fun scrolledUpChatHoldsWhileOutputContinues() {
        val items = androidx.compose.runtime.mutableStateOf((1..40).flatMap { listOf(UserItem("u$it", "Question $it"), TextItem("t$it", "Answer $it")) })
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        val p = Prompt("Run this command?", listOf(Choice("Yes", "1", false), Choice("No", "2", false)), selected = "1")
        rule.setContent {
            state = rememberLazyListState()
            Box(Modifier.width(380.dp).height(640.dp)) {
                TranscriptChat(
                    rows = rowsOf(items.value), working = true, workingLabel = "Working", agentName = "Claude",
                    pending = emptyList(), more = false, size = 12.5f, family = FontFamily.Default,
                    actions = ChatActions(onQuote = {}, onStop = null),
                    footer = {
                        androidx.compose.foundation.layout.Column {
                            PromptQuestion(p, agent = "Claude")
                            PromptCard(p, agent = "Claude", onChoice = {}, onKey = {}, onTypeAnswer = {})
                        }
                    },
                    state = state, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        // Scroll up twice, as a reader would.
        rule.onRoot().performTouchInput { swipeDown(durationMillis = 300) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { swipeDown(durationMillis = 300) }
        rule.waitForIdle()
        val held = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        // Output keeps arriving: ten updates, each with a new exchange.
        for (n in 41..50) {
            items.value = items.value + listOf(UserItem("u$n", "Question $n"), TextItem("t$n", "Answer $n"))
            rule.waitForIdle()
            assertEquals("the chat moved on update $n", held, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        }
        rule.onNodeWithContentDescription("Jump to the latest").assertIsDisplayed()
    }

    @Test fun pullingDownRefreshes() {
        var refreshed = 0
        rule.setContent {
            PullRefresh({ refreshed++ }, Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) { items(30) { Text("Row $it", Modifier.height(40.dp)) } }
            }
        }
        rule.onRoot().performTouchInput { swipeDown(startY = top + 5f, endY = bottom - 5f, durationMillis = 600) }
        rule.waitForIdle()
        assertTrue("pull to refresh ran", refreshed >= 1)
    }

    @Test fun openingAChatLandsAtTheBottom() {
        val items = (1..60).flatMap { listOf(UserItem("u$it", "Question $it"), TextItem("t$it", "Answer $it")) }
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                TranscriptChat(
                    rows = rowsOf(items), working = false, workingLabel = "", agentName = "Claude",
                    pending = emptyList(), more = false, size = 12.5f, family = FontFamily.Default,
                    actions = ChatActions(onQuote = {}, onStop = null),
                    state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Answer 60").assertIsDisplayed()
        rule.onNodeWithText("Question 1").assertDoesNotExist()
    }

    @Test fun chatStaysAtTheBottomWhenMoreArrives() {
        val items = androidx.compose.runtime.mutableStateOf((1..30).flatMap { listOf(UserItem("u$it", "Question $it"), TextItem("t$it", "Answer $it")) })
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                TranscriptChat(
                    rows = rowsOf(items.value), working = false, workingLabel = "", agentName = "Claude",
                    pending = emptyList(), more = false, size = 12.5f, family = FontFamily.Default,
                    actions = ChatActions(onQuote = {}, onStop = null),
                    state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        // A fresh read adds a long stretch at once, as after the cached chat on opening.
        items.value = items.value + (31..70).flatMap { listOf(UserItem("u$it", "Question $it"), TextItem("t$it", "Answer $it\n" + "more text\n".repeat(4))) }
        rule.waitForIdle()
        rule.onNodeWithText("Answer 70", substring = true).assertIsDisplayed()
    }

    /**
     * The jump-to-latest pill ("↓ N new" / "↓ Latest") shows only away from the bottom, and its
     * count clears as soon as the list is back at the bottom (Bill, 2026-11-13: "it says 4 new or
     * latest with a down arrow even though I am at the bottom").
     */
    @Test fun latestPillOnlyAwayFromTheBottom() {
        val items = androidx.compose.runtime.mutableStateOf((1..40).flatMap { listOf(UserItem("u$it", "Question $it"), TextItem("t$it", "Answer $it")) })
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        rule.setContent {
            state = rememberLazyListState()
            Box(Modifier.width(380.dp).height(640.dp)) {
                TranscriptChat(
                    rows = rowsOf(items.value), working = false, workingLabel = "", agentName = "Claude",
                    pending = emptyList(), more = false, size = 12.5f, family = FontFamily.Default,
                    actions = ChatActions(onQuote = {}, onStop = null),
                    state = state, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val pill = "Jump to the latest"
        rule.waitForIdle()
        rule.onNodeWithContentDescription(pill).assertDoesNotExist()
        // New messages while at the bottom: still no pill.
        items.value = items.value + listOf(UserItem("u41", "Question 41"), TextItem("t41", "Answer 41"))
        rule.waitForIdle()
        rule.onNodeWithText("Answer 41").assertIsDisplayed()
        rule.onNodeWithContentDescription(pill).assertDoesNotExist()
        // Drag up: the pill shows; new messages are counted.
        rule.onRoot().performTouchInput { swipeDown(durationMillis = 300) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(pill).assertIsDisplayed()
        items.value = items.value + listOf(UserItem("u42", "Question 42"), TextItem("t42", "Answer 42"))
        rule.waitForIdle()
        rule.onNodeWithText("new", substring = true).assertIsDisplayed()
        // Back at the bottom: the pill goes and the count clears.
        rule.runOnIdle { kotlinx.coroutines.runBlocking { state.scrollToItem(state.layoutInfo.totalItemsCount - 1) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(pill).assertDoesNotExist()
        // Up again: "Latest", not the old count.
        rule.onRoot().performTouchInput { swipeDown(durationMillis = 300) }
        rule.waitForIdle()
        rule.onNodeWithText("Latest", substring = true).assertIsDisplayed()
        rule.onNodeWithText("new", substring = true).assertDoesNotExist()
    }

    /** A short label leaves room: the reset time must not be cut with "…". */
    @Test fun quotaResetTimeIsNotCutWhenThereIsRoom() {
        // An unreadable reset is shown as written, so this one stays long.
        val long = "after the weekly cycle ends"
        val r = QuotaReading(0, listOf(QuotaItem("Session", 12.0, long)))
        rule.setContent { Box(Modifier.width(380.dp)) { QuotaChips(r) } }
        rule.onNodeWithText(long).assertIsDisplayed()
        assertTrue("reset time was cut", !overflows(long))
        assertTrue("label was cut", !overflows("Session"))
    }

    private fun overflows(text: String): Boolean {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText(text).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().hasVisualOverflow
    }

    @Test fun compactComposerKeepsItsToolsInOneMenu() {
        var picked = ""
        rule.setContent {
            Box(Modifier.width(380.dp)) {
                ChatComposer(
                    value = "", onValueChange = {}, placeholder = "Message Claude", canSend = false, onSend = {},
                    compact = true, menuItems = listOf("Snippets" to { picked = "Snippets" }, "Show keys" to { picked = "Keys" }),
                )
            }
        }
        rule.onNodeWithText("Snippets").assertDoesNotExist()
        rule.onNodeWithText("🎙").assertIsDisplayed()
        rule.onNodeWithText("⋯").performClick()
        rule.onNodeWithText("Show keys").performClick()
        assertEquals("Keys", picked)
    }

    @Test fun incompleteApprovalCardOnlyOffersTheTerminal() {
        var opened = false
        val prompt = Prompt("Review the full command", emptyList(),
            region = listOf("1. Allow once"), terminalReview = true)
        rule.setContent {
            PromptCard(prompt, "Hermes", onChoice = { error("Sent a hidden choice") },
                onKey = { error("Synthesized a key") }, onTypeAnswer = { error("Opened a typed answer") },
                onShowTerminal = { opened = true })
        }
        listOf("1", "Enter", "Esc", "Ctrl+C", "Type a different answer").forEach {
            rule.onNodeWithText(it).assertDoesNotExist()
        }
        rule.onNodeWithText("Show terminal").performClick()
        rule.runOnIdle { assertTrue(opened) }
    }

    @Test fun voiceSettingsRequireOptInAndStartAndStopOnlyOnExplicitTap() {
        val enabled = androidx.compose.runtime.mutableStateOf(false)
        val active = androidx.compose.runtime.mutableStateOf(false)
        val locked = androidx.compose.runtime.mutableStateOf(false)
        val route = androidx.compose.runtime.mutableStateOf(com.billmongan.herdrdroid.voice.VoiceRoute.PHONE)
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                VoiceSettings(com.billmongan.herdrdroid.voice.VoiceUi(enabled.value, { enabled.value = it },
                    { active.value = true }, { active.value = false }, {}, route.value, { route.value = it }), active.value, locked.value)
            }
        }
        rule.onNodeWithText("Start hands-free").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Voice Control").performClick()
        rule.runOnIdle { assertTrue(!active.value) }
        rule.onNodeWithText("Start hands-free").performScrollTo().performClick()
        rule.onNodeWithText("Stop hands-free").performScrollTo().performClick()
        rule.runOnIdle { locked.value = true }
        rule.onNodeWithText("Start hands-free").assertIsNotEnabled()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            rule.onNodeWithText("Bluetooth speakers and mic").performScrollTo().performClick()
            rule.runOnIdle { assertEquals(com.billmongan.herdrdroid.voice.VoiceRoute.BLUETOOTH, route.value) }
        }
        rule.onNodeWithText("Confirm message").performScrollTo().assertIsDisplayed()
    }

    @Test fun agentMenusKeepComposerAndDraftWithoutASeparateReadingView() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = rule.runOnUiThread { com.billmongan.herdrdroid.AppViewModel(app) }
        rule.setContent { AgentScreen(vm, "fixture:pane") }
        rule.onNode(hasSetTextAction()).performTextInput("Synthetic draft")
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Reading mode").assertDoesNotExist()
        rule.onNodeWithText("Leave reading mode").assertDoesNotExist()
        rule.onNodeWithText("Running underneath").assertIsDisplayed()
        rule.onNodeWithText("Running underneath").performClick()
        rule.onNodeWithText("⋯").performClick()
        rule.onNodeWithText("Reading mode").assertDoesNotExist()
        rule.onNodeWithText("Snippets").assertIsDisplayed()
        rule.onNodeWithText("Snippets").performClick()
        rule.onNodeWithText("Synthetic draft").assertExists()
    }

    @Test fun grokQueueDoesNotSupplyMissingQuestionOrChoices() {
        // A blocked status by itself doesn't supply a question. Deliberately make a queued
        // message look like a menu; the queued text is never a question source.
        grokChatWithQueue("│ ❯ │\nShift+Tab:mode")
        rule.onNodeWithText("Queued in Grok", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Grok asks").assertDoesNotExist()
        rule.onNodeWithText("Your answer").assertDoesNotExist()
        rule.onAllNodesWithText("2 (●) Second draft option", substring = true).assertCountEquals(1)
    }

    @Test fun grokQuestionAndChoicesAreTogetherBeforeTheQueue() {
        val keys = mutableListOf<String>()
        grokChatWithQueue("""
            ┃  ?  Which format should the report use?
            ┃  1 (●) Plain text
            ┃  2 (○) Markdown
            ┃
            1/2:answer  │  ←/→:question  │  z:type  │  Shift+X:dismiss  │  Esc:scrollback
        """.trimIndent()) { keys.addAll(it) }
        val question = rule.onNodeWithText("Which format should the report use?").assertExists()
        val choice = rule.onNodeWithText("Markdown").assertExists()
        val queuedFirst = rule.onNodeWithText("First queued message").assertExists()
        val queuedSecond = rule.onNodeWithText("Second draft option", substring = true).assertExists()
        fun top(node: androidx.compose.ui.test.SemanticsNodeInteraction) = node.fetchSemanticsNode().positionInRoot.y
        assertTrue(top(question) < top(choice))
        assertTrue("Grok's choices should not appear under the queued messages", top(choice) < top(queuedFirst))
        assertTrue(top(queuedFirst) < top(queuedSecond))
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        question.assertIsDisplayed()
        choice.assertIsDisplayed().performClick()
        assertEquals(listOf("down", "enter"), keys)
    }

    private fun grokChatWithQueue(screen: String, onKeys: (List<String>) -> Unit = {}) {
        val prompt = com.billmongan.herdrdroid.herdr.detectPrompt(screen, blocked = true, agent = "grok")
        rule.setContent {
            Box(Modifier.width(380.dp).height(700.dp)) {
                TranscriptChat(
                    rows = rowsOf(listOf(UserItem("delivered", "Write a report"))),
                    working = false, workingLabel = "", agentName = "Grok",
                    pending = listOf("First queued message", "2 (●) Second draft option"),
                    more = false, size = 12.5f, family = FontFamily.Default,
                    actions = ChatActions(onQuote = {}, onStop = null, queuedNote = "Queued in Grok · not yet in the session log"),
                    footer = {
                        prompt?.let { p ->
                            PromptQuestion(p, agent = "Grok")
                            PromptCard(p, agent = "Grok", onChoice = { onKeys(requireNotNull(com.billmongan.herdrdroid.herdr.menuKeys(p, it))) },
                                onKey = {}, onTypeAnswer = {})
                        }
                    },
                    state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test fun aReplyOpensFullScreen() {
        var opened: String? = null
        val items = listOf(UserItem("u1", "Question"), TextItem("t1", "The whole answer"))
        rule.setContent {
            // A narrow phone: every reply button must show without scrolling sideways.
            Box(Modifier.width(360.dp).height(600.dp)) {
                TranscriptChat(
                    rows = rowsOf(items), working = false, workingLabel = "", agentName = "Claude",
                    pending = emptyList(), more = false, size = 12.5f, family = FontFamily.Default,
                    actions = ChatActions(onQuote = {}, onStop = null, onForward = {}, onOpen = { opened = it }, focusKey = "t1"),
                    state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                )
            }
        }
        listOf("Copy", "Share", "Quote", "Read").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Forward").assertDoesNotExist()
        rule.onNodeWithText("Expand").performClick()
        assertEquals("The whole answer", opened)
    }

    /** Closing a working session asks twice; the folder is never mentioned as deleted. */
    @Test fun closingABusySessionAsksTwice() {
        val server = com.billmongan.herdrdroid.store.Server(name = "devbox2", host = "h", port = 22, user = "u", herdrPath = "herdr")
        val p = Pane("w1:p2", "w1:t2", "w1", "opencode", Status.WORKING, "", "/home/user/cs357", tabLabel = "cs357-tutorial")
        var closed = false
        rule.setContent {
            CloseSessionDialog(com.billmongan.herdrdroid.AppViewModel.Session(server, p, Status.WORKING, false, false), onDismiss = {}) { closed = true }
        }
        rule.onNodeWithText("Files in /home/user/cs357 are not touched", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Close session").performClick()
        assertEquals(false, closed)
        rule.onNodeWithText("Close it anyway").performClick()
        assertEquals(true, closed)
    }

    @Test fun aSessionIsNamedByItsTabWhenThatSaysMore() {
        assertEquals("OpenCode · cs357-tutorial", sessionName(Pane("1", "t", "w", "opencode", Status.IDLE, "", "/x", tabLabel = "cs357-tutorial")))
        assertEquals("Claude", sessionName(Pane("1", "t", "w", "claude", Status.IDLE, "", "/x", tabLabel = "claude")))
    }
}
