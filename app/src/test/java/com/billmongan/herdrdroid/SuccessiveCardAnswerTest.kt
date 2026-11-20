package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SuccessiveCardAnswerTest {
    @Test fun openCodeBackgroundDrawerIsNotAQuestionAndDoesNotReceiveChat() =
        fixture("opencode-college", openCodeDrawer) {
            recentResult = ExecResult(0, openCodePermission, "")
            readSelectedPane()
            assertEquals(openCodeDrawer, vm.paneText)
            assertNull(detectPrompt(vm.paneText, true, "opencode-college"))
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
        fixture("opencode", openCodeComposer) {
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
        assertEquals(listOf("Allow once", "Reject"), shown().choices.map { it.label })
        assertEquals(openCodePermission, vm.paneText)
        assertTrue(vm.hasPendingQuestion(checkNotNull(vm.pane("p1"))))
    }

    @Test fun nativeOpenCodeApprovalUsesFreshFocusAndNeverOffersStandingPermission() {
        for (agent in listOf("opencode", "opencode-college")) fixture(agent, openCodePermission) {
            status(Status.WORKING)
            recentResult = ExecResult(0, "Preparing the next step", "")
            readSelectedPane()
            val card = displayed()
            assertEquals(listOf("Allow once", "Reject"), card.choices.map { it.label })
            assertTrue(card.shownParts().command.orEmpty().contains(approvalCommand))
            assertTrue(sent.isEmpty())
            frame = focusedPermission("Always allow")
            answer(card, checkNotNull(vm.paneTextTarget))
            assertEquals(listOf("left enter"), sent)
        }
    }

    @Test fun nativeOpenCodeRejectDoesNotConfirmTheHighlightedApproval() = fixture("opencode", openCodePermission) {
        readSelectedPane()
        val card = displayed()
        frame = focusedPermission("Allow once")
        answer(card, checkNotNull(vm.paneTextTarget), index = 1)
        assertEquals(listOf("esc"), sent)
    }

    @Test fun nativeOpenCodeApprovalRefusesUnverifiedFocusChangedCommandDrawerAndOccupant() =
        fixture("opencode-college", openCodePermission) {
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
        for (agent in listOf("opencode", "opencode-college")) fixture(agent, openCodeQuestion) {
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

    @Test fun voiceStagesVisibleApprovalDirectlyAndSendsOnlyAfterFullReadback() = fixture("codex", command("alpha-beta")) {
        recentResult = ExecResult(0, "Working on the report", "")
        assertTrue(shown().choices.isNotEmpty())
        speak("option 1")
        assertEquals(0, recentReads)
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
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
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
            assertEquals(listOf(boundedVisible), discoveryVisibleReads)
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
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
    }

    @Test fun staleApprovalCannotAnswerTheNewCommand() = fixture("codex", command("alpha-beta")) {
        val stale = shown()
        frame = command("alphabeta")
        answer(stale)
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
    }

    @Test fun staleTargetCannotAnswerAfterOccupantOrConnectionReplacement() = fixture("claude", firstStep) {
        val old = checkNotNull(vm.cardTarget("p1"))
        val prompt = shown()
        terminal = "t2" // Only the remote metadata has changed.
        answer(prompt, old)
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))

        terminal = "t1"
        connection = Any()
        answer(prompt, old)
        assertTrue(sent.isEmpty())
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
    }

    private fun fixture(agent: String, frame: String, test: Fixture.() -> Unit) {
        val f = Fixture(agent, frame)
        try {
            f.test()
        } finally {
            f.vm.viewModelScope.cancel()
            // Finish queued Compose snapshot dispatch before Robolectric resets the shared looper.
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Fixture(private val agent: String, var frame: String) : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val server = Server(id = "fixture", name = "Fixture", host = "unused.invalid", port = 22, user = "fixture", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        val sent = mutableListOf<String>()
        var failAfterSend = false
        var afterSend: String? = null
        var ansiRows = false
        var terminal = "t1"
        var recentReads = 0
        var recentResult: ExecResult? = null
        val discoveryVisibleReads = mutableListOf<Boolean>()
        var guardedVisibleReads = 0
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

        fun status(status: Status, scrollOffset: Int = 0) = state("panes", listOf(
            Pane("p1", "tab", "w1", agent, status, "", "/fixture", scrollOffset = scrollOffset, terminalId = terminal)
        ))

        fun shown() = checkNotNull(detectPrompt(frame, blocked = true, agent = agent)).forCard()
        fun displayed() = checkNotNull(detectPrompt(vm.paneText, blocked = false, agent = agent)).forCard()
        fun shownApproval() = checkNotNull(detectPrompt(vm.paneText, blocked = true, agent = "codex")).forCard().also {
            assertTrue(it.choices.isNotEmpty())
            assertFalse(it.terminalReview)
        }
        fun readSelectedPane() = runBlocking { vm.readPane("p1") }
        fun answer(prompt: Prompt, target: AppViewModel.CardTarget = checkNotNull(vm.cardTarget("p1")), index: Int = 0) {
            vm.answerChoice("p1", prompt, prompt.choices[index], target = target)
            drainUntil { !vm.answerInFlight("p1") }
        }

        fun alert(prompt: Prompt) {
            val choice = prompt.choices.first()
            val reads = recentReads
            assertTrue(AppBus.emit(NotifyAction(server.id, "p1", choice.keys, choice.pressEnter, prompt.question,
                label = choice.label, agent = agent)))
            drainUntil { recentReads > reads && !vm.answerInFlight("p1") }
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

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            if (expectedConnection != null) assertSame(connection, expectedConnection)
            return when {
                command.endsWith("'pane' 'get' 'p1'") -> ok("""{"result":{"pane":$pane}}""")
                command.contains("'pane' 'read' 'p1' '--source' 'recent'") -> { recentReads++; recentResult ?: ok(ansiFrame()) }
                command.contains("'pane' 'read' 'p1' '--source' 'visible'") -> {
                    if (expectedConnection == null) discoveryVisibleReads += command.contains("'--lines'")
                    else guardedVisibleReads++
                    ok(if (command.endsWith("'--format' 'ansi'")) ansiFrame() else frame)
                }
                command.contains("def occupant():") && command.endsWith("'/fixture/herdr' 'p1' '$agent'") -> ok("")
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

        private fun ansiFrame() = if (!ansiRows) frame else frame.lineSequence().joinToString("\n") {
            if (it.startsWith("$ ")) "$it\u001B[44m  \u001B[0m" else it
        }

        private val pane get() = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"$agent","agent_status":"blocked","cwd":"/fixture","terminal_id":"$terminal","scroll":{"offset_from_bottom":0}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }

    private companion object {
        val openCodeComposer = """
            Should I continue with the report?

            ┃  Ask anything...
            ┃  Build · Fixture Model
            ╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
            ~/fixture              tab agents  ctrl+p commands
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
