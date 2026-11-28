package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.codexUnansweredConfirmation
import com.billmongan.herdrdroid.herdr.codexWrappedQuestion
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.herdr.hermesClarify
import com.billmongan.herdrdroid.notify.AppBus
import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.transcript.QuestionLog
import com.billmongan.herdrdroid.voice.VoiceControls
import com.billmongan.herdrdroid.voice.ConfirmationGrammar
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SuccessiveCardAnswerTest {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    @Test fun livePiQuestionOverridesWorkingUntilDismissed() = fixture("pi", piQuestion) {
        status(Status.WORKING)
        readSelectedPane()
        assertEquals("Trust", displayed().choices.first().label)
        assertEquals(Status.WORKING, vm.pane("p1")?.status)
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        assertTrue(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
        assertEquals(Status.WORKING, vm.shownStatus(checkNotNull(vm.pane("p1")), "other-server"))
        assertEquals(1, guardedVisibleReads)

        val card = displayed()
        val target = checkNotNull(vm.paneTextTarget)
        recentResult = ExecResult(0, piQuestion, "")
        frame = "Continuing after the question."
        readSelectedPane()
        assertEquals(Status.WORKING, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
        answer(card, target)
        assertTrue(sent.isEmpty())
    }

    @Test fun piQuestionInVisibleButNotRecentOutputOverridesWorking() = fixture("pi", piQuestion) {
        status(Status.WORKING)
        recentResult = ExecResult(0, "Preparing a question", "")
        readSelectedPane()
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        assertEquals("Trust", displayed().choices.first().label)
        assertEquals(1, guardedVisibleReads)
        assertTrue(sent.isEmpty())
    }

    @Test fun incompleteOrScrolledPiQuestionDoesNotOverrideWorking() = fixture("pi", piQuestion) {
        status(Status.WORKING)
        recentResult = ExecResult(0, piQuestion, "")
        for (visible in listOf("", "↑↓ navigate  enter select  escape/ctrl+c cancel", "$piQuestion\nContinuing after the question.")) {
            frame = visible
            readSelectedPane()
            assertEquals(Status.WORKING, vm.shownStatus(checkNotNull(vm.pane("p1"))))
            assertNull(vm.paneTextTarget)
        }
        frame = piQuestion
        liveScrollOffset = 3
        readSelectedPane()
        assertEquals(Status.WORKING, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        assertNull(vm.paneTextTarget)
        assertTrue(sent.isEmpty())
    }

    @Test fun piQuestionStatusDoesNotFollowAReplacedOccupant() = fixture("pi", piQuestion) {
        status(Status.WORKING)
        readSelectedPane()
        val card = displayed()
        val target = checkNotNull(vm.paneTextTarget)
        terminal = "replacement"
        status(Status.WORKING)
        assertEquals(Status.WORKING, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        answer(card, target)
        assertTrue(sent.isEmpty())
    }

    @Test fun hermesClarifyFromVisibleViewportSendsOnlyOneDigitForLiveChoice() =
        fixture("hermes", hermesClarify) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing a question", "")
            readSelectedPane()
            assertEquals(1, guardedVisibleReads)
            val card = displayed()
            val target = checkNotNull(vm.paneTextTarget)
            assertTrue(sent.isEmpty())
            repeat(2) { answer(card, target, index = 1) }
            assertEquals(listOf("2"), sent)
        }

    /** 2.2.7 (Bill, "check right before send"): the screen changed after the card's last read: nothing typed, and a new tap can go. */
    @Test fun aScreenChangedBeforeTheKeysIsNotSentAndCanBeAnsweredAgain() =
        fixture("hermes", hermesClarify) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing a question", "")
            readSelectedPane()
            val card = displayed()
            beforeCheck = { ansiOverride = frame + "\nA new line under the question" }
            answer(card, checkNotNull(vm.paneTextTarget), index = 1)
            assertTrue(sent.isEmpty())
            assertEquals(1, cardChecksRefused)
            // 2.2.8: never "the question changed": the card shows what is on screen now, nothing typed.
            assertEquals(CARD_UPDATED_NOTE, vm.notice)
            beforeCheck = null
            ansiOverride = null
            readSelectedPane()
            answer(card, checkNotNull(vm.paneTextTarget), index = 1)
            assertEquals("not held as already answered", listOf("2"), sent)
        }

    /**
     * 2.2.7 (Bill: "silently auto fail over to it with a notification toast"): no python3 on the
     * computer: the answer is checked by the shell version in the same tap, the toast says so
     * once, and the next answer goes straight to the shell check with no toast.
     */
    @Test fun withoutPython3TheAnswerFallsOverToTheShellCheckWithOneToast() =
        fixture("hermes", hermesClarify) {
            pythonMissing = true
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing a question", "")
            readSelectedPane()
            val card = displayed()
            answer(card, checkNotNull(vm.paneTextTarget), index = 1)
            assertEquals(listOf("2"), sent)
            assertEquals(1 to 1, pythonChecks to shellChecks)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("python3 isn't on Fixture, so answers are checked with the shell version.",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertEquals(1, org.robolectric.shadows.ShadowToast.shownToastCount())
            // A new question; its answer goes straight to the shell check, and nothing is said again.
            frame = hermesClarify.replace("Which database", "Which cache")
            readSelectedPane()
            val next = displayed()
            sent.clear()
            answer(next, checkNotNull(vm.paneTextTarget), index = 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("1"), sent)
            assertEquals("no Python check this time", 1 to 2, pythonChecks to shellChecks)
            assertEquals(1, org.robolectric.shadows.ShadowToast.shownToastCount())
        }

    /** 2.2.7 (Grok's review): "Switch to Claude" opens the Claude pane, never a saved server whose name only sounds alike. */
    @Test fun aBareAgentNameOpensThePaneNotALookAlikeServer() = fixture("claude", "❯ ") {
        vm.saveServer(Server(id = "fixture-cloud", name = "cloud", host = "127.0.0.1", port = 1, user = "u", herdrPath = "herdr"))
        speak("Switch to Claude")
        assertEquals("Opening Claude on Fixture.", spoken)
        assertEquals("fixture", vm.current?.id)
    }

    private fun grokScreen(name: String) = checkNotNull(javaClass.getResource("/screens/grok/synthetic-2.2.8-approval/$name")) { name }.readText()
    private fun grokTick(s: String, n: Int) = s.replace("1m53s", "1m${53 + n}s").replace("7m33s", "7m${33 + n}s").replace("⇣196k", "⇣${196 + n}k")

    /** 2.2.8 (Bill: "grok has a question but keeps saying it changed and won't send"): its running times tick between the read and the keys. */
    @Test fun grokTimersTickingBetweenTheReadAndTheKeysStillSend() = fixture("grok", grokScreen("approval-whole.ansi")) {
        readSelectedPane()
        val card = displayed()
        assertEquals("Yes, proceed", card.choices[0].label)
        var n = 0
        beforeCheck = { frame = grokTick(frame, ++n) }
        answer(card, index = 1)
        assertEquals(listOf("down enter"), sent)
        assertEquals(0, cardChecksRefused)
    }

    /** 2.2.8 (Bill: "just render the question and then give the buttons"): a changed dialog at the send: nothing typed, the card shows it now. */
    @Test fun aDialogChangedAtTheSendUpdatesTheCardAndSendsNothing() = fixture("grok", grokScreen("approval-whole.ansi")) {
        readSelectedPane()
        val card = displayed()
        beforeCheck = { frame = frame.replace("ls -la build/cache", "ls -la build/other") }
        answer(card, index = 0)
        assertTrue(sent.isEmpty())
        assertEquals(1, cardChecksRefused)
        assertEquals(CARD_UPDATED_NOTE, vm.notice)
        assertEquals("fixture" to "p1", vm.cardUpdatedAt)
        assertTrue("the card shows the new command", displayed().question.contains("ls -la build/other"))
        // Its fresh buttons answer it.
        beforeCheck = null
        answer(displayed(), index = 0)
        assertEquals(listOf("enter"), sent)
    }

    @Test fun agyLongCommandReplacedAtTheSendIsRefused() = fixture(
        "agy", res("/screens/agy/derived-2.2.8-format/long-command-approval.txt"),
    ) {
        readSelectedPane()
        val card = displayed()
        assertEquals("Yes, run command", card.choices.first().label)
        assertTrue(card.question.contains("JOBS=4"))

        beforeCheck = { frame = frame.replace("JOBS=4", "JOBS=8") }
        answer(card)
        assertEquals(1, cardChecksRefused)
        assertTrue(sent.isEmpty())
        assertEquals(CARD_UPDATED_NOTE, vm.notice)
        assertTrue(displayed().question.contains("JOBS=8"))
    }

    /** 2.2.8: the dialog changed before the tap's read: the card shows it now, nothing typed. */
    @Test fun aDialogChangedBeforeTheTapUpdatesTheCardAndSendsNothing() = fixture("grok", grokScreen("approval-whole.ansi")) {
        readSelectedPane()
        val card = displayed()
        frame = frame.replace("Yes, proceed", "Yes, run it")
        answer(card, index = 0)
        assertTrue(sent.isEmpty())
        assertEquals(CARD_UPDATED_NOTE, vm.notice)
        assertEquals("Yes, run it", displayed().choices[0].label)
    }

    /** 2.2.8: the dialog is gone at the send: nothing typed, and the card goes with it. */
    @Test fun aDialogGoneAtTheSendRemovesTheCard() = fixture("grok", grokScreen("approval-whole.ansi")) {
        readSelectedPane()
        val card = displayed()
        beforeCheck = { frame = "  ~/work/demo\n\n  Listed the build cache.\n" }
        answer(card, index = 0)
        assertTrue(sent.isEmpty())
        assertNull(detectPrompt(vm.paneText, blocked = false, agent = "grok"))
    }

    /** 2.2.8: approve-everything is never one tap, and a clipped command is expanded before it can be answered. */
    @Test fun grokAlwaysApproveIsRefusedAndAClippedCommandExpandsFirst() = fixture("grok", grokScreen("approval-clipped.ansi")) {
        readSelectedPane()
        val card = displayed()
        assertTrue(card.clipped)
        answer(card, index = 0)
        assertEquals(CLIPPED_NOTE, vm.notice)
        answer(card, index = 2)
        // Bill (2026-11-25): the tap asks first; Grok's unnumbered row is one no key reaches, so the
        // dialog only says why, and its Confirm (were it pressed) sends nothing.
        val ask = checkNotNull(vm.approveAllPending).ask
        assertEquals(com.billmongan.herdrdroid.herdr.blockedChoiceReason(card.choices[2]), ask.reason)
        vm.confirmApproveAll()
        drainUntil { !vm.answerInFlight("p1") }
        assertTrue(sent.isEmpty())
        afterSend = grokScreen("approval-whole.ansi")
        vm.answerCardKey(vm.cardTarget("p1"), card, "ctrl+f", "Pressed Ctrl+F")
        drainUntil { !vm.answerInFlight("p1") }
        assertEquals(listOf("ctrl+f"), sent)
        val expanded = displayed()
        assertFalse(expanded.clipped)
        sent.clear()
        afterSend = null
        answer(expanded, index = 2)
        assertTrue("always-approve still not sent", sent.isEmpty())
    }

    private fun res(path: String) = checkNotNull(javaClass.getResource(path)) { path }.readText()
    private val grokScan get() = res("/screens/grok/derived-2.2.8-corpus/001-grok-live-approval-status-above.txt")
    private val grokAlways = "Yes, and don't ask again for anything (always-approve mode)"

    /**
     * Bill (2026-11-25): an approve-from-now-on option asks "Approve everything from now on?" first:
     * Cancel sends nothing, Confirm sends exactly that option's keys, under the usual check.
     */
    @Test fun grokAlwaysApproveAsksFirstThenConfirmSendsItsKeys() = fixture("grok", grokScan) {
        readSelectedPane()
        val card = displayed()
        val i = card.choices.indexOfFirst { it.label == grokAlways }
        assertTrue(i >= 0)
        answer(card, index = i)
        val ask = checkNotNull(vm.approveAllPending).ask
        assertEquals(grokAlways, ask.label)
        assertNull(ask.reason)
        assertEquals(com.billmongan.herdrdroid.herdr.menuKeys(card, card.choices[i]), ask.keys)
        assertTrue(sent.isEmpty())
        vm.cancelApproveAll()
        drainUntil { !vm.answerInFlight("p1") }
        assertNull(vm.approveAllPending)
        assertTrue("cancel sends nothing", sent.isEmpty())
        answer(card, index = i)
        vm.confirmApproveAll()
        drainUntil { !vm.answerInFlight("p1") }
        assertEquals(listOf(checkNotNull(ask.keys).joinToString(" ")), sent)
    }

    @Test fun claudeAndCodexStandingPermissionsAskFirstThenConfirmSendsTheirKeys() {
        for ((agent, path) in listOf("claude" to "/screens/claude/captured-2026-11-13/bash-permission.txt",
                "codex" to "/screens/codex/captured-2026-11-13/run-command.txt")) fixture(agent, res(path)) {
            readSelectedPane()
            val card = displayed()
            val i = card.choices.indexOfFirst { com.billmongan.herdrdroid.herdr.grantsStandingPermission(it.label) }
            assertTrue(agent, i >= 0)
            answer(card, index = i)
            val ask = checkNotNull(vm.approveAllPending) { agent }.ask
            assertNull(agent, ask.reason)
            assertTrue(agent, sent.isEmpty())
            vm.confirmApproveAll()
            drainUntil { !vm.answerInFlight("p1") }
            assertEquals(agent, listOf(checkNotNull(ask.keys).joinToString(" ")), sent)
            // An ordinary option still goes in one tap.
            sent.clear()
            readSelectedPane()
            val again = displayed()
            answer(again, index = 0)
            assertNull(agent, vm.approveAllPending)
        }
    }

    /** Bill (2026-11-25): the label changed between the card and Confirm: the card is redrawn, nothing sent. */
    @Test fun aChangedLabelAtConfirmRedrawsInsteadOfSending() = fixture("grok", grokScan) {
        readSelectedPane()
        val card = displayed()
        answer(card, index = card.choices.indexOfFirst { it.label == grokAlways })
        checkNotNull(vm.approveAllPending)
        frame = frame.replace("Yes, and don't ask again for anything", "Yes, and don't ask again for this folder")
        vm.confirmApproveAll()
        drainUntil { !vm.answerInFlight("p1") }
        assertTrue(sent.isEmpty())
        assertEquals(CARD_UPDATED_NOTE, vm.notice)
    }

    /** Bill (2026-11-25): a cut-off label is never sent from the dialog, which says why. */
    @Test fun aCutOffAlwaysApproveLabelOnlyExplains() = fixture("grok", grokScan.replace(grokAlways, "Yes, and don't ask again f…")) {
        readSelectedPane()
        val card = displayed()
        val i = card.choices.indexOfFirst { it.label.startsWith("Yes, and don't ask again f") }
        assertTrue(card.choices.map { it.label }.toString(), i >= 0)
        answer(card, index = i)
        val ask = checkNotNull(vm.approveAllPending).ask
        assertNotNull(ask.reason)
        assertNull(ask.keys)
        vm.confirmApproveAll()
        drainUntil { !vm.answerInFlight("p1") }
        assertTrue(sent.isEmpty())
    }

    private fun ompScreen(name: String) = checkNotNull(javaClass.getResource("/screens/omp/live-2026-11-24-ask/$name")) { name }.readText()

    /**
     * 2.2.7 (Bill: "not get stuck"): "Other" on Oh My Pi's Ask dialog takes typed text: the highlight
     * moves there (checked), the text is typed and Enter confirms it, each under the check of the read before it.
     */
    @Test fun ompOtherTakesTypedTextThenEnter() = fixture("omp", ompScreen("ask-open-first.ansi")) {
        readSelectedPane()
        val card = displayed()
        val other = card.choices.single { it.label == "Other: type your own" }
        afterSend = ompScreen("ask-open-first.ansi").replace("❯ ◉ Leave", "  ◉ Leave").replace("  ○ Other", "❯ ○ Other")
        var delivered = false
        vm.answerTyped(checkNotNull(vm.paneTextTarget), card, other, "look at the logs first") { delivered = true }
        drainWithTime { !vm.answerInFlight("p1") }
        assertEquals(listOf("down down down", "text:look at the logs first", "enter"), sent)
        assertTrue(delivered)
        assertEquals("the move and the typed answer each went through the check", 2, shellChecks + pythonChecks)
    }

    /** 2.2.7: Submit moves the highlight down, one checked step at a time, and presses Enter only on Submit. */
    @Test fun ompSubmitStepsDownToSubmitThenEnter() = fixture("omp", ompScreen("ask-open-other.ansi")) {
        readSelectedPane()
        val card = displayed()
        val submit = card.choices.single { it.keys == com.billmongan.herdrdroid.herdr.OmpAsk.SUBMIT }
        // Down folds the open question, its selection kept (shortened), and puts the highlight on Submit.
        afterSend = ompScreen("ask-submit.ansi").replace("   Leave as CHECK-F…   List the 7 commit…   The commits are…   ✓ Other: look at the…",
            "   ✓ Leave as CHECK-F…   List the 7 commit…   The commits are…   Other: look at the…")
        vm.answerChoice("p1", card, submit, target = checkNotNull(vm.paneTextTarget))
        drainWithTime { !vm.answerInFlight("p1") }
        assertEquals(listOf("down", "enter"), sent)
    }

    /** Codex's re-review: an answer that changes while the highlight moves to Submit stops the Submit; Enter never goes. */
    @Test fun ompSubmitStopsWhenAnAnswerChangesOnTheWay() = fixture("omp", ompScreen("ask-open-other.ansi")) {
        readSelectedPane()
        val card = displayed()
        val submit = card.choices.single { it.keys == com.billmongan.herdrdroid.herdr.OmpAsk.SUBMIT }
        afterSend = ompScreen("ask-submit.ansi").replace("   Leave as CHECK-F…   List the 7 commit…   The commits are…   ✓ Other: look at the…",
            "   ✓ Leave as CHECK-F…   List the 7 commit…   The commits are…   Other: look at the…")
            .replace("✓ Leave as CHECK-FIRST   Check", "Leave as CHECK-FIRST   ✓ Check")
        vm.answerChoice("p1", card, submit, target = checkNotNull(vm.paneTextTarget))
        drainWithTime { !vm.answerInFlight("p1") }
        assertEquals("only the first Down", listOf("down"), sent)
        assertTrue(vm.notice.orEmpty(), vm.notice.orEmpty().startsWith("Not sent."))
    }

    /** Grok's re-review: OpenCode's own-answer row: the digit, then the text and Enter, each under the check. */
    @Test fun openCodeOwnAnswerGoesThroughTheCheck() = fixture("opencode", nativeQuestion(1)) {
        readSelectedPane()
        val card = displayed()
        val digit = checkNotNull(card.openCodeCustom)
        afterSend = nativeQuestion(1).replace("┃  ↑↓ select  enter submit  esc dismiss", "┃  enter submit  esc close")
        vm.answerTyped(checkNotNull(vm.paneTextTarget), card, Choice("Type your own answer", digit, false), "MariaDB") {}
        drainWithTime { !vm.answerInFlight("p1") }
        assertEquals(listOf(digit, "text:MariaDB", "enter"), sent)
        assertEquals("the digit and the text each went through the check", 2, pythonChecks + shellChecks)
    }

    /** 2.2.7 (Bill: "the stop button in chat view doesn't work"): each agent's own interrupt, once. */
    @Test fun stopSendsEachAgentsInterruptOnce() {
        val want = mapOf(
            "claude" to "esc", "codex" to "esc", "copilot" to "esc", "agy" to "esc", "vibe" to "esc", "pi" to "esc",
            "omp" to "esc", "muse" to "esc", "opencode" to "esc esc", "opencode-work" to "esc esc",
            "grok" to "ctrl+c", "kiro" to "ctrl+c", "hermes" to "ctrl+c",
        )
        for ((agent, keys) in want) fixture(agent, "working on it") {
            status(Status.WORKING)
            vm.stopPane(checkNotNull(vm.pane("p1")))
            drainUntil { sent.isNotEmpty() }
            assertEquals(agent, listOf(keys), sent)
        }
    }

    /**
     * The regression: Stop went through the pane's send queue, so it waited behind other input to the
     * pane and, with the link down, was kept and sent after the reconnect. Now it goes at once, past a
     * held lane and past a screen that changed, and is never kept for later.
     */
    @Test fun stopGoesAtOnceAndIsNeverKeptForLater() = fixture("claude", "working on it") {
        status(Status.WORKING)
        val lanes = AppViewModel::class.java.getDeclaredField("paneDispatch").apply { isAccessible = true }.get(vm)
            as com.billmongan.herdrdroid.herdr.PaneDispatch
        val held = checkNotNull(lanes.tryAcquire("fixture" to "p1"))
        try {
            frame = "a different screen"
            vm.stopPane(checkNotNull(vm.pane("p1")))
            drainUntil { sent.isNotEmpty() }
            assertEquals("past a busy lane and a changed screen", listOf("esc"), sent)
        } finally { held() }
        sent.clear()
        val field = AppViewModel::class.java.getDeclaredField("link\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (field.get(vm) as androidx.compose.runtime.MutableState<Link>).value = Link.RECONNECTING
        vm.stopPane(checkNotNull(vm.pane("p1")))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.notice.orEmpty(), vm.notice.orEmpty().startsWith("Not stopped"))
        (field.get(vm) as androidx.compose.runtime.MutableState<Link>).value = Link.CONNECTED
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("not sent after the reconnect either", sent.isEmpty())
        // Another session in the pane: nothing.
        val pane = checkNotNull(vm.pane("p1"))
        terminal = "replacement"
        vm.stopPane(pane)
        drainUntil { vm.notice.orEmpty().contains("something else") }
        assertTrue(sent.isEmpty())
    }

    /** Codex's review: the app locking (or the server changing) during Stop's pane read stops the keys. */
    @Test fun stopIsNotSentWhenTheAppLocksDuringItsRead() = fixture("claude", "working on it") {
        status(Status.WORKING)
        val lockedField = AppViewModel::class.java.getDeclaredField("locked\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        onPaneGet = { (lockedField.get(vm) as androidx.compose.runtime.MutableState<Boolean>).value = true }
        vm.stopPane(checkNotNull(vm.pane("p1")))
        drainUntil { vm.notice.orEmpty().startsWith("Not stopped") }
        assertTrue(vm.notice.orEmpty(), vm.notice.orEmpty().contains("locked"))
        assertTrue(sent.isEmpty())
    }

    /**
     * 2.2.8 (Bill: "it said it couldn't read it although it looked like a fairly standard question"): when
     * the recent read can't prove the dialog, the live screen is read, and its proven dialog is the card.
     */
    @Test fun agyNativePickerUsesFreshViewportAndDiscardsStaleRecentCard() {
        val picker = checkNotNull(javaClass.getResource(
            "/screens/agy/captured-1.5.18-native-question/picker.txt"
        )).readText()
        fixture("agy", picker) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing the question.", "")
            repeat(3) { readSelectedPane() }
            assertTrue(checkNotNull(vm.cardParse("p1", vm.paneText)).terminalReview)
            assertTrue(vm.paneText.contains("Which fixture label?"))

            recentResult = ExecResult(0, picker, "")
            frame = "Continuing after the question."
            repeat(3) { readSelectedPane() }
            assertFalse(vm.paneText.contains("Which fixture label?"))
            assertNull(vm.cardParse("p1", vm.paneText))
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun agyClippedApprovalUsesFreshViewportAndDiscardsStaleRecentCard() {
        val clipped = checkNotNull(javaClass.getResource(
            "/screens/agy/captured-1.5.18-tall/clipped-a.txt"
        )).readText()
        fixture("agy", clipped) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing the command.", "")
            repeat(3) { readSelectedPane() }
            assertTrue(checkNotNull(vm.cardParse("p1", vm.paneText)).terminalReview)
            assertTrue(vm.paneText.contains("fixture-a-011"))

            recentResult = ExecResult(0, clipped, "")
            frame = "Continuing after the approval."
            repeat(3) { readSelectedPane() }
            assertFalse(vm.paneText.contains("fixture-a-011"))
            assertNull(vm.cardParse("p1", vm.paneText))
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun grokPrivacyOverlayUsesFreshViewportAndDiscardsStaleRecentCard() {
        val picker = checkNotNull(javaClass.getResource(
            "/screens/grok/captured-1.0.92-picker-draft/privacy-picker.txt"
        )).readText()
        fixture("grok", picker) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Opening settings.", "")
            repeat(3) { readSelectedPane() }
            assertTrue(checkNotNull(vm.cardParse("p1", vm.paneText)).terminalReview)
            assertTrue(vm.paneText.contains("Coding data, retention, and training"))

            recentResult = ExecResult(0, picker, "")
            frame = "Continuing after closing settings."
            repeat(3) { readSelectedPane() }
            assertFalse(vm.paneText.contains("Coding data, retention, and training"))
            assertNull(vm.cardParse("p1", vm.paneText))
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun anUnprovenRecentReadFallsBackToTheLiveScreen() = fixture("claude",
        checkNotNull(javaClass.getResource("/screens/claude/synthetic-2.2.8-multiq/four-tabs-first.ansi")).readText()) {
        val live = frame
        val plain = com.billmongan.herdrdroid.herdr.stripAnsi(live).replace("\r", "").lines()
        recentResult = ExecResult(0, (listOf("❯ a stray echoed message") + plain.dropWhile { !it.startsWith("❯ 1.") }).joinToString("\n"), "")
        repeat(3) { readSelectedPane() }
        val p = checkNotNull(vm.questionCardFor("p1", vm.cardParse("p1", vm.paneText), "Claude"))
        assertEquals("Which step should run first in the sample build?", p.shownParts().heading)
        assertFalse("the live screen, not the stray row", vm.paneText.contains("stray echoed"))
        assertTrue(p.choices.isNotEmpty())
        assertTrue(guardedVisibleReads > 0)
    }

    /** 2.2.8: Oh My Pi's approval box answered from the card while herdr calls it working: Deny, and a typed steer. */
    @Test fun ompApprovalBoxAnswersWhileHerdrSaysWorking() {
        val box = checkNotNull(javaClass.getResource("/screens/omp/synthetic-2.2.8-approval/approval-three.ansi")).readText()
        fixture("omp", box) {
            status(Status.WORKING)
            readSelectedPane()
            val card = checkNotNull(detectPrompt(vm.paneText, false, "omp")).forCard()
            answer(card, checkNotNull(vm.cardTarget("p1")), index = card.choices.indexOfFirst { it.label == "Deny" })
            assertEquals(listOf("down down enter"), sent)
        }
        fixture("omp", box) {
            status(Status.WORKING)
            readSelectedPane()
            val card = checkNotNull(detectPrompt(vm.paneText, false, "omp")).forCard()
            val steer = card.choices.single { it.keys == com.billmongan.herdrdroid.herdr.OMP_STEER }
            vm.answerTyped(checkNotNull(vm.cardTarget("p1")), card, steer, "use the cached copy instead") {}
            drainWithTime { !vm.answerInFlight("p1") }
            assertEquals(listOf("text:use the cached copy instead", "enter"), sent)
        }
    }

    /**
     * 2.2.8 (Bill: "I can't send a message while a question card is open ... let me send it from the app
     * at least"): a message sent while Oh My Pi's approval box is open waits in the app's queue; nothing is
     * typed into the box (where typing would deny the tool and steer).
     */
    @Test fun aMessageWhileTheApprovalIsOpenWaitsAndTypesNothing() =
        fixture("omp", checkNotNull(javaClass.getResource("/screens/omp/synthetic-2.2.8-approval/approval-three.ansi")).readText()) {
            status(Status.WORKING)
            readSelectedPane()
            assertTrue(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
            vm.enqueue("p1", "also check the cache")
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4)); Thread.sleep(5) }
            assertTrue("nothing typed into the box", sent.isEmpty())
            assertEquals(listOf("also check the cache"), vm.queued.filter { it.paneId == "p1" }.map { it.text })
        }

    /**
     * 2.2.8 (Codex's review 1): the live read is the card even when it shows the dialog closed. A stale
     * recent read with an unproven dialog, then a live screen with only the composer: no card, ever.
     */
    @Test fun aLiveComposerOutranksAStaleUnprovenRecentDialog() = fixture("claude",
        "Earlier reply line.\n\n" + "─".repeat(40) + "\n❯ \n" + "─".repeat(40) + "\n  ? for shortcuts\n") {
        val dialog = checkNotNull(javaClass.getResource("/screens/claude/synthetic-2.2.8-multiq/four-tabs-first.ansi")).readText()
        val plain = com.billmongan.herdrdroid.herdr.stripAnsi(dialog).replace("\r", "").lines()
        val tabs = plain.indexOfFirst { it.startsWith("←") }
        val box = plain.indexOfFirst { it.startsWith("│") }
        // Recent reads that show the options but can't prove the dialog: its top gone by in the
        // scrollback, or a row of the chat in between. The one that parses with options is the case.
        val shapes = listOf(
            listOf("❯ a stray echoed message") + plain.dropWhile { !it.startsWith("❯ 1.") },
            listOf("❯ a stray echoed message") + plain.drop(box),
            plain.take(tabs + 1) + "❯ a stray echoed message" + plain.drop(tabs + 1),
            plain.take(box) + "❯ a stray echoed message" + plain.drop(box),
        ).map { it.joinToString("\n") }
        val stale = checkNotNull(shapes.firstOrNull { r ->
            val p = detectPrompt(r, true, "claude")?.forCard()
            p != null && p.choices.isNotEmpty() && !p.terminalReview && !com.billmongan.herdrdroid.herdr.questionRead(r, p, "claude", null, true).topped
        }) { "no unproven recent shape" }
        recentResult = ExecResult(0, stale, "")
        repeat(com.billmongan.herdrdroid.herdr.FALLBACK_READS + 4) {
            readSelectedPane()
            val card = vm.questionCardFor("p1", vm.cardParse("p1", vm.paneText), "Claude")
            assertTrue("no card from the stale recent dialog (read ${it + 1})", card == null || card.choices.isEmpty())
            assertFalse("the live screen, not the recent read (read ${it + 1})", vm.paneText.contains("stray echoed"))
        }
        assertTrue(guardedVisibleReads > 0)
        assertTrue(sent.isEmpty())
    }

    /** 2.2.7: the questions can always be left: Esc from the card. */
    @Test fun ompAskCanBeCancelled() = fixture("omp", ompScreen("ask-open-first.ansi")) {
        readSelectedPane()
        val card = displayed()
        answer(card, index = card.choices.indexOfFirst { it.label == com.billmongan.herdrdroid.herdr.OmpAsk.CANCEL_LABEL })
        assertEquals(listOf("esc"), sent)
    }

    /** 2.2.7: the pane's occupant changed after the card's last read: nothing typed. */
    @Test fun anOccupantChangedBeforeTheKeysIsNotSent() =
        fixture("hermes", hermesClarify) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing a question", "")
            readSelectedPane()
            val card = displayed()
            beforeCheck = { terminal = "replacement" }
            answer(card, checkNotNull(vm.paneTextTarget), index = 1)
            assertTrue(sent.isEmpty())
            assertEquals(1, cardChecksRefused)
            // 2.2.8: never "the question changed": the card shows what is on screen now, nothing typed.
            assertEquals(CARD_UPDATED_NOTE, vm.notice)
        }

    @Test fun hermesClarifyNeverSendsToChangedOrReplacementViewport() =
        fixture("hermes", hermesClarify) {
            readSelectedPane()
            val card = displayed()
            val target = checkNotNull(vm.paneTextTarget)
            val original = frame
            recentResult = ExecResult(0, original, "")
            for (changed in listOf(original.replace("Postgres", "PostgreSQL"),
                original.replace("▸ 1.", "  1."), hermesComposer, "")) {
                frame = changed
                answer(card, target)
                assertEquals("2.2.8: the card shows the question on screen now", CARD_UPDATED_NOTE, vm.notice)
            }
            frame = original
            terminal = "replacement"
            answer(card, target)
            assertTrue(sent.isEmpty())
        }

    @Test fun newerHermesApprovalReplacesClarifyWithoutReceivingItsDigit() = bothMessagingSettings("hermes", hermesClarify) {
        readSelectedPane()
        val clarify = displayed()
        val target = checkNotNull(vm.paneTextTarget)
        assertTrue(clarify.hermesClarify)
        recentResult = ExecResult(0, hermesClarify, "")
        val approval = checkNotNull(javaClass.getResource(
            "/screens/hermes/derived-1.4.9/1001-dangerous-command-approval.txt")).readText().trimEnd()
        frame = "$hermesClarify\n\n$approval"

        answer(clarify, target, index = 1)
        assertEquals("2.2.8: the card shows the question on screen now", CARD_UPDATED_NOTE, vm.notice)
        readSelectedPane()
        val newer = displayed()
        assertTrue(newer.question, newer.question.contains("Dangerous Command"))
        assertFalse(newer.hermesClarify)
        assertFalse(newer.terminalReview)
        assertTrue(newer.choices.isNotEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun hermesClarifyBlocksAForcedMessageAndGenericCardInput() = fixture("hermes", hermesClarify) {
        status(Status.IDLE)
        recentResult = ExecResult(0, hermesComposer, "")
        vm.sendMessage(checkNotNull(vm.pane("p1")), "not an answer", force = true)
        drainUntil { vm.pendingSends.any { it.waiting } || sent.isNotEmpty() }
        assertTrue(vm.pendingSends.single().waiting)
        readSelectedPane()
        val card = displayed()
        val target = checkNotNull(vm.paneTextTarget)
        vm.answerCardKey(target, card, "enter", "generic Enter")
        vm.answerTyped(target, card, null, "draft") { error("Typed reply sent") }
        assertTrue(sent.isEmpty())
        // 2.1.2 (Bill: "The alert should offer whatever options the question does"): the alert's
        // option goes in as its card's digit, after the strict re-read shows the same question.
        recentResult = ExecResult(0, hermesClarify, "")
        alert(card)
        assertEquals(listOf("1"), sent)
    }

    @Test fun recentOnlyHermesClarifyDoesNotCreateAQuestionFromScrollback() = bothMessagingSettings("hermes", hermesComposer) {
        status(Status.IDLE)
        recentResult = ExecResult(0, hermesClarify, "")
        readSelectedPane()
        assertEquals(hermesComposer, vm.paneText)
        assertTrue(vm.questionsFor("p1").isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun hermesUsageInLiveViewportIsNotTheOldQuestionAndSendsNoKey() = fixture("hermes", hermesUsage) {
        recentResult = ExecResult(0, firstStep, "")
        readSelectedPane()
        assertEquals(hermesUsage, vm.paneText)
        assertNull(detectPrompt(vm.paneText, true, "hermes"))
        assertTrue(vm.questionsFor("p1").isEmpty())
        assertNull(vm.usageOverlayFor("p1"))
        assertEquals(Status.WORKING, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        assertTrue(sent.isEmpty())
    }

    @Test fun hermesUsageQueueDrainsWhenComposerReturnsEvenWithStaleBlockedStatus() = fixture("hermes", hermesUsage) {
        readSelectedPane()
        vm.enqueue("p1", "queued follow-up")
        vm.sendQueuedImmediately(vm.queued.single())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertTrue(sent.isEmpty())
        assertEquals(1, vm.queued.size)

        recentResult = ExecResult(0, hermesUsage, "")
        frame = hermesComposer
        readSelectedPane()
        assertEquals(Status.IDLE, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        recentResult = ExecResult(0, "Old scrollback without Usage", "")
        readSelectedPane()
        assertEquals(hermesComposer, vm.paneText)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        drainUntil { sent.isNotEmpty() }
        assertTrue(vm.queued.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertEquals(listOf("text:queued follow-up", "enter"), sent)
    }

    @Test fun hermesUsageHeldSendRetriesWhenComposerReturnsWithStaleBlockedStatus() = fixture("hermes", hermesUsage) {
        readSelectedPane()
        vm.sendMessage(checkNotNull(vm.pane("p1")), "held follow-up", force = true)
        drainUntil { vm.pendingSends.any { it.waiting } }
        assertTrue(sent.isEmpty())
        frame = "$hermesUsage\n${hermesComposer.replace("❯", "❯ remote draft")}"
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertTrue(sent.isEmpty())
        frame = "$hermesUsage\n$hermesComposer"
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        drainUntil { sent.isNotEmpty() }
        assertEquals(listOf("text:held follow-up", "enter"), sent)
    }

    @Test fun hermesUsageFromRecentOnlyDoesNotOverrideBlockedStatus() = fixture("hermes", hermesComposer) {
        recentResult = ExecResult(0, hermesUsage, "")
        readSelectedPane()
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        vm.enqueue("p1", "do not send based on scrollback")
        vm.sendQueuedImmediately(vm.queued.single())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertEquals(1, vm.queued.size)
        assertTrue(sent.isEmpty())
    }

    @Test fun hermesUsageComposerProofCannotSendThroughANewApproval() = fixture("hermes", hermesUsage) {
        readSelectedPane()
        recentResult = ExecResult(0, hermesUsage, "")
        frame = hermesComposer
        readSelectedPane()
        assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))

        frame = checkNotNull(javaClass.getResource(
            "/screens/hermes/derived-1.4.9/1001-dangerous-command-approval.txt")).readText().trimEnd()
        vm.sendMessage(checkNotNull(vm.pane("p1")), "do not paste into the approval", force = true)
        drainUntil { vm.pendingSends.any { it.waiting } || sent.isNotEmpty() }
        assertTrue(sent.isEmpty())
        readSelectedPane()
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        assertTrue(displayed().choices.isNotEmpty())
    }

    @Test fun doneHermesPaneShowsItsOwnGatewayWait() = fixture("hermes", hermesComposer) {
        status(Status.DONE, sessionId = "pane-stored-session")
        gatewaySnapshot("""{"sessions":[
            {"id":"other-runtime","session_key":"other-stored-session","status":"waiting"},
            {"id":"pane-runtime","session_key":"pane-stored-session","status":"waiting","current":false}
        ]}""")
        val pane = checkNotNull(vm.pane("p1"))
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(pane))
        assertTrue(vm.hasPendingQuestion(pane))
        vm.sendMessage(pane, "must not reach the approval")
        drainUntil { vm.pendingSends.any { it.waiting } || sent.isNotEmpty() }
        assertTrue(sent.isEmpty())
    }

    @Test fun doneHermesPaneKeepsDoneForAnUnrelatedGatewayWait() = fixture("hermes", hermesComposer) {
        status(Status.DONE, sessionId = "pane-stored-session")
        gatewaySnapshot("""{"sessions":[
            {"id":"other-runtime","session_key":"other-stored-session","status":"waiting","current":true},
            {"id":"pane-runtime","session_key":"pane-stored-session","status":"idle","current":false}
        ]}""")
        val pane = checkNotNull(vm.pane("p1"))
        assertEquals(Status.DONE, vm.shownStatus(pane))
        // 2.1.5: a wait proven to be another conversation's no longer holds this pane's sends.
        assertFalse(vm.hasPendingQuestion(pane))
        status(Status.NEEDS_YOU, sessionId = "pane-stored-session")
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
    }

    // 2.1.5 (Bill: "hermes thinks there is a question right now but there is not and so it is
    // stuck"): the live laptop2 screen, idle after a cross-server notice, while another stored
    // conversation (a Telegram turn on an open approval) waits on the gateway.
    @Test fun idleHermesPaneIsNotHeldByAnotherConversationsGatewayWait() = fixture("hermes", idleNoticeScreen) {
        status(Status.IDLE, sessionId = "pane-stored-session")
        gatewaySnapshot("""{"sessions":[
            {"id":"other-runtime","session_key":"other-stored-session","status":"waiting","source":"telegram"},
            {"id":"pane-runtime","session_key":"pane-stored-session","status":"idle","source":"cli"}
        ]}""")
        assertNull(detectPrompt(frame, false, "hermes"))
        val pane = checkNotNull(vm.pane("p1"))
        assertEquals(Status.IDLE, vm.shownStatus(pane))
        assertFalse(vm.hasPendingQuestion(pane))
        vm.sendMessage(pane, "hello hermes")
        drainUntil { sent.isNotEmpty() || vm.pendingSends.any { it.held != null } }
        assertTrue("held: ${vm.pendingSends.map { it.held }}", sent.isNotEmpty())
    }

    @Test fun idleHermesPaneStillHeldByItsOwnOrAnUnattributedGatewayWait() = fixture("hermes", idleNoticeScreen) {
        status(Status.IDLE, sessionId = "pane-stored-session")
        gatewaySnapshot("""{"sessions":[
            {"id":"other-runtime","session_key":"other-stored-session","status":"idle"},
            {"id":"pane-runtime","session_key":"pane-stored-session","status":"waiting"}
        ]}""")
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        assertTrue(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
        gatewaySnapshot("""{"sessions":[
            {"id":"other-runtime","session_key":null,"status":"waiting"},
            {"id":"pane-runtime","session_key":"pane-stored-session","status":"idle"}
        ]}""")
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        assertTrue(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
        // No session identity from herdr: nothing proves the wait is elsewhere.
        status(Status.IDLE)
        gatewaySnapshot("""{"sessions":[
            {"id":"other-runtime","session_key":"other-stored-session","status":"waiting"}
        ]}""")
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        assertTrue(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
        vm.sendMessage(checkNotNull(vm.pane("p1")), "must wait")
        drainUntil { vm.pendingSends.any { it.waiting } || sent.isNotEmpty() }
        assertTrue(sent.isEmpty())
    }

    @Test fun doneHermesPaneKeepsConservativeWaitWhenIdentityIsMissing() = fixture("hermes", hermesComposer) {
        status(Status.DONE)
        gatewaySnapshot("""{"sessions":[
            {"id":"other-runtime","session_key":"other-stored-session","status":"waiting"}
        ]}""")
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))

        status(Status.DONE, sessionId = "pane-stored-session")
        // A runtime ID alone does not establish a stored-key binding to this inventory.
        gatewaySnapshot("""{"sessions":[
            {"id":"pane-stored-session","session_key":"other-stored-session","status":"waiting"}
        ]}""")
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))

        gatewaySnapshot("""{"sessions":[
            {"id":"other-runtime","session_key":null,"status":"waiting"},
            {"id":"pane-runtime","session_key":"pane-stored-session","status":"idle"}
        ]}""")
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
    }

    @Test fun hermesUsageDoesNotOverrideGatewayApprovalOrAuthorizeAReplacement() = fixture("hermes", hermesUsage) {
        status(Status.IDLE)
        readSelectedPane()
        vm.enqueue("p1", "original occupant only")
        recentResult = ExecResult(0, hermesUsage, "")
        frame = hermesComposer
        readSelectedPane()
        gatewaySnapshot("""{"sessions":[{"status":"waiting"}]}""")
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertTrue(sent.isEmpty())
        assertEquals(1, vm.queued.size)

        gatewaySnapshot("""{"sessions":[]}""")
        terminal = "replacement"
        status(Status.IDLE)
        readSelectedPane()
        vm.sendQueuedImmediately(vm.queued.single())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertTrue(sent.isEmpty())
        assertEquals(1, vm.queued.size)
    }

    @Test fun hermesUsageHoldsEvenAForcedSendUntilLiveComposerReturns() = fixture("hermes", hermesUsage) {
        val composer = "$hermesUsage\n──────────\n❯\n──────────\n☤ fixture · 1K/100K"
        status(Status.DONE)
        // Recent output looks ready even though the live viewport has no composer.
        recentResult = ExecResult(0, composer, "")
        vm.sendMessage(checkNotNull(vm.pane("p1")), "fixture follow-up", force = true)
        drainUntil { sent.isNotEmpty() || vm.pendingSends.any { it.waiting } }
        assertTrue("writes=$sent, pending=${vm.pendingSends}", vm.pendingSends.single().waiting)
        assertTrue(sent.isEmpty())
        frame = "Redrawing…" // Clearing the report isn't proof that the composer returned.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertTrue(sent.isEmpty())
        frame = composer
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        drainUntil { sent.isNotEmpty() }
        assertEquals(listOf("prompt:fixture follow-up"), sent)
    }

    @Test fun hermesUsageQueuedMessageWaitsForComposerAndDoesNotHideLaterRealQuestion() = fixture("hermes", hermesUsage) {
        status(Status.DONE)
        readSelectedPane()
        vm.enqueue("p1", "queued follow-up")
        vm.sendQueuedImmediately(vm.queued.single())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertEquals("queued follow-up", vm.queued.single().text)
        assertTrue(sent.isEmpty())

        val approval = checkNotNull(javaClass.getResource(
            "/screens/hermes/derived-1.4.9/1001-dangerous-command-approval.txt")).readText().trimEnd()
        frame = "$hermesUsage\n$approval"
        readSelectedPane()
        assertTrue(shown().choices.isNotEmpty())
        assertEquals("queued follow-up", vm.queued.single().text)
        assertTrue(sent.isEmpty())

        frame = "$hermesUsage\n──────────\n❯\n──────────\n☤ fixture · 1K/100K"
        readSelectedPane()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        drainUntil { sent.isNotEmpty() }
        assertTrue(vm.queued.isEmpty())
        assertEquals(listOf("prompt:queued follow-up"), sent)
    }

    @Test fun claudeCheckboxActionsDispatchOnlyTheirNativeRows() = fixture("claude", claudeNext) {
        readSelectedPane()
        val target = checkNotNull(vm.paneTextTarget)
        assertTrue(sent.isEmpty())
        afterSend = claudeNext.replace("[ ] tox", "[✓] tox")
        answer(displayed(), target, index = 1)
        assertEquals(listOf("down space"), sent)

        fun tap(card: Prompt, label: String) {
            val row = checkNotNull(card.answerRows().firstOrNull { it.label == label }) { "Missing $label" }
            vm.answerChoice("p1", card, row, target = target)
            drainUntil { !vm.answerInFlight("p1") }
        }
        val next = displayed()
        val checked = frame
        val submitFrame = claudeNext.replace("☐ Runners", "☒ Runners").replace("    Next", "    Submit")
        frame = submitFrame
        tap(next, "Next")
        frame = checked.replace("    Next", "    Next\n    Submit")
        tap(next, "Next")
        frame = checked.replace("    Next\n", "")
        tap(next, "Next")
        frame = checked
        terminal = "replacement"
        tap(next, "Next")
        terminal = "t1"
        liveScrollOffset = 1
        tap(next, "Next")
        liveScrollOffset = 0
        assertEquals(listOf("down space"), sent)

        // Focus moved since the card was shown. Read the live highlight, not the captured one.
        frame = checked.replace("❯ 1.", "  1.").replace("    Next", "❯   Next")
        afterSend = submitFrame
        tap(next, "Next")
        assertEquals(listOf("down space", "enter"), sent)

        afterSend = claudeReview
        tap(displayed(), "Submit")
        assertEquals(listOf("down space", "enter", "down down down enter"), sent)
        val review = shown()
        assertEquals("Submit answers", review.choices.first().label)

        afterSend = null
        frame = claudeReview.replace("→ Python", "→ Rust")
        answer(review, target)
        frame = claudeReview
        terminal = "replacement"
        answer(review, target)
        terminal = "t1"
        vm.answerChoice("another-pane", review, review.choices.first(), target = target)
        assertEquals(listOf("down space", "enter", "down down down enter"), sent)

        frame = claudeReview.replace("❯ 1.", "  1.").replace("  2.", "❯ 2.")
        answer(review, target)
        answer(review, target)
        assertEquals(listOf("down space", "enter", "down down down enter", "up enter"), sent)
    }

    @Test fun claudeConfiguredEditorHintPreservesNextAndSubmitOnFreshRead() = fixture("claude", claudeNext) {
        for (label in listOf("Next", "Submit")) {
            val base = claudeNext.replace("    Next", "    $label")
            val typed = base.replace("❯ 1.", "  1.").replace("  3. [ ] Type", "❯ 3. [ ] Type")
                .replace(" · Esc to cancel", " · ctrl+g to edit in vim · Esc to cancel")
            frame = typed
            readSelectedPane()
            val card = displayed()
            val row = card.answerRows().singleOrNull { it.label == label }
            assertNotNull("Missing $label on the custom-answer row", row)
            val target = checkNotNull(vm.paneTextTarget)
            val prior = sent.toList()
            fun tap() {
                vm.answerChoice("p1", card, checkNotNull(row), target = target)
                drainUntil { !vm.answerInFlight("p1") }
            }
            frame = typed.replace("ctrl+g to edit in vim", "ctrl+g to edit in vim before continuing")
            tap()
            assertEquals(prior, sent)
            frame = typed
            afterSend = if (label == "Submit") claudeReview else null
            tap()
            assertEquals(prior + "down enter", sent)
            if (label == "Submit") assertEquals("Submit answers", shown().choices.first().label)
        }
    }

    @Test fun claudeAskWithNoVisibleHighlightDoesNotTypeAnOptionDigit() =
        fixture("claude", firstStep.replace("❯ 1.", "  1.")) {
            readSelectedPane()
            val card = displayed()
            assertNull(card.selected)
            assertTrue("displayed prompt must require terminal review: ${card.choices}", card.terminalReview)
            assertTrue("no actions on terminal review", card.choices.isEmpty())
            vm.answerChoice("p1", card, Choice("Blue", "1", false), target = checkNotNull(vm.paneTextTarget))
            drainUntil { !vm.answerInFlight("p1") }
            assertTrue("no digit must reach the pane: $sent", sent.isEmpty())
        }

    @Test fun claudeAskThatLosesHighlightBeforeTheTapDoesNotSend() = fixture("claude", firstStep) {
        readSelectedPane()
        val card = displayed()
        assertEquals("1", card.selected)
        frame = frame.replace("❯ 1.", "  1.")
        answer(card)
        assertTrue(sent.isEmpty())
    }

    @Test fun claudeCheckboxActionMissingOrAmbiguousIsNotAnAnswerRow() {
        fun labels(frame: String, agent: String = "claude") = detectPrompt(frame, true, agent)?.answerRows().orEmpty().map { it.label }
        assertTrue("Next" in labels(claudeNext))
        for (frame in listOf(claudeMulti, claudeNext.replace("    Next\n", ""),
            claudeNext.replace("    Next", "    Next\n    Submit"),
            claudeNext.replace("    Next", "❯   Next"), // two visible focus markers
            "$claudeNext\n──────────\n❯\n──────────")) {
            assertTrue(labels(frame).none { it == "Next" || it == "Submit" })
        }
        assertFalse("Next" in labels(claudeNext, "codex"))
    }

    @Test fun claudeCheckboxActionIsNotReplayedAfterAnUnconfirmedDelivery() = fixture("claude", claudeNext) {
        readSelectedPane()
        val target = checkNotNull(vm.paneTextTarget)
        val card = displayed()
        val next = card.answerRows().single { it.label == "Next" }
        failAfterSend = true
        repeat(2) {
            vm.answerChoice("p1", card, next, target = target)
            drainUntil { !vm.answerInFlight("p1") }
        }
        assertEquals(listOf("down down down enter"), sent)
    }

    @Test fun openCodeBackgroundDrawerIsNotAQuestionAndDoesNotReceiveChat() =
        fixture("opencode-work", openCodeDrawer) {
            recentResult = ExecResult(0, openCodePermission, "")
            readSelectedPane()
            assertEquals(openCodeDrawer, vm.paneText)
            assertNull(detectPrompt(vm.paneText, true, "opencode-work"))
            assertTrue(vm.questionsFor("p1").isEmpty())
            assertTrue(vm.blocksNormalSend(checkNotNull(vm.pane("p1")).copy(status = Status.WORKING), frame))
            assertTrue(sent.isEmpty())

            // Close/redraw is simulated externally; observation must never dismiss the drawer.
            frame = openCodeComposer
            readSelectedPane()
            assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
            assertTrue(sent.isEmpty())
        }

    @Test fun openCodeStaleBlockedStatusAndRecentPermissionDoNotHoldNormalChat() =
        bothMessagingSettings("opencode", openCodeComposer) {
            recentResult = ExecResult(0, openCodePermission, "")
            readSelectedPane()

            assertEquals(1, guardedVisibleReads)
            assertEquals(openCodeComposer, vm.paneText)
            assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
            assertTrue(vm.questionsFor("p1").isEmpty())
            assertTrue(sent.isEmpty())

            var delivered = false
            vm.sendMessage(checkNotNull(vm.pane("p1")), "Fixture chat") { delivered = true }
            drainUntil { delivered || vm.pendingSends.any { it.waiting } }
            assertTrue(delivered)
            assertEquals(listOf("text:Fixture chat", "enter"), sent)
        }

    @Test fun openCodePermissionAppearingBeforeSendStillHoldsChat() = fixture("opencode", openCodeComposer) {
        recentResult = ExecResult(0, openCodeComposer, "")
        readSelectedPane()
        assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
        frame = openCodePermission

        vm.sendMessage(checkNotNull(vm.pane("p1")), "Fixture chat")
        drainUntil { sent.isNotEmpty() || vm.pendingSends.any { it.waiting } }
        assertTrue(sent.isEmpty())
        assertTrue(vm.pendingSends.single().waiting)

        readSelectedPane()
        assertEquals(listOf("Allow once", "Always allow", "Reject"), shown().choices.map { it.label })
        assertEquals(openCodePermission, vm.paneText)
        assertTrue(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
    }

    @Test fun nativeOpenCodeApprovalUsesFreshFocusAndNeverOffersStandingPermission() {
        for (agent in listOf("opencode", "opencode-work")) fixture(agent, openCodePermission) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing the next step", "")
            readSelectedPane()
            val card = displayed()
            assertEquals(listOf("Allow once", "Always allow", "Reject"), card.choices.map { it.label })
            assertTrue(card.shownParts().command.orEmpty().contains(approvalCommand))
            assertTrue(sent.isEmpty())
            frame = focusedPermission("Always allow")
            answer(card, checkNotNull(vm.paneTextTarget))
            assertEquals(listOf("left enter"), sent)
        }
    }

    // 2.1.2 (Bill, 2026-11-21): OpenCode 2.3.2's permission dialog while herdr says done.
    @Test fun openCode232PermissionShowsItsCardWhileHerdrSaysDoneAndAnswersThroughTheSamePath() {
        val v232 = checkNotNull(javaClass.getResource("/screens/opencode/live-2026-11-21/permission-shell-2.3.2.txt")).readText()
        fun focused(focus: String) = listOf("Allow once", "Always allow", "Reject").fold(v232) { t, label ->
            t.replace("   $label", "   \u001B[${if (label == focus) 44 else 49}m$label\u001B[0m")
        }
        for (status in listOf(Status.DONE, Status.IDLE)) fixture("opencode-work", v232) {
            status(status)
            readSelectedPane()
            val card = displayed()
            assertFalse(card.terminalReview)
            assertEquals(listOf("Allow once", "Always allow", "Reject"), card.choices.map { it.label })
            assertTrue(card.shownParts().command.orEmpty().contains("echo hi > ~/scratch/x"))
            assertTrue(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
            assertTrue(sent.isEmpty())
            frame = focused("Allow once")
            answer(card, checkNotNull(vm.paneTextTarget))
            assertEquals(listOf("enter"), sent)
        }
        fixture("opencode", v232) {
            status(Status.DONE)
            readSelectedPane()
            val card = displayed()
            frame = focused("Allow once")
            answer(card, checkNotNull(vm.paneTextTarget), index = 2)
            assertEquals(listOf("esc"), sent)
        }
    }

    @Test fun wrappedOpenCodePermissionReconstructsTokensAndKeepsGuardedButtons() {
        val capture = checkNotNull(javaClass.getResource(
            "/screens/opencode/derived-2.2.10/permission-wrapped.txt")).readText()
        val styled = listOf("Allow once", "Always allow", "Reject").fold(capture) { text, label ->
            text.replace(label, "\u001B[${if (label == "Allow once") 44 else 49}m$label\u001B[0m")
        }
        fixture("opencode", styled) {
            readSelectedPane()
            val card = displayed()
            assertFalse(card.terminalReview)
            assertTrue(card.shownParts().command.orEmpty().contains(
                "tool.py -\nc 12345 --preview --format text --file /home/user/project/fixture/fixture/\nfixtures/data.json"
            ))
            assertEquals(listOf("Allow once", "Always allow", "Reject"), card.choices.map { it.label })
            assertNull(card.selected)
            answer(card, checkNotNull(vm.paneTextTarget), index = 1)
            assertTrue(sent.isEmpty())
            val ask = checkNotNull(vm.approveAllPending).ask
            assertNull(ask.keys)
            assertNull(ask.reason)
            vm.confirmApproveAll()
            drainUntil { !vm.answerInFlight("p1") }
            assertEquals(listOf("right enter"), sent)
        }
    }

    @Test fun wrappedOpenCodePermissionFallsBackForUnframedOrClippedRows() {
        val frame = res("/screens/opencode/derived-2.2.10/permission-wrapped.txt")
        val changedFrames = listOf(
            frame.replace("  ┃  c 12345", "     c 12345"),
            frame.replace("fixtures/data.json", "fixtures/…"),
            frame.replace("  ┃  △ Permission required\n", ""),
        )
        for (changed in changedFrames) {
            val card = checkNotNull(detectPrompt(changed, blocked = false, agent = "opencode"))
            assertTrue(card.terminalReview)
            assertTrue(card.choices.isEmpty())
        }
    }

    @Test fun wrappedOpenCodeStandingPermissionWithUnprovenFocusSendsNothing() =
        fixture("opencode", res("/screens/opencode/derived-2.2.10/permission-wrapped.txt")) {
            readSelectedPane()
            val card = displayed()
            answer(card, checkNotNull(vm.paneTextTarget), index = 1)
            assertNull(checkNotNull(vm.approveAllPending).ask.reason)
            vm.confirmApproveAll()
            drainUntil { !vm.answerInFlight("p1") }
            assertTrue(sent.isEmpty())
        }

    @Test fun nativeOpenCodeRejectDoesNotConfirmTheHighlightedApproval() = fixture("opencode", openCodePermission) {
        readSelectedPane()
        val card = displayed()
        frame = focusedPermission("Allow once")
        // Reject is the third button since 2.1.2 shows Always allow too.
        answer(card, checkNotNull(vm.paneTextTarget), index = 2)
        assertEquals(listOf("esc"), sent)
    }

    @Test fun nativeOpenCodeApprovalRefusesUnverifiedFocusChangedCommandDrawerAndOccupant() =
        fixture("opencode-work", openCodePermission) {
            readSelectedPane()
            val card = displayed()
            val target = checkNotNull(vm.paneTextTarget)
            answer(card, target)
            assertTrue(sent.isEmpty())
            frame = focusedPermission("Allow once").replace("pip install", "pip  install")
            answer(card, target)
            assertTrue(sent.isEmpty())
            frame = openCodeDrawer
            answer(card, target)
            assertTrue(sent.isEmpty())
            frame = focusedPermission("Allow once")
            terminal = "replacement"
            answer(card, target)
            assertTrue(sent.isEmpty())
        }

    @Test fun nativeOpenCodeClippedApprovalIsTerminalOnly() = fixture("opencode", openCodePermission.replace(approvalCommand, "…")) {
        readSelectedPane()
        val card = displayed()
        assertTrue(card.terminalReview)
        assertTrue(card.choices.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun nativeOpenCodeQuestionUsesFreshSelection() {
        for (agent in listOf("opencode", "opencode-work")) fixture(agent, openCodeQuestion) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing a question", "")
            readSelectedPane()
            val card = displayed()
            assertEquals("Which database should the fixture use?", card.question)
            assertEquals(listOf("SQLite", "Postgres", "Type your own answer"), card.choices.map { it.label })
            assertTrue(sent.isEmpty())
            frame = openCodeQuestion.replace(">1.", " 1.").replace(" 2.", ">2.")
            answer(card, checkNotNull(vm.paneTextTarget))
            assertEquals(listOf("up enter"), sent)
        }
    }

    @Test fun codexHookTrustRequiresConfirmationAndClearsWhenCodexShowsDetails() =
        fixture("codex", hooksBrowser) {
            readSelectedPane()
            val card = displayed()
            assertEquals(listOf("Trust all", "Review", "Close"), card.choices.map { it.label })
            assertEquals(listOf("t"), com.billmongan.herdrdroid.herdr.menuKeys(card, card.choices[0]))
            assertEquals(listOf("enter"), com.billmongan.herdrdroid.herdr.menuKeys(card, card.choices[1]))
            assertEquals(listOf("esc"), com.billmongan.herdrdroid.herdr.menuKeys(card, card.choices[2]))
            val target = checkNotNull(vm.paneTextTarget)
            vm.answerChoice("p1", card, card.choices.first(), target = target)
            assertNotNull(vm.approveAllPending)
            assertTrue(sent.isEmpty())
            vm.cancelApproveAll()
            assertTrue(sent.isEmpty())
            vm.answerChoice("p1", card, card.choices.first(), target = target)
            frame = hooksBrowser.replace("1 hook needs review", "2 hooks need review").replace("it can run", "they can run")
            vm.confirmApproveAll()
            drainUntil { !vm.answerInFlight("p1") }
            assertTrue("confirmation of a changed hook table must refuse", sent.isEmpty())
            frame = hooksBrowser
            readSelectedPane()
            val live = displayed()
            recentResult = ExecResult(0, hooksBrowser, "")
            afterSend = hooksTrusted
            vm.answerChoice("p1", live, live.choices.first(), target = checkNotNull(vm.paneTextTarget))
            vm.confirmApproveAll()
            drainUntil { sent == listOf("t") && vm.paneText.contains("enter details") }
            assertNull(vm.cardParse("p1", vm.paneText))
        }

    @Test fun codexHookReviewSendsEnterThenHandsOffToTheTerminal() = fixture("codex", hooksBrowser) {
        readSelectedPane()
        val card = displayed()
        afterSend = hooksDetail
        vm.answerChoice("p1", card, card.choices.single { it.label == "Review" },
            target = checkNotNull(vm.paneTextTarget))
        drainUntil { sent == listOf("enter") && vm.paneText.contains("esc back") }
        assertEquals(listOf("enter"), sent)
        assertNull(vm.cardParse("p1", vm.paneText))
    }

    @Test fun codexHookBrowserClosesWithEsc() = fixture("codex", hooksBrowser) {
        readSelectedPane()
        val card = displayed()
        status(Status.IDLE)
        afterSend = "› "
        answer(card, checkNotNull(vm.paneTextTarget), index = 2)
        assertEquals(listOf("esc"), sent)
        assertNull(vm.cardParse("p1", vm.paneText))
    }

    @Test fun codexStartupHookMenuUsesItsOwnArrowKeysAndRequiresTrustConfirmation() {
        val frame = hooksFixture("startup")
        val card = checkNotNull(detectPrompt(frame, false, "codex"))
        assertEquals("Hooks need review", card.question)
        assertEquals(listOf("Review", "Trust all and continue", "Continue without trusting"), card.choices.map { it.label })
        assertEquals(listOf("down", "enter"), com.billmongan.herdrdroid.herdr.approveAllAsk(card, card.choices[1])?.keys)
        assertNull(detectPrompt("$hooksBrowser\n\n› New input", false, "codex")?.takeIf { it.choices.any { c -> c.label == "Trust all" } })
        assertNull(detectPrompt(hooksBrowser.replace("PreToolUse", "invalid row"), false, "codex")?.takeIf { it.choices.any { c -> c.label == "Trust all" } })
    }

    @Test fun clippedCodexQuestionProbesTheLiveViewportWithoutSendingKeys() = fixture("codex", codexUnansweredConfirmation) {
        status(Status.WORKING)
        val clipped = codexWrappedQuestion.substringAfter('\n')
        recentResult = ExecResult(0, clipped, "")
        readSelectedPane()
        assertEquals(1, guardedVisibleReads)
        assertEquals(codexUnansweredConfirmation, vm.paneText)
        val card = displayed()
        assertTrue(card.terminalReview)
        val target = checkNotNull(vm.paneTextTarget)
        vm.answerChoice("p1", card, Choice("Proceed", "1", true), target = target)
        vm.answerCardKey(target, card, "enter", "Proceed")
        assertTrue(sent.isEmpty())

        frame = "› Next request"
        readSelectedPane()
        // 2.1.0: text in the input box is a draft, so the live viewport is read once more, with its
        // styles, for the draft card. Reading it types nothing.
        assertEquals(3, guardedVisibleReads)
        assertEquals(frame, vm.paneText)
        assertNull(detectPrompt(vm.paneText, false, "codex"))
        assertEquals("Next request", vm.paneDraft?.text)
        assertTrue(sent.isEmpty())
    }

    // ---- 2.1.0: Muse Code's quota warning as a question card --------------------------------

    private val museQuota: String get() = checkNotNull(javaClass.classLoader?.getResource("screens/muse/derived-2.1.0/quota-fast-full.txt")).readText()

    /** The warning read with styles: option [hi] bold and blue, the others plain. */
    private fun museStyled(hi: Int?): String {
        var n = 0
        return museQuota.lines().joinToString("\n") { row ->
            if (com.billmongan.herdrdroid.herdr.MUSE_QUOTA_TITLES.any { row.trimStart().startsWith(it) } && row.startsWith("  ") && !row.startsWith("    ")) {
                n++
                if (n == hi) "  \u001B[1;38;2;120;170;255m${row.trimStart()}\u001B[0m" else "  \u001B[38;2;230;230;230m${row.trimStart()}\u001B[0m"
            } else row
        }
    }

    @Test fun museQuotaWarningIsACardAnsweredOnlyOnATap() = fixture("muse", museQuota) {
        for (st in listOf(Status.NEEDS_YOU, Status.IDLE)) {
            status(st)
            ansiOverride = museStyled(1)
            readSelectedPane()
            assertTrue(sent.isEmpty())
            val card = displayed()
            assertTrue(card.museQuota)
            assertEquals(4, card.choices.size)
        }
        val card = displayed()
        val both = card.choices.first { it.label.startsWith("Both") }
        vm.answerChoice("p1", card, both, target = checkNotNull(vm.paneTextTarget))
        drainUntil { !vm.answerInFlight("p1") }
        assertEquals(listOf("down down enter"), sent)
    }

    @Test fun museQuotaKeepGoingDismissesAndAnUnknownHighlightSendsNothing() = fixture("muse", museQuota) {
        status(Status.IDLE)
        ansiOverride = museStyled(null)
        readSelectedPane()
        val card = displayed()
        vm.answerChoice("p1", card, card.choices.last(), target = checkNotNull(vm.paneTextTarget))
        drainUntil { !vm.answerInFlight("p1") }
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty(), vm.notice.orEmpty().startsWith("Not sent."))

        ansiOverride = museStyled(2)
        readSelectedPane()
        val again = displayed()
        vm.answerChoice("p1", again, again.choices.last(), target = checkNotNull(vm.paneTextTarget))
        drainUntil { !vm.answerInFlight("p1") }
        assertEquals(listOf("down down enter"), sent)
    }

    // ---- 2.1.0: a draft in the pane's input box, sent or cleared from the chat --------------

    private val draftRule = "─".repeat(60)
    private fun hermesBox(vararg rows: String) =
        (listOf("  ┊ review done", draftRule) + rows + listOf(draftRule, " ☤ gpt-6.1-sol · 295K/500K")).joinToString("\n")

    private fun Fixture.waitForDraftAction() {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            if (vm.paneDraftBusy("p1") == null) return
            Thread.sleep(1)
        } while (System.nanoTime() < deadline)
        fail("Draft action did not finish")
    }

    @Test fun hermesDraftIsShownAndSendDraftPressesEnterOnce() = fixture("hermes", hermesBox("❯ Please draft the reply", "about the vault push")) {
        status(Status.IDLE)
        readSelectedPane()
        val draft = checkNotNull(vm.paneDraft)
        assertEquals("Please draft the reply\nabout the vault push", draft.text)
        assertTrue(draft.actionable && draft.clearable)
        assertTrue(sent.isEmpty())
        afterSend = hermesBox("❯ ")
        vm.sendPaneDraft(draft)
        waitForDraftAction()
        assertEquals(listOf("enter"), sent)
        assertEquals("Draft sent to Hermes.", vm.notice)
    }

    @Test fun clearDraftPressesOnlyBackspace() = fixture("hermes", hermesBox("❯ two lines", "of draft")) {
        status(Status.IDLE)
        readSelectedPane()
        val draft = checkNotNull(vm.paneDraft)
        afterSend = hermesBox("❯ ")
        vm.clearPaneDraft(draft)
        waitForDraftAction()
        assertEquals(1, sent.size)
        // The probe: a few Backspaces, read back; the box was empty after them.
        assertEquals(List(minOf("two lines\nof draft".length, com.billmongan.herdrdroid.herdr.PaneDraftAction.CLEAR_PROBE)) { "backspace" },
            sent.single().split(' '))
        assertEquals("Draft cleared from Hermes's input box.", vm.notice)
    }

    @Test fun clearDraftIsRefusedWhenTheOccupantChanges() = fixture("hermes", hermesBox("❯ two lines", "of draft")) {
        status(Status.IDLE)
        readSelectedPane()
        val draft = checkNotNull(vm.paneDraft)
        terminal = "t2" // Another terminal is in the pane now.
        status(Status.IDLE)
        vm.clearPaneDraft(draft)
        waitForDraftAction()
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty(), vm.notice.orEmpty().startsWith("Not cleared:"))
    }

    @Test fun aSlashCommandDraftHasNoSendButClearStays() = fixture("hermes", hermesBox("❯ /clear")) {
        status(Status.IDLE)
        readSelectedPane()
        val draft = checkNotNull(vm.paneDraft)
        assertTrue(draft.sendNote.orEmpty().contains("command"))
        assertTrue(draft.clearable)
        vm.sendPaneDraft(draft)
        waitForDraftAction()
        assertTrue(sent.isEmpty())
    }

    @Test fun aPlaceholderIsReadWithStylesOnceNotEveryPoll() = fixture("codex", "› Explain this codebase\n\n  footer") {
        status(Status.IDLE)
        ansiPlaceholder = true
        readSelectedPane()
        val first = guardedVisibleReads
        readSelectedPane()
        readSelectedPane()
        assertNull(vm.paneDraft)
        assertEquals(first, guardedVisibleReads)
    }

    @Test fun draftChangedAtTheComputerIsNotSent() = fixture("hermes", hermesBox("❯ first version")) {
        status(Status.IDLE)
        readSelectedPane()
        val draft = checkNotNull(vm.paneDraft)
        frame = hermesBox("❯ first version, edited")
        vm.sendPaneDraft(draft)
        waitForDraftAction()
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty(), vm.notice.orEmpty().startsWith("Not sent: the draft changed"))
    }

    @Test fun sendDraftRefusesAReplacementAtTheHostGuard() = fixture("hermes", hermesBox("❯ first draft")) {
        status(Status.IDLE)
        readSelectedPane()
        val draft = checkNotNull(vm.paneDraft)
        beforeDraftCheck = { frame = hermesBox("❯ replacement draft") }
        vm.sendPaneDraft(draft)
        waitForDraftAction()
        assertNull(beforeDraftCheck)
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty().startsWith("Not sent: the draft changed"))
    }

    @Test fun noDraftNoCardAndNoExtraRead() = fixture("hermes", hermesBox("❯ ")) {
        status(Status.IDLE)
        readSelectedPane()
        val reads = guardedVisibleReads
        readSelectedPane()
        assertNull(vm.paneDraft)
        assertTrue(sent.isEmpty())
        assertEquals(reads * 2, guardedVisibleReads)
    }

    @Test fun nativeOpenCodeColorOnlyQuestionSendsOneDigitFromChat() {
        for (agent in listOf("opencode", "opencode-work")) fixture(agent, nativeQuestion(1)) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing a question", "")
            readSelectedPane()
            val card = displayed()
            assertEquals("Which database should the fixture use?", card.question)
            assertTrue(sent.isEmpty())
            val target = checkNotNull(vm.paneTextTarget)
            frame = nativeQuestion(2)
            answer(card, target, index = 1)
            assertEquals(vm.notice, listOf("2"), sent)
        }
    }

    @Test fun nativeOpenCodeQuestionRefusesChangedClippedEditingOrReplacedViewport() = fixture("opencode", nativeQuestion(1)) {
        readSelectedPane()
        val card = displayed()
        val target = checkNotNull(vm.paneTextTarget)
        // Keep scrollback readable even when the final live viewport is unavailable.
        recentResult = ExecResult(0, nativeQuestion(1), "")
        for (changed in listOf(
            nativeQuestion(2).replace("Server database", "Different database"),
            nativeQuestion(2).replace("Which database should the fixture use?", ""),
            nativeQuestion(3).replace("┃  ↑↓ select", "┃      Type your own answer\n┃  ↑↓ select"),
            nativeQuestion(2).replace("enter submit", "enter toggle"),
            openCodePermission, openCodeDrawer, openCodeComposer, "",
        )) {
            frame = changed
            answer(card, target, index = 1)
            assertEquals("2.2.8: the card shows the question on screen now", CARD_UPDATED_NOTE, vm.notice)
        }
        frame = nativeQuestion(2)
        liveScrollOffset = 1
        answer(card, target, index = 1)
        liveScrollOffset = 0
        terminal = "replacement"
        answer(card, target, index = 1)
        terminal = "t1"
        connection = Any()
        answer(card, target, index = 1)
        assertTrue(sent.isEmpty())
    }

    @Test fun workingClaudeDiscoversAQuestionFromTheLiveViewport() = fixture("claude", firstStep) {
        val card = frame
        val recent = "Claude is preparing a response."
        status(Status.WORKING)
        recentResult = ExecResult(0, recent, "")
        readSelectedPane()
        assertEquals(1, guardedVisibleReads)
        assertEquals(card, vm.paneText)
        assertTrue(detectPrompt(vm.paneText, false, "claude")!!.choices.isNotEmpty())

        for (nonCard in listOf("", "❯ Next request")) {
            frame = nonCard
            readSelectedPane()
            assertEquals(recent, vm.paneText)
        }
        assertEquals(3, guardedVisibleReads)

        recentResult = ExecResult(0, card, "")
        readSelectedPane()
        assertEquals(3, guardedVisibleReads)
        assertEquals(card, vm.paneText)

        status(Status.WORKING, scrollOffset = 1)
        recentResult = ExecResult(0, recent, "")
        frame = card
        readSelectedPane()
        assertEquals(3, guardedVisibleReads)
        assertEquals(recent, vm.paneText)
        assertTrue(sent.isEmpty())
    }

    @Test fun workingClaudeRecoversAnIncompleteQuestionFromTheLiveViewport() = fixture("claude", firstStep) {
        val complete = frame
        val partial = complete.lineSequence().filterNot { it.trimStart().matches(Regex("❯?\\s*[123]\\. .*")) }
            .joinToString("\n")
        assertTrue(checkNotNull(detectPrompt(partial, false, "claude")).choices.isEmpty())
        status(Status.WORKING)
        recentResult = ExecResult(0, partial, "")

        readSelectedPane()

        assertEquals(1, recentReads)
        assertEquals(1, guardedVisibleReads)
        assertEquals(complete, vm.paneText)
        assertEquals(listOf("Blue", "Orange", "Type something."),
            detectPrompt(vm.paneText, false, "claude")?.choices?.map { it.label })

        for (incomplete in listOf(partial, "", "❯ Next request")) {
            frame = incomplete
            readSelectedPane()
            assertEquals(partial, vm.paneText)
            assertTrue(checkNotNull(detectPrompt(vm.paneText, false, "claude")).choices.isEmpty())
        }
        assertEquals(4, guardedVisibleReads)

        status(Status.WORKING, scrollOffset = 1)
        frame = complete
        readSelectedPane()
        assertEquals(4, guardedVisibleReads)
        assertEquals(partial, vm.paneText)
        assertTrue(sent.isEmpty())
    }

    @Test fun voiceStagesVisibleApprovalDirectlyAndSendsOnlyAfterFullReadback() = bothMessagingSettings("codex", command("alpha-beta")) {
        // 2.2.2: voice answers only the open pane's confirmed card, so Chat reads it twice first.
        readSelectedPane(); readSelectedPane()
        val chatReads = recentReads
        recentResult = ExecResult(0, "Working on the report", "")
        assertTrue(shown().choices.isNotEmpty())
        speak("option 1")
        assertEquals("voice itself reads no recent output", chatReads, recentReads)
        assertTrue(spoken.contains("Fixture") && spoken.contains("echo alpha-beta") && spoken.contains("Yes, proceed"))
        assertNull(voice.confirmationGrammar())
        assertTrue(sent.isEmpty())
        completeSpeech()
        assertEquals(ConfirmationGrammar.ANSWER, voice.confirmationGrammar())
        speak("confirm answer")
        drainUntil { !vm.answerInFlight("p1") }
        assertEquals(spoken, listOf("y"), sent)
        speak("confirm answer")
        assertEquals(listOf("y"), sent)
    }

    @Test fun voiceRefusesChangedApprovalButCanStageDenialWithAStandingAlternative() = fixture("codex", command("alpha-beta")) {
        frame = frame.replace("  2. No", "  2. Yes, and don't ask again (a)\n  3. No")
        speak("answer option two")
        completeSpeech()
        assertNull(voice.confirmationGrammar())
        speak("answer option one")
        completeSpeech()
        frame = frame.replace("alpha-beta", "alphabeta")
        speak("confirm answer")
        drainUntil { !vm.answerInFlight("p1") }
        assertTrue(sent.isEmpty())
        // 2.2.2: voice sends only once Chat has confirmed the changed card (two matching reads, the
        // open pane's own read cadence); the refused confirm above read it only once.
        readSelectedPane(); readSelectedPane()
        speak("answer option three")
        completeSpeech()
        speak("confirm answer")
        drainUntil { !vm.answerInFlight("p1") }
        assertEquals(spoken, listOf("esc"), sent)
    }

    @Test fun voiceListsOnlyVerifiedCardsAndRefusesAReplacedOccupant() = fixture("claude", firstStep.replace("  ☐ Size", "")) {
        speak("list questions")
        assertTrue(spoken, spoken.contains("Which option should the sample use?"))
        assertTrue(sent.isEmpty())
        speak("read question")
        assertTrue(spoken, spoken.contains("Option 2. Orange"))
        speak("option 1")
        completeSpeech()
        terminal = "t2"
        speak("confirm answer")
        drainUntil { !vm.answerInFlight("p1") }
        assertTrue(sent.isEmpty())
        speak("list questions")
        assertTrue(spoken, spoken.contains("could not verify", ignoreCase = true))
    }

    @Test fun voiceRefusesAStaleDisplayedCardBeforeStaging() = fixture("codex", command("alpha-beta")) {
        readSelectedPane()
        assertNotNull(vm.paneTextTarget)
        frame = command("alphabeta")
        speak("option 1")
        completeSpeech()
        assertNull(voice.confirmationGrammar())
        speak("confirm answer")
        assertTrue(sent.isEmpty())
    }

    @Test fun voiceRefusesEarlyConfirmationCancellationAndTypedOrMultiChoice() = fixture("claude", firstStep) {
        speak("option 1")
        completeSpeech()
        assertNull(voice.confirmationGrammar()) // Multi-question dialog.
        frame = frame.replace("  ☐ Size", "")
        speak("option 3")
        completeSpeech()
        assertNull(voice.confirmationGrammar()) // Typed option.
        speak("option 1")
        speak("confirm answer") // Readback never completed.
        assertTrue(sent.isEmpty())
        speak("option 1")
        completeSpeech()
        speak("cancel answer")
        speak("confirm answer")
        assertTrue(sent.isEmpty())
    }

    @Test fun voiceUsesLiveHighlightAndDoesNotRepeatUnconfirmedDelivery() = fixture("claude", firstStep.replace("  ☐ Size", "")) {
        // 2.2.2: voice answers only the open pane's confirmed card, so Chat reads it twice first.
        readSelectedPane(); readSelectedPane()
        speak("option 2")
        completeSpeech()
        frame = frame.replace("❯ 1. Blue", "  1. Blue").replace("  2. Orange", "❯ 2. Orange")
        failAfterSend = true
        speak("confirm answer")
        drainUntil { !vm.answerInFlight("p1") }
        assertTrue(spoken, spoken.startsWith("Could not confirm delivery"))
        assertEquals(listOf("enter"), sent)
        speak("option 2")
        completeSpeech()
        speak("confirm answer")
        drainUntil { !vm.answerInFlight("p1") }
        assertEquals(listOf("enter"), sent)
    }

    @Test fun successiveClaudeTabsWithIdenticalQuestionAndOptionsBothSend() = fixture("claude", firstStep) {
        val first = shown()
        answer(first)
        assertEquals(listOf("enter"), sent)
        assertTrue(vm.notice.orEmpty().startsWith("Sent "))

        frame = secondStep
        val next = shown()
        assertEquals(first.question, next.question)
        assertEquals(first.choices, next.choices)
        assertNotEquals(first.parts?.step, next.parts?.step)
        assertTrue(QuestionLog.sameCard(QuestionLog.signature(first), QuestionLog.signature(next)))
        answer(next)
        assertEquals(vm.notice, listOf("enter", "enter"), sent)
    }

    @Test fun claudeBackgroundAskShowsThirdTabAndAdvancesToTheFourth() = fixture("claude", backgroundAsk("third")) {
        readSelectedPane()
        val third = displayed()
        assertEquals("Question 3 of 4", third.parts?.step)
        assertEquals(listOf(true, true, false, false, false), third.parts?.tabs?.map { it.done })
        assertEquals(listOf("Wait for completion", "Try again", "Type something.", "Chat about this"),
            third.choices.map { it.label })
        assertEquals(listOf("down", "enter"), com.billmongan.herdrdroid.herdr.menuKeys(third, third.choices[1]))
        afterSend = backgroundAsk("fourth")
        answer(third, checkNotNull(vm.paneTextTarget), index = 1)
        assertEquals(listOf("down enter"), sent)
        readSelectedPane()
        val fourth = displayed()
        assertEquals("Question 4 of 4", fourth.parts?.step)
        assertEquals("Choose a brief time window for the next step. The window starts when work resumes.", fourth.question)
        assertEquals(listOf("Use a short window", "Use a longer window", "Decide later", "Type something.", "Chat about this"),
            fourth.choices.map { it.label })
    }

    @Test fun terminalEnterClearsClaudeBackgroundAskWhenTheComposerReturns() = fixture("claude", backgroundAsk("fourth")) {
        readSelectedPane()
        val question = displayed()
        assertEquals("Question 4 of 4", question.parts?.step)
        showTerminalQuestionAlert(question)
        try {
            val terminal = readTerminal()
            afterSend = "❯ "
            vm.sendTerminalKeys("p1", terminal, "enter")
            drainUntil { sent == listOf("enter") && vm.paneText == "❯ " }
            assertNull(vm.cardParse("p1", vm.paneText))
            assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
            assertFalse(terminalAlertIsShowing())
        } finally {
            clearTerminalAlert()
        }
    }

    @Test fun terminalEnterClearsTheCardAndWaitingBadgeFromLiveScreen() = fixture("claude", firstStep) {
        readSelectedPane()
        assertNotNull(vm.cardParse("p1", vm.paneText))
        showTerminalQuestionAlert(shown())
        try {
            val terminal = readTerminal()
            recentResult = ExecResult(0, firstStep, "")
            afterSend = "❯ "
            vm.sendTerminalKeys("p1", terminal, "enter")
            drainUntil { sent == listOf("enter") && vm.paneText == "❯ " }
            assertNull("the live terminal no longer asks a question", vm.cardParse("p1", vm.paneText))
            assertNotEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
            assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
            assertFalse(terminalAlertIsShowing())
            readSelectedPane()
            assertNull("old recent output cannot reopen the answered prompt", vm.cardParse("p1", vm.paneText))
        } finally {
            clearTerminalAlert()
        }
    }

    @Test fun vibeCardAnswerRefusesFocusChangeAfterTheLiveRead() {
        val modal = checkNotNull(javaClass.getResource("/screens/vibe/derived-2.2.11/release.ansi")).readText()
        fixture("vibe", modal) {
            readSelectedPane()
            val card = displayed()
            beforeCheck = { frame = modal.replace("\u001B[48;5;7m", "") }
            answer(card, index = 1)
            assertTrue(sent.isEmpty())
            assertEquals(1, cardChecksRefused)
        }
        fixture("vibe", modal) {
            readSelectedPane()
            val card = displayed()
            afterSend = "> "
            answer(card, index = 1)
            assertEquals(listOf("right enter"), sent)
        }
    }

    @Test fun vibeTerminalEnterClearsTheReleaseCardOnceTheComposerReturns() {
        val modal = checkNotNull(javaClass.getResource("/screens/vibe/derived-2.2.11/release.ansi")).readText()
        fixture("vibe", modal) {
            readSelectedPane()
            assertTrue(shown().vibeRelease)
            val terminal = readTerminal()
            recentResult = ExecResult(0, modal, "")
            afterSend = "> "
            vm.sendTerminalKeys("p1", terminal, "enter")
            drainUntil { sent == listOf("enter") && vm.paneText == "> " }
            assertNull(vm.cardParse("p1", vm.paneText))
            assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
        }
    }

    @Test fun openCodeTerminalEnterClearsPermissionWhenTheLiveToolStarts() =
        fixture("opencode", focusedPermission("Allow once")) {
            readSelectedPane()
            val question = shown()
            assertTrue(question.openCodePermission)
            showTerminalQuestionAlert(question)
            try {
                val terminal = readTerminal()
                recentResult = ExecResult(0, frame, "")
                afterSend = openCodeToolRunning
                vm.sendTerminalKeys("p1", terminal, "enter")
                drainUntil { sent == listOf("enter") && vm.paneText == openCodeToolRunning }
                assertNull(vm.cardParse("p1", vm.paneText))
                assertEquals(Status.WORKING, vm.shownStatus(checkNotNull(vm.pane("p1"))))
                assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
                assertFalse(terminalAlertIsShowing())

                frame = openCodeComposer
                readTerminal()
                assertNull("the previous permission remains only in recent history", vm.cardParse("p1", vm.paneText))
                assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
            } finally {
                clearTerminalAlert()
            }
        }

    @Test fun openCodeTerminalEnterClearsPermissionOnCapturedSpinnerAndComposerLayout() =
        fixture("opencode", focusedPermission("Allow once")) {
            readSelectedPane()
            val permission = shown()
            showTerminalQuestionAlert(permission)
            try {
                val terminal = readTerminal()
                val after = res("/screens/opencode/derived-2.2.11/after-enter-working.txt").trimEnd()
                recentResult = ExecResult(0, frame, "")
                afterSend = after
                vm.sendTerminalKeys("p1", terminal, "enter")
                drainUntil { sent == listOf("enter") && vm.paneText == after }
                assertNull(vm.cardParse("p1", vm.paneText))
                assertEquals(Status.WORKING, vm.shownStatus(checkNotNull(vm.pane("p1"))))
                assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
                assertFalse(terminalAlertIsShowing())
            } finally {
                clearTerminalAlert()
            }
        }

    @Test fun openCodeTerminalEnterPreservesTheLivePermissionAndRefusesAReusedViewport() =
        fixture("opencode", focusedPermission("Reject")) {
            readSelectedPane()
            showTerminalQuestionAlert(shown())
            try {
                val terminal = readTerminal()
                val before = recentReads
                vm.sendTerminalKeys("p1", terminal, "down", "enter")
                drainUntil { sent == listOf("down enter") && recentReads > before }
                assertNotNull(vm.cardParse("p1", vm.paneText))
                assertTrue(terminalAlertIsShowing())
                frame = openCodeToolRunning
                afterGuardedVisibleRead = { this.terminal = "replacement-terminal" }
                readTerminal()
                assertNotNull(vm.cardParse("p1", vm.paneText))
                assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
                assertTrue(terminalAlertIsShowing())
            } finally {
                clearTerminalAlert()
            }
        }

    @Test fun terminalAnswerKeepsTheOldCardUntilASecondLiveQuestionArrives() = fixture("claude", firstStep) {
        readSelectedPane()
        val first = shown()
        showTerminalQuestionAlert(first)
        try {
            val terminal = readTerminal()
            val before = recentReads
            vm.sendTerminalKeys("p1", terminal, "enter")
            drainUntil { sent == listOf("enter") && recentReads > before }
            assertNotNull(vm.cardParse("p1", vm.paneText))
            assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
            assertTrue(terminalAlertIsShowing())
            recentResult = ExecResult(0, firstStep, "")
            frame = firstStep.replace("Which option", "Which layout")
            assertNotEquals(first.question, shown().question)
            readTerminal()
            readTerminal()
            assertEquals(shown().question, vm.cardParse("p1", vm.paneText)?.question)
            assertFalse(terminalAlertIsShowing())
        } finally {
            clearTerminalAlert()
        }
    }

    @Test fun terminalAnswerCannotClearItsCardFromAnUnverifiedViewport() = fixture("claude", firstStep) {
        readSelectedPane()
        val shownFrame = readTerminal()
        val before = recentReads
        vm.sendTerminalKeys("p1", shownFrame, "enter")
        drainUntil { sent == listOf("enter") && recentReads > before }
        frame = "❯ "
        recentResult = ExecResult(0, firstStep, "")
        liveScrollOffset = 2
        readTerminal()
        assertNotNull(vm.cardParse("p1", vm.paneText))
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
        liveScrollOffset = 0
        afterGuardedVisibleRead = { terminal = "different-terminal" }
        readTerminal()
        assertNotNull(vm.cardParse("p1", vm.paneText))
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
    }

    @Test fun terminalAnswerKeepsTheCardOnAnUnrecognizedRedrawEvenWithIdleStatus() = fixture("claude", firstStep) {
        readSelectedPane()
        val terminal = readTerminal()
        val before = recentReads
        vm.sendTerminalKeys("p1", terminal, "enter")
        drainUntil { sent == listOf("enter") && recentReads > before }
        status(Status.IDLE)
        frame = "A partial redraw"
        readTerminal()
        assertNotNull(vm.cardParse("p1", vm.paneText))
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
    }

    @Test fun terminalLetterAnswerClearsCodexAndGivesLiveScreenAuthority() = fixture("codex", command("alpha-beta")) {
        readSelectedPane()
        val terminal = readTerminal()
        recentResult = ExecResult(0, frame, "")
        afterSend = "› "
        vm.sendTerminalKeys("p1", terminal, "y")
        drainUntil { sent == listOf("y") && vm.paneText == "› " }
        assertNull(vm.cardParse("p1", vm.paneText))
        assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
    }

    @Test fun terminalClearedBadgeStaysClearInPaneListUntilTheNextLiveQuestion() = fixture("claude", firstStep) {
        readSelectedPane()
        val terminal = readTerminal()
        recentResult = ExecResult(0, firstStep, "")
        afterSend = "❯ "
        vm.sendTerminalKeys("p1", terminal, "enter")
        drainUntil { sent == listOf("enter") && vm.openQuestionCount() == 0 }
        vm.screen = Screen.Tabs
        assertEquals("stale native blocked status is not another question", 0, vm.openQuestionCount())
        frame = firstStep.replace("Which option", "Which layout")
        pollTerminalPaneStatus()
        drainUntil { vm.openQuestionCount() == 1 }
    }

    @Test fun ordinaryOutputAfterClaudeAnswerIsNotAnotherQuestion() =
        verifyPostAnswerOutput("claude", firstStep, "enter")

    @Test fun ordinaryOutputAfterCodexApprovalIsNotAnotherQuestion() =
        verifyPostAnswerOutput("codex", command("alpha-beta"), "y")

    private fun verifyPostAnswerOutput(agent: String, card: String, key: String) = fixture(agent, card) {
        // Recent output retains the old dialog while the agent starts printing its answer.
        val prose = "The report now answers this heading:\nWhich format should the example use?"
        afterSend = "$card\n\n$prose"
        answer(shown())
        assertEquals(listOf(key), sent)
        assertEquals(Status.NEEDS_YOU, vm.pane("p1")?.status)
        assertEquals(afterSend, vm.paneText)
        assertNull("post-answer prose must not produce a card",
            detectPrompt(vm.paneText, blocked = true, agent = agent))
    }

    @Test fun punctuationOnlyChangeInApprovalIsNotAlreadyAnswered() = fixture("codex", command("alpha-beta")) {
        val first = shown()
        answer(first)
        assertEquals(listOf("y"), sent)
        frame = command("alphabeta")
        val next = shown()
        assertNotEquals(first.approvalIdentity, next.approvalIdentity)
        assertTrue(QuestionLog.sameCard(QuestionLog.signature(first), QuestionLog.signature(next)))
        answer(next)
        assertEquals(vm.notice, listOf("y", "y"), sent)
    }

    @Test fun aFreshNotificationAnswerIsNotBlockedByThePreviousAppAnswer() = fixture("claude", firstStep) {
        answer(shown())
        // The button identifies the new option, not just a question shared with the earlier step.
        frame = secondStep.replace("1. Blue", "1. Green")
        alert(shown())
        assertEquals(listOf("enter", "enter"), sent)
    }

    @Test fun answeredNotificationRefreshesASecondQuestionWithoutAStatusChange() = fixture("claude", firstStep) {
        watchAnsweredSuccessor(secondStep)
    }

    @Test fun oldNotificationCannotAnswerAnIndistinguishableButtonOnTheNextStep() = fixture("claude", firstStep) {
        val first = shown()
        answer(first)
        frame = secondStep
        alert(first)
        assertEquals(listOf("enter"), sent)
    }

    @Test fun unchangedCardStillRejectsSequentialDuplicateAfterHighlightMoves() = fixture("claude", firstStep) {
        val first = shown()
        answer(first)
        frame = firstStep.replace("❯ 1. Blue", "  1. Blue").replace("  2. Orange", "❯ 2. Orange")
        val redraw = shown()
        assertNotEquals(first.selected, redraw.selected)
        answer(redraw)
        assertEquals(listOf("enter"), sent)
        assertTrue(vm.notice.orEmpty().startsWith("Already answered."))
    }

    @Test fun approvalFromRecentAnsiCanBeAnsweredOnTheSameLiveCard() = fixture("codex", command("alpha-beta")) {
        // Herdr's plain visible reader trims each row's end. Its ANSI reader can retain
        // trailing styled cells, including the spaces on the command row in this frame.
        ansiRows = true
        readSelectedPane()
        val shown = checkNotNull(detectPrompt(vm.paneText, blocked = true, agent = "codex")).forCard()
        assertTrue(shown.choices.isNotEmpty())
        assertFalse(shown.terminalReview)
        assertTrue(shown.parts?.command.orEmpty().endsWith("  "))
        answer(shown, checkNotNull(vm.paneTextTarget))
        assertEquals(vm.notice, listOf("y"), sent)
    }

    @Test fun approvalFromRecentAnsiRefusesChangedCommandWhitespace() = fixture("codex", command("alpha-beta")) {
        ansiRows = true
        readSelectedPane()
        val shown = checkNotNull(detectPrompt(vm.paneText, blocked = true, agent = "codex")).forCard()
        ansiRows = false
        answer(shown, checkNotNull(vm.paneTextTarget))
        assertTrue(sent.isEmpty())
        assertEquals("2.2.8: the card shows the question on screen now", CARD_UPDATED_NOTE, vm.notice)
    }

    @Test fun approvalRecoveredAfterFailedRecentReadCanBeAnswered() =
        verifyRecoveredApproval(ExecResult(1, "", "fixture read failure"), boundedVisible = true)

    @Test fun approvalRecoveredAfterEmptyRecentReadCanBeAnswered() =
        verifyRecoveredApproval(ExecResult(0, "", ""), boundedVisible = false)

    private fun verifyRecoveredApproval(recent: ExecResult, boundedVisible: Boolean) =
        fixture("codex", command("alpha-beta")) {
            recentResult = recent
            ansiRows = true
            readSelectedPane()
            assertEquals(listOf(boundedVisible), visibleReadsWithLineLimit)
            assertTrue(sent.isEmpty())
            val prompt = shownApproval()
            answer(prompt, checkNotNull(vm.paneTextTarget))
            assertEquals(vm.notice, listOf("y"), sent)
            assertTrue(prompt.parts?.command.orEmpty().endsWith("  "))
        }

    @Test fun recoveredApprovalRefusesChangedCommandWhitespace() = fixture("codex", command("alpha-beta")) {
        recentResult = ExecResult(0, "", "")
        ansiRows = true
        readSelectedPane()
        val prompt = shownApproval()
        ansiRows = false
        answer(prompt, checkNotNull(vm.paneTextTarget))
        assertEquals(vm.notice, emptyList<String>(), sent)
        assertEquals("2.2.8: the card shows the question on screen now", CARD_UPDATED_NOTE, vm.notice)
    }

    @Test fun staleApprovalCannotAnswerTheNewCommand() = fixture("codex", command("alpha-beta")) {
        val stale = shown()
        frame = command("alphabeta")
        answer(stale)
        assertTrue(sent.isEmpty())
        assertEquals("2.2.8: the card shows the question on screen now", CARD_UPDATED_NOTE, vm.notice)
    }

    @Test fun staleTargetCannotAnswerAfterOccupantOrConnectionReplacement() = fixture("claude", firstStep) {
        val old = checkNotNull(vm.cardTarget("p1"))
        val prompt = shown()
        terminal = "t2" // Only the remote metadata has changed.
        answer(prompt, old)
        assertTrue(sent.isEmpty())
        assertEquals("2.2.8: the card shows the question on screen now", CARD_UPDATED_NOTE, vm.notice)

        terminal = "t1"
        connection = Any()
        answer(prompt, old)
        assertTrue(sent.isEmpty())
        assertEquals("2.2.8: the card shows the question on screen now", CARD_UPDATED_NOTE, vm.notice)
    }

    private fun bothMessagingSettings(agent: String, frame: String, test: Fixture.() -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("app", Application.MODE_PRIVATE)
        try {
            for (enabled in listOf(false, true)) {
                fixture(agent, frame) {
                    vm.changeAgentMessaging(enabled)
                    assertEquals(enabled, vm.agentMessagingEnabled)
                    test()
                }
            }
        } finally {
            prefs.edit().remove("agentMessagingEnabled").apply()
        }
    }

    @Test fun kiroTerminalAnswerClearsMigrationCardFromTheVerifiedComposer() =
        fixture("kiro", res("/screens/kiro/derived-2.2.11/v3-migration.txt").trimEnd()) {
            readSelectedPane()
            val question = shown()
            assertEquals("Kiro CLI 3.0 is here", question.question)
            assertEquals(3, question.choices.size)
            showTerminalQuestionAlert(question)
            try {
                val terminal = readTerminal()
                recentResult = ExecResult(0, frame, "")
                afterSend = "❯ ask a question or describe a task ↵"
                vm.sendTerminalKeys("p1", terminal, "down", "enter")
                drainUntil { sent == listOf("down enter") && vm.paneText == afterSend }
                assertNull(vm.cardParse("p1", vm.paneText))
                assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
                assertFalse(terminalAlertIsShowing())
            } finally {
                clearTerminalAlert()
            }
        }

    @Test fun copilotAllowAllWaitsForConfirmation() =
        fixture("copilot", res("/screens/copilot/derived-2.2.11/permission-mode.txt").trimEnd()) {
            readSelectedPane()
            val question = displayed()
            answer(question, index = 2)
            assertTrue(sent.isEmpty())
            assertEquals("Allow All", checkNotNull(vm.approveAllPending).ask.label)
            vm.cancelApproveAll()
            assertTrue(sent.isEmpty())
            answer(question, index = 2)
            vm.confirmApproveAll()
            drainUntil { !vm.answerInFlight("p1") }
            assertEquals(listOf("down down enter"), sent)
        }

    @Test fun copilotTerminalAnswerClearsPermissionModeCardFromVerifiedComposer() =
        fixture("copilot", res("/screens/copilot/derived-2.2.11/permission-mode.txt").trimEnd()) {
            readSelectedPane()
            showTerminalQuestionAlert(displayed())
            try {
                val terminal = readTerminal()
                recentResult = ExecResult(0, frame, "")
                afterSend = "╻" + "▄".repeat(24) + "\n┃\n╹" + "▀".repeat(24) +
                    "\n← open sidebar · Interactive · Assisted Permissions · / commands"
                vm.sendTerminalKeys("p1", terminal, "down", "enter")
                drainUntil { sent == listOf("down enter") && vm.paneText == afterSend }
                assertNull(vm.cardParse("p1", vm.paneText))
                assertFalse(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
                assertFalse(terminalAlertIsShowing())
            } finally {
                clearTerminalAlert()
            }
        }

    private fun fixture(agent: String, frame: String, test: Fixture.() -> Unit) {
        val f = Fixture(agent, frame)
        try {
            f.test()
        } finally {
            f.vm.clearForTest()
            // Finish queued Compose snapshot dispatch before Robolectric resets the shared looper.
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Fixture(private val agent: String, var frame: String) : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val server = Server(id = "fixture", name = "Fixture", host = "127.0.0.1", port = 1, user = "fixture", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        val sent = mutableListOf<String>()
        var failAfterSend = false
        var afterSend: String? = null
        var ansiRows = false
        var terminal = "t1"
        var liveScrollOffset = 0
        var recentReads = 0
        var recentResult: ExecResult? = null
        val visibleReadsWithLineLimit = mutableListOf<Boolean>()
        var guardedVisibleReads = 0
        var afterGuardedVisibleRead: (() -> Unit)? = null
        var spoken = ""
        private var speechDone: (() -> Unit)? = null
        val voice = VoiceControls(vm, { _, text, _, done -> spoken = text; speechDone = done }, { false })
        fun speak(command: String) = runBlocking { voice.handle(command) }
        fun completeSpeech() { speechDone?.invoke(); speechDone = null }

        init {
            field("herdr").set(vm, Herdr(ssh, server.herdrPath, Backend.TUI, null, this))
            state("current", server)

            status(Status.NEEDS_YOU)
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            vm.screen = Screen.Agent("p1")
            shadowOf(Looper.getMainLooper()).idle()
        }

        private var statusNow = Status.NEEDS_YOU
        private var sessionNow: String? = null
        fun status(status: Status, scrollOffset: Int = 0, sessionId: String? = null) {
            statusNow = status
            sessionNow = sessionId
            state("panes", listOf(
            Pane("p1", "tab", "w1", agent, status, "", "/fixture", sessionId = sessionId, scrollOffset = scrollOffset, terminalId = terminal)
            ))
        }

        fun gatewaySnapshot(json: String) = state("hermesLiveStates",
            com.billmongan.herdrdroid.hermes.HermesLiveState.fromResult(org.json.JSONObject(json)))

        fun shown() = checkNotNull(detectPrompt(frame, blocked = true, agent = agent)).forCard()
        fun displayed() = checkNotNull(detectPrompt(vm.paneText, blocked = false, agent = agent)).forCard()
        fun shownApproval() = checkNotNull(detectPrompt(vm.paneText, blocked = true, agent = "codex")).forCard().also {
            assertTrue(it.choices.isNotEmpty())
            assertFalse(it.terminalReview)
        }
        fun readTerminal(): AppViewModel.TerminalFrame {
            runBlocking { vm.readTerminalPane("p1") }
            return checkNotNull(vm.terminalFrame("p1"))
        }
        fun pollTerminalPaneStatus() {
            val h = field("herdr").get(vm) as Herdr
            vm.noticeChanges(server.id, server.label, listOf(checkNotNull(vm.pane("p1")))) { h }
        }
        fun showTerminalQuestionAlert(card: Prompt) {
            val app = vm.getApplication<Application>()
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            com.billmongan.herdrdroid.notify.Notify.channels(app)
            com.billmongan.herdrdroid.notify.Notify.needsYou(app, server.id, server.label, "p1", agent,
                card.question, card.choices, agent)
        }
        fun terminalAlertIsShowing() =
            com.billmongan.herdrdroid.notify.Notify.isShowing(vm.getApplication<Application>(), server.id, "p1")
        fun clearTerminalAlert() =
            com.billmongan.herdrdroid.notify.Notify.clear(vm.getApplication<Application>(), server.id, "p1")
        fun readSelectedPane() = runBlocking { vm.readPane("p1") }
        fun answer(prompt: Prompt, target: AppViewModel.CardTarget = checkNotNull(vm.cardTarget("p1")), index: Int = 0) {
            vm.answerChoice("p1", prompt, prompt.choices[index], target = target)
            drainUntil { !vm.answerInFlight("p1") }
        }

        fun alert(prompt: Prompt) {
            val choice = prompt.choices.first()
            val reads = guardedVisibleReads
            val h = field("herdr").get(vm) as Herdr
            assertTrue(AppBus.emit(vm.bindFixtureAlert(NotifyAction(server.id, "p1", choice.keys, choice.pressEnter, prompt.question,
                label = choice.label, agent = agent,
                options = com.billmongan.herdrdroid.notify.alertOptionsSig(prompt.choices),
                identity = com.billmongan.herdrdroid.notify.alertIdentity(prompt)), h)))
            drainUntil { guardedVisibleReads > reads && !vm.answerInFlight("p1") }
        }

        fun watchAnsweredSuccessor(nextFrame: String) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val notify = com.billmongan.herdrdroid.notify.Notify
            notify.channels(app)
            state("watching", true)
            state("foreground", false)
            val h = field("herdr").get(vm) as Herdr
            val blocked = checkNotNull(vm.pane("p1"))
            fun poll() = vm.noticeChanges(server.id, server.label, listOf(blocked)) { h }
            val notifications = checkNotNull(app.getSystemService(android.app.NotificationManager::class.java))
            fun active() = notifications.activeNotifications.single { it.id == (server.id + "|p1").hashCode() }.notification
            val first = shown()
            val choice = first.choices.first()
            val old = vm.bindFixtureAlert(NotifyAction(server.id, "p1", choice.keys, choice.pressEnter,
                first.question, label = choice.label, agent = agent,
                options = com.billmongan.herdrdroid.notify.alertOptionsSig(first.choices),
                identity = com.billmongan.herdrdroid.notify.alertIdentity(first)), h)
            try {
                poll()
                assertTrue(AppBus.emit(old))
                drainUntil { sent.size == 1 && !vm.answerInFlight("p1") }
                assertEquals("Answered", active().extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())

                val reads = guardedVisibleReads
                drainWithTime { guardedVisibleReads > reads }
                assertEquals("Answered", active().extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())

                frame = nextFrame
                val nextPrompt = shown()
                val nextLabel = nextPrompt.choices.first().label
                assertEquals(choice.label, nextLabel)
                assertEquals(first.question, nextPrompt.question)
                assertNotEquals(old.identity, com.billmongan.herdrdroid.notify.alertIdentity(nextPrompt))
                drainWithTime { active().actions?.any { it.title.toString() == nextLabel } == true }
                val next = active().actions.single { it.title.toString() == nextLabel }
                com.billmongan.herdrdroid.notify.ActionReceiver().onReceive(app, android.content.Intent()
                    .putExtra(notify.EXTRA_SERVER, server.id)
                    .putExtra(notify.EXTRA_PANE, "p1")
                    .putExtra("origin", old.origin)
                    .putExtra("keys", old.keys))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf("enter"), sent)
                assertTrue(active().actions.any { it.title.toString() == nextLabel })
                next.actionIntent.send()
                drainUntil { sent.size == 2 && !vm.answerInFlight("p1") }
                assertEquals(listOf("enter", "enter"), sent)
            } finally {
                notify.clear(app, server.id, "p1")
            }
        }

        /** [drainUntil], with the main looper's clock moving on, for answers that wait between keys. */
        fun drainWithTime(done: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            do {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                if (done()) return
                Thread.sleep(1)
            } while (System.nanoTime() < deadline)
            fail("Answer handler did not finish")
        }

        fun drainUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                shadowOf(Looper.getMainLooper()).idle()
                if (done()) return
                Thread.sleep(1)
            } while (System.nanoTime() < deadline)
            fail("Answer handler did not finish")
        }

        override suspend fun bulk(command: String): ExecResult {
            assertTrue(command, command.contains("'pane' 'list'") && command.contains("'tab' 'list'"))
            return ok("""{"result":{"panes":[$pane]}}
                |@@herdrdroid-tabs@@
                |{"result":{"tabs":[]}}
            """.trimMargin())
        }

        /** Runs as the pane's details are read (`pane get`), for what changes during that read. */
        var onPaneGet: (() -> Unit)? = null
        /** 2.2.7: answers waiting on the check before the keys; how many the check refused. */
        var cardChecksRefused = 0
        /** Runs as the check on the computer starts: what changed between the card's last read and the keys. */
        var beforeCheck: (() -> Unit)? = null
        var beforeDraftCheck: (() -> Unit)? = null
        /** 2.2.7: the computer has no python3; how many answers each check took. */
        var pythonMissing = false
        var pythonChecks = 0
        var shellChecks = 0

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            if (expectedConnection != null) assertSame(connection, expectedConnection)
            if ("hashlib.sha256(screen)" !in command && Regex("""'p1' '[0-9a-f]{64}' '-'""").containsMatchIn(command) &&
                command.contains(" && echo ${com.billmongan.herdrdroid.herdr.OCCUPANT_PASSED_MARK} && ") &&
                command.substringAfter(" && echo ${com.billmongan.herdrdroid.herdr.OCCUPANT_PASSED_MARK} && ").startsWith("'/fixture/herdr' 'pane' 'send-keys'")) {
                val send = command.substringAfter(" && echo ${com.billmongan.herdrdroid.herdr.OCCUPANT_PASSED_MARK} && ")
                return immediate(send, timeoutSeconds, expectedConnection)
            }
            if ("hashlib.sha256(screen)" in command) {
                beforeDraftCheck?.also { beforeDraftCheck = null }?.invoke()
                val occupant = Regex("""'p1' '([0-9a-f]{64})' '-'""").find(command)!!.groupValues[1]
                val digest = Regex("""'p1' '([0-9a-f]{64})' && echo""").find(command)!!.groupValues[1]
                val now = com.billmongan.herdrdroid.herdr.cardOccupantDigest(org.json.JSONObject(pane))
                val visible = com.billmongan.herdrdroid.herdr.sha256Hex(ansiFrame().toByteArray())
                if (now != occupant || visible != digest)
                    return ExecResult(com.billmongan.herdrdroid.herdr.CARD_CHANGED_EXIT, com.billmongan.herdrdroid.herdr.CARD_REFUSED_MARK, "")
                val send = command.substringAfter(" && echo ${com.billmongan.herdrdroid.herdr.OCCUPANT_PASSED_MARK} && ")
                val result = immediate(send, timeoutSeconds, expectedConnection)
                return result.copy(out = com.billmongan.herdrdroid.herdr.OCCUPANT_PASSED_MARK + "\n" + result.out)
            }
            // 2.2.7: the check before a card's keys, run here as the computer would: against the
            // pane and screen as they are now, then each send it guards.
            Regex("""(?s)'p1' '([0-9a-f]{64})' '((?:r:[0-9]+:[0-9]+:)?[0-9a-f]{64})'.*\) && (.*)$""").find(command)?.takeIf { "herdrdroid-check" in command }?.let { m ->
                // The Python check, failing over to the shell check (as the command does) when this computer has no python3.
                val python = "def identity():" in command
                if (python) pythonChecks++
                val mark = if (python && pythonMissing) com.billmongan.herdrdroid.herdr.SHELL_CHECK_MARK + "\n" else ""
                if (!python || pythonMissing) shellChecks++
                beforeCheck?.invoke()
                // 2.2.8: the guard's dialog rows (or the whole screen) as the checks on the computer compare them.
                val occupantNow = com.billmongan.herdrdroid.herdr.cardOccupantDigest(org.json.JSONObject(pane))
                if (occupantNow != m.groupValues[1] || !com.billmongan.herdrdroid.herdr.cardGuardScreenMatches(ansiFrame(), m.groupValues[2]) || liveScrollOffset != 0) {
                    cardChecksRefused++
                    return ExecResult(com.billmongan.herdrdroid.herdr.CARD_CHANGED_EXIT, mark + com.billmongan.herdrdroid.herdr.CARD_REFUSED_MARK + "\n", "")
                }
                var last = ok("")
                var echoed = ""
                for (part in m.groupValues[3].split(" && ")) {
                    if (part.startsWith("echo ")) { echoed += part.removePrefix("echo ") + "\n"; continue }
                    last = immediate(part, timeoutSeconds, expectedConnection); if (last.exit != 0) break
                }
                return last.copy(out = mark + echoed + last.out)
            }
            return when {
                command.endsWith("'pane' 'get' 'p1'") -> { onPaneGet?.invoke(); ok("""{"result":{"pane":$pane}}""") }
                command.contains("'pane' 'read' 'p1' '--source' 'recent'") -> { recentReads++; recentResult ?: ok(ansiFrame()) }
                command.contains("'pane' 'read' 'p1' '--source' 'visible'") -> {
                    visibleReadsWithLineLimit += command.contains("'--lines'")
                    if (expectedConnection != null) {
                        guardedVisibleReads++
                        afterGuardedVisibleRead?.also { afterGuardedVisibleRead = null }?.invoke()
                    }
                    ok(if (command.endsWith("'--format' 'ansi'")) ansiFrame() else frame)
                }
                command.contains("def occupant():") && command.endsWith("'/fixture/herdr' 'p1' '$agent'") -> ok("")
                command.contains("'agent' 'prompt' 'p1' ") && agent == "hermes" -> {
                    sent += "prompt:" + command.substringAfter("'p1' ").removeSurrounding("'")
                    ok("")
                }
                command.contains("'pane' 'run' 'p1' ") -> {
                    sent += "text:" + command.substringAfter("'p1' ").removeSurrounding("'")
                    sent += "enter"
                    afterSend?.let { frame = it }
                    if (failAfterSend) ExecResult(1, "", "fixture lost acknowledgement") else ok("")
                }
                command.contains("'pane' 'send-text' 'p1' ") &&
                    command.contains(" && echo @@herdrdroid-text-pasted@@ && ") -> {
                    val text = command.substringAfter("'p1' ").substringBefore(" && echo @@herdrdroid-text-pasted@@ && ")
                    check(command.endsWith("'pane' 'send-keys' 'p1' 'enter'"))
                    sent += "text:" + text.removeSurrounding("'")
                    sent += "enter"
                    afterSend?.let { frame = it }
                    if (failAfterSend) ExecResult(1, "@@herdrdroid-text-pasted@@", "fixture lost acknowledgement")
                    else ok("@@herdrdroid-text-pasted@@")
                }
                command.contains("'pane' 'send-text' 'p1' ") -> {
                    sent += "text:" + command.substringAfter("'p1' ").removeSurrounding("'")
                    ok("")
                }
                command.contains("'pane' 'send-keys' 'p1' ") -> {
                    sent += command.substringAfter("'p1' ").replace("'", "")
                    afterSend?.let { frame = it }
                    if (failAfterSend) ExecResult(1, "", "fixture lost acknowledgement") else ok("")
                }
                else -> throw AssertionError("Unexpected fixture command: $command")
            }
        }

        /** Rows starting "› " drawn as Codex draws a placeholder: a bold glyph, then faint text. */
        var ansiPlaceholder = false

        /** What a read with styles returns, when set (2.1.0, Muse's colour-only highlight). */
        var ansiOverride: String? = null

        private fun ansiFrame() = if (ansiOverride != null) ansiOverride!! else if (ansiPlaceholder) frame.lineSequence().joinToString("\n") {
            if (it.startsWith("› ")) "\u001B[1m› \u001B[0m\u001B[2m${it.removePrefix("› ")}\u001B[0m" else it
        } else if (!ansiRows) frame else frame.lineSequence().joinToString("\n") {
            if (it.startsWith("$ ")) "$it\u001B[44m  \u001B[0m" else it
        }

        private val pane get() = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"$agent","agent_status":"${when (statusNow) {
            Status.IDLE -> "idle"; Status.DONE -> "done"; Status.WORKING -> "working"; else -> "blocked"
        }}","cwd":"/fixture","terminal_id":"$terminal"${sessionNow?.let { ""","agent_session":{"agent":"$agent","kind":"id","value":"$it"}""" } ?: ""},"scroll":{"offset_from_bottom":$liveScrollOffset}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }

    private companion object {
        fun backgroundAsk(step: String) = checkNotNull(SuccessiveCardAnswerTest::class.java.getResource(
            "/screens/claude/derived-2.2.11/background-ask-$step.txt")).readText().trimEnd()

        fun hooksFixture(name: String) = checkNotNull(SuccessiveCardAnswerTest::class.java.getResource(
            "/screens/codex/derived-hooks-0.172.0/$name.txt")).readText().trimEnd()

        val hooksBrowser = hooksFixture("browser")
        val hooksTrusted = hooksBrowser
            .replace("  ⚠ 1 hook needs review before it can run.\n", "")
            .replace("t trust all · enter review · esc close", "enter details · esc close")
        val hooksDetail = """
            PreToolUse hooks
            1 hook needs review before it can run.

            › [ ] 1 Fixture hook

            t trust · esc back
        """.trimIndent()

        val piQuestion = checkNotNull(SuccessiveCardAnswerTest::class.java.getResource(
            "/screens/pi/derived-1.4.9/0502-project-trust.txt")).readText().trimEnd()
        val claudeMulti = screenFixture("0109-askuserquestion-multi-select-review-and--2")
        val claudeNext = checkNotNull(SuccessiveCardAnswerTest::class.java.getResource(
            "/screens/claude/derived-checkbox-2.1.326/next.txt")).readText().trimEnd()
        val claudeReview = screenFixture("0109-askuserquestion-multi-select-review-and-")
        fun screenFixture(name: String) = checkNotNull(SuccessiveCardAnswerTest::class.java.getResource(
            "/screens/claude/derived-1.4.9/$name.txt")).readText().trimEnd()

        val hermesUsage = """
            Session Token Usage
            ───────────────────
            Model: fixture
            Input tokens: 1,024
            Output tokens: 25
            Total tokens: 1,049
            API calls: 2
            Current context: 1,024 / 100,000 (1%)
        """.trimIndent()
        /** The live laptop2 Hermes screen, idle after a cross-server notice (2.1.5), with its styles. */
        val idleNoticeScreen = checkNotNull(SuccessiveCardAnswerTest::class.java.getResource(
            "/screens/hermes/live-2026-11-22-idle-notice/visible.ansi")).readText().replace("\r", "").trimEnd()
        const val hermesComposer = "──────────\n❯\n──────────\n☤ fixture · 1K/100K"
        val openCodeComposer = """
            Should I continue with the report?

            ┃  Ask anything...
            ┃  Build · Fixture Model
            ╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
            ~/fixture              tab agents  ctrl+p commands
        """.trimIndent()

        val openCodeToolRunning = """
            ┃  ⠹ echo sample
            ┃    wrapped…
            ┃
               Press ctrl+b to move running work to the background
        """.trimIndent()

        const val approvalCommand = "/fixture/.venv/bin/python -m pip install fixture"
        val openCodePermission = """
            ┃  △ Permission required
            ┃  # Shell command
            ┃  ${'$'} $approvalCommand
            ┃  Allow once   Always allow   Reject                    ⇆ select  enter confirm
        """.trimIndent()

        // OpenCode colors the selected button but does not change its text.
        fun focusedPermission(focus: String) = listOf("Allow once", "Always allow", "Reject").fold(openCodePermission) { text, label ->
            val color = if (label == focus) 44 else 49
            text.replace(label, "\u001B[${color}m$label\u001B[0m")
        }

        val openCodeQuestion = """
            ┃  Which database should the fixture use?
            ┃
            ┃  >1. SQLite
            ┃   2. Postgres
            ┃   3. Type your own answer
            ┃
            ┃  ↑↓ select  enter confirm  esc dismiss
        """.trimIndent()

        // Source-derived OpenCode QuestionPrompt: color-only focus, descriptions, and single-select footer.
        fun nativeQuestion(focus: Int) = """
            ┃
            ┃  Which database should the fixture use?
            ┃
            ┃  ${nativeOption(1, "SQLite", focus)}
            ┃     Local database
            ┃  ${nativeOption(2, "Postgres", focus)}
            ┃     Server database
            ┃  ${nativeOption(3, "Type your own answer", focus)}
            ┃
            ┃  ↑↓ select  enter submit  esc dismiss
            ┃
        """.trimIndent()

        private fun nativeOption(i: Int, label: String, focus: Int) =
            if (i == focus) "\u001B[44m$i. $label\u001B[0m" else "$i. $label"

        // Synthetic reconstruction from the reported structural markers, not a live capture.
        val openCodeDrawer = """
            Press ctrl+b to move running work to the background
            Subagents     Shell     Terminals
            No background work
            esc     show inactive ctrl+a tabs
        """.trimIndent()

        val firstStep = """
            ←  ☐ Color  ☐ Size  ✔ Submit  →
            Which option should the sample use?
            ❯ 1. Blue
              2. Orange
              3. Type something.
            Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """.trimIndent()

        val secondStep = firstStep.replace("☐ Color  ☐ Size", "☒ Color  ☐ Size")

        fun command(arg: String) = """
            Would you like to run the following command?
            ${'$'} echo $arg
            › 1. Yes, proceed (y)
              2. No, and tell Codex what to do differently (esc)
            Press enter to confirm or esc to cancel
        """.trimIndent()
    }
}
