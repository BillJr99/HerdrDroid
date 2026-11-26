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
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.questions.EntryState
import com.billmongan.herdrdroid.questions.QuestionEntry
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.2.8: answers from the Questions view go through exactly the checks the pane's own card uses:
 * the target bound to the client, connection, server, visit and occupant; a fresh read that must
 * still show the same card; and the check on the computer against the occupant and screen digests
 * of that read before any key is typed.
 */
@RunWith(AndroidJUnit4::class)
class QuestionsViewVm228Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()

    @Test fun aConfirmedEntryIsAnsweredThroughTheCardsCheckOnTheComputer() = fixture {
        val e = readEntry()
        assertEquals(EntryState.READY, e.state)
        assertTrue("two reads confirmed it", visibleReads >= 2)
        answer(e)
        assertEquals(listOf("y"), sent)
        assertEquals("the digest check ran before the key", 1, cardChecks)
    }

    @Test fun agySubagentApprovalSendsOnlyTheGuardedCtrlK() = fixture("agy", agyApproval) {
        val e = readEntry()
        answer(e)
        assertEquals(listOf("ctrl+k"), sent)
        assertEquals(1, cardChecks)
    }

    @Test fun anAgySubagentCommandChangeAtTheHostSendsNothing() = fixture("agy", agyApproval) {
        val e = readEntry()
        beforeCheck = { frame = frame.replace("sample-only", "different-sample") }
        answer(e)
        assertTrue(sent.isEmpty())
        assertEquals(1, cardChecks)
        assertEquals(1, cardChecksRefused)
    }

    @Test fun kiroDenyOnceSendsOnlyTheGuardedN() = fixture("kiro", kiroApproval) {
        val e = readEntry()
        answer(e, choiceIndex = 2)
        assertEquals(listOf("n"), sent)
        assertEquals(1, cardChecks)
    }

    @Test fun anEntryNeedsTheQuestionsViewOpen() = fixture {
        vm.screen = Screen.Home
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(vm.questionsCardTarget("p1"))
        vm.screen = Screen.Questions
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(vm.questionsCardTarget("p1"))
    }

    @Test fun aDialogClosedBetweenTheListAndTheTapSendsNothing() = fixture {
        val e = readEntry()
        frame = "Ran the command.\n› "
        answer(e)
        assertTrue(sent.isEmpty())
        assertEquals(0, cardChecks)
        // 2.2.8 (Grok lane): never "the question changed": the card shows what is on screen now.
        assertEquals(CARD_UPDATED_NOTE, vm.notice)
    }

    @Test fun aScreenChangedAfterTheLastReadIsRefusedOnTheComputer() = fixture {
        val e = readEntry()
        beforeCheck = { frame = command("other") }
        answer(e)
        assertTrue(sent.isEmpty())
        assertEquals(1, cardChecks)
        assertEquals(1, cardChecksRefused)
        assertEquals(CARD_UPDATED_NOTE, vm.notice)
    }

    @Test fun anOccupantChangeAfterTheListSendsNothing() = fixture {
        val e = readEntry()
        terminal = "t2"
        status(Status.NEEDS_YOU)
        val gets = paneGets
        answer(e)
        assertTrue(sent.isEmpty())
        assertEquals("never sent to the computer", 0, cardChecks)
        assertEquals("no retry: not even a fresh read", gets, paneGets)
        assertEquals(CARD_UPDATED_NOTE, vm.notice)
        assertEquals("the entry says why", "Not sent. Another agent or session is in this pane now.",
            vm.questionsFeed.entries.single().note)
        // And the list starts that pane again rather than keep the old buttons.
        runBlocking { vm.questionsFeed.refreshNow() }
        val again = vm.questionsFeed.entries.single()
        assertEquals("t2", again.source.pane.terminalId)
        assertEquals(EntryState.READY, again.state)
    }

    /** 2.2.8 (Codex's re-review 6): the check passed, then the keys failed: maybe sent, on the entry, never resent. */
    @Test fun aCardAnswerFailingAfterTheCheckIsShownAsMaybeSentAndNeverSentAgain() = fixture {
        val e = readEntry()
        failAfterSend = true
        answer(e)
        assertEquals("typed once", listOf("y"), sent)
        assertEquals("the check passed first", 1, cardChecks)
        assertEquals(CARD_MAY_HAVE_SENT, vm.questionsFeed.entries.single().note)
        assertEquals(CARD_MAY_HAVE_SENT, vm.notice)
        // No retry by itself, and a second tap on the same question right away isn't sent twice.
        failAfterSend = false
        drainUntil { !vm.answerInFlight("p1") }
        answer(e)
        assertEquals(listOf("y"), sent)
    }

    @Test fun anEntryGoesStaleOnceTheViewIsLeft() = fixture {
        val e = readEntry()
        vm.screen = Screen.Agent("p1")
        shadowOf(Looper.getMainLooper()).idle()
        vm.screen = Screen.Questions
        shadowOf(Looper.getMainLooper()).idle()
        answer(e)
        assertTrue("a new visit: the old target isn't current", sent.isEmpty())
    }

    @Test fun theEntryShowsAnsweredOnceThePaneMovesOnAndTheBadgeFollows() = fixture {
        val e = readEntry()
        assertEquals(1, vm.openQuestionCount())
        afterSend = "Running npm test…"
        answer(e)
        assertEquals(listOf("y"), sent)
        status(Status.WORKING)
        runBlocking { vm.questionsFeed.refreshNow() }
        assertEquals(EntryState.ANSWERED, vm.questionsFeed.entries.single().state)
        assertEquals(0, vm.openQuestionCount())
    }

    @Test fun aProbeTabIsExcludedByLabelAndInvalidatesAnExistingQuestion() = fixture {
        val e = readEntry()
        assertEquals(1, vm.openQuestionCount())
        status(Status.NEEDS_YOU, "probe-question")
        assertTrue(vm.pane("p1")!!.isProbe)
        assertTrue(vm.visibleQuestions().isEmpty())
        assertEquals(0, vm.openQuestionCount())
        assertNull(vm.questionsCardTarget("p1"))
        answer(e)
        assertTrue(sent.isEmpty())

        val reads = visibleReads
        runBlocking { vm.questionsFeed.refreshNow() }
        assertEquals(reads, visibleReads)

        status(Status.NEEDS_YOU, "work-probe-question")
        assertTrue(!vm.pane("p1")!!.isProbe)
        assertEquals(1, vm.openQuestionCount())
        assertNotNull(vm.questionsCardTarget("p1"))
    }

    @Test fun noPanesNeedingYouIsAnEmptyListAndNoBadge() = fixture {
        status(Status.IDLE)
        runBlocking { vm.questionsFeed.refreshNow() }
        assertTrue(vm.questionsFeed.entries.isEmpty())
        assertEquals(0, vm.openQuestionCount())
    }

    @Test fun anEntryGoesStaleAfterQuestionsToHomeToQuestions() = fixture {
        val e = readEntry()
        vm.screen = Screen.Home
        shadowOf(Looper.getMainLooper()).idle()
        vm.screen = Screen.Questions
        shadowOf(Looper.getMainLooper()).idle()
        answer(e)
        assertTrue("a new visit of the view: the old target isn't current", sent.isEmpty())
        assertEquals(0, cardChecks)
    }

    @Test fun hidingTheViewForgetsItsEntriesAndMakesTheirTargetsStale() = fixture {
        val e = readEntry()
        vm.questionsHidden()
        assertTrue(vm.questionsFeed.entries.isEmpty())
        answer(e)
        assertTrue(sent.isEmpty())
    }

    @Test fun aSentAnswerLeavesNoNoteOnTheEntry() = fixture {
        val e = readEntry()
        answer(e)
        assertEquals(listOf("y"), sent)
        assertNull(note())
        assertTrue(vm.notice.orEmpty().startsWith("Sent"))
    }

    @Test fun onlyARefusalBeforeSendingIsRestatedAsAnOccupantChange() = fixture {
        val e = readEntry()
        val t = e.target as AppViewModel.CardTarget
        terminal = "t2"
        status(Status.NEEDS_YOU)
        assertEquals(OCCUPANT_REFUSAL, vm.questionsRefusal(t, "Not sent. Refresh the question or open the terminal to answer."))
        assertEquals("a maybe-sent answer is never turned into not sent", CARD_MAY_HAVE_SENT, vm.questionsRefusal(t, CARD_MAY_HAVE_SENT))
        val unsure = "Could not confirm delivery. Refresh the question before trying again."
        assertEquals(unsure, vm.questionsRefusal(t, unsure))
    }

    @Test fun everyRefusalBranchReachesTheEntry() = fixture {
        val e = readEntry()
        val p = e.prompt!!
        val notes = mutableListOf<String>()
        // Oh My Pi's Submit and Grok's privacy choice, with no target to answer through.
        vm.answerChoice("p1", p, com.billmongan.herdrdroid.herdr.Choice("Submit", com.billmongan.herdrdroid.herdr.OmpAsk.SUBMIT, false), target = null, feedback = { notes += it })
        vm.answerChoice("p1", p, com.billmongan.herdrdroid.herdr.Choice("Opt in", com.billmongan.herdrdroid.herdr.GROK_PRIVACY_IN, false), target = null, feedback = { notes += it })
        // The target is another pane's.
        vm.answerChoice("p2", p, p.choices.first(), target = e.target as AppViewModel.CardTarget, feedback = { notes += it })
        settle()
        assertEquals(listOf("Not sent. Refresh the question or open the terminal to answer.",
            "Not sent. Refresh the question or open the terminal to answer.", "Not sent. The question belongs to another pane."), notes)
        // No server open.
        state("current", null)
        vm.answerChoice("p1", p, p.choices.first(), target = e.target as AppViewModel.CardTarget, feedback = feedbackFor(e))
        settle()
        assertEquals("Not sent. No server is open.", note())
        assertTrue(sent.isEmpty())
    }

    @Test fun aKeyAndATypedAnswerGoThroughTheCardsCheck() = fixture {
        val e = readEntry()
        val t = e.target as AppViewModel.CardTarget
        vm.answerCardKey(t, e.prompt!!, "esc", "Pressed Esc", feedback = feedbackFor(e))
        settle()
        assertEquals(listOf("esc"), sent)
        assertEquals(1, cardChecks)
        var delivered = false
        vm.answerTyped(t, e.prompt!!, null, "use the other flag", feedback = feedbackFor(e)) { delivered = true }
        settle()
        assertTrue(delivered)
        assertEquals(listOf("esc", "text:use the other flag", "enter"), sent)
        assertEquals(2, cardChecks)
    }

    @Test fun aWatchedServersEntryAndTheSamePaneIdOnAnotherServerHaveNoTarget() = fixture {
        val e = readEntry()
        val elsewhere = e.source.copy(serverId = "other", serverLabel = "Other", current = false)
        assertNull(vm.questionTargetFor(elsewhere))
        assertNull("listed as current but not the open server", vm.questionTargetFor(elsewhere.copy(current = true)))
        // The open server switches to another one with a pane of the same id: the old target answers nothing.
        state("current", Server(id = "fixture-q2", name = "FixtureQ2", host = "127.0.0.2", port = 1, user = "fixq", herdrPath = "/fixture/herdr"))
        answer(e)
        assertTrue(sent.isEmpty())
    }

    @Test fun aScrolledBackPaneSaysWhyAndIsNotReadEveryRound() = fixture {
        scrolled = 4
        runBlocking { vm.questionsFeed.refreshNow() }
        val e = vm.questionsFeed.entries.single()
        assertEquals(EntryState.UNREAD, e.state)
        assertTrue(e.note.orEmpty(), e.note.orEmpty().contains("scrolled back"))
        assertNull(e.target)
        val gets = paneGets
        runBlocking { vm.questionsFeed.refreshNow() }
        assertEquals("not read again before the wait", gets, paneGets)
    }

    @Test fun anOhMyPiApprovalWhileHerdrSaysWorkingIsListedAndAnswered() = fixture("omp", ompBox) {
        status(Status.WORKING)
        assertEquals(0, vm.openQuestionCount())
        runBlocking { vm.questionsFeed.refreshNow() }
        val e = vm.questionsFeed.entries.single()
        assertEquals(EntryState.READY, e.state)
        assertEquals(1, vm.openQuestionCount())
        val deny = e.prompt!!.choices.indexOfFirst { it.label == "Deny" }
        vm.answerChoice("p1", e.prompt!!, e.prompt!!.choices[deny], target = e.target as AppViewModel.CardTarget, feedback = feedbackFor(e))
        settle()
        assertEquals(listOf("down down enter"), sent)
    }

    @Test fun aWorkingOhMyPiPaneWithNoApprovalIsNotListed() = fixture("omp", "Thinking about the build…") {
        status(Status.WORKING)
        runBlocking { vm.questionsFeed.refreshNow() }
        assertTrue(vm.questionsFeed.entries.isEmpty())
        assertEquals(0, vm.openQuestionCount())
    }

    @Test fun aDialogThatKeepsChangingSaysSoWithOpenPane() = fixture {
        var n = 0
        onVisible = { frame = command("step-${n++}") }
        runBlocking { vm.questionsFeed.refreshNow() }
        val e = vm.questionsFeed.entries.single()
        assertEquals(EntryState.UNREAD, e.state)
        assertEquals(com.billmongan.herdrdroid.questions.QuestionsAggregator.KEPT_CHANGING_NOTE, e.note)
        assertNull(e.target)
    }

    @Test fun aHerdrCoreServerSaysWhyWithoutReadingThePane() = fixture(backend = Backend.CORE) {
        runBlocking { vm.questionsFeed.refreshNow() }
        val e = vm.questionsFeed.entries.single()
        assertEquals(EntryState.UNREAD, e.state)
        assertTrue(e.note.orEmpty(), e.note.orEmpty().contains("can't show the pane's screen"))
        assertEquals(0, visibleReads)
    }

    @Test fun aPaneWithNoAgentIdentitySaysWhyWithoutReadingIt() = fixture {
        terminal = null
        status(Status.NEEDS_YOU)
        runBlocking { vm.questionsFeed.refreshNow() }
        val e = vm.questionsFeed.entries.single()
        assertEquals(EntryState.UNREAD, e.state)
        assertTrue(e.note.orEmpty(), e.note.orEmpty().contains("can't tell which agent"))
        assertEquals(0, visibleReads)
        assertEquals(0, paneGets)
    }

    /** Muse's quota warning shows its highlight only in colour: read with styles, the entry answers from it. */
    @Test fun aMuseQuotaWarningKeepsItsColourHighlight() = fixture("muse", museStyled(1)) {
        runBlocking { vm.questionsFeed.refreshNow() }
        val e = vm.questionsFeed.entries.single()
        assertEquals(EntryState.READY, e.state)
        val card = e.prompt!!
        assertTrue(card.museQuota)
        val both = card.choices.first { it.label.startsWith("Both") }
        vm.answerChoice("p1", card, both, target = e.target as AppViewModel.CardTarget, feedback = feedbackFor(e))
        settle()
        assertEquals("moved from the highlight the styled read showed", listOf("down down enter"), sent)
    }

    /** A Claude dialog taller than the screen: rebuilt whole from the history, as its card is. */
    @Test fun aTallClaudeDialogIsRebuiltWhole() = fixture("claude", tallScrolled) {
        recent = tallHistory
        runBlocking { vm.questionsFeed.refreshNow() }
        val e = vm.questionsFeed.entries.single()
        assertEquals(EntryState.READY, e.state)
        val card = e.prompt!!
        assertTrue("the whole dialog, not its visible tail", !card.topOffScreen)
        assertTrue(card.terminalReview)
        assertTrue(card.choices.isEmpty())
        assertNull(e.target)
        val command = checkNotNull(card.shownParts().command).lines()
        assertEquals("cd /home/user/scratch && python3 - <<'EOF'", command.first())
        assertEquals("EOF", command.last())
    }

    private fun fixture(agent: String = "codex", frame: String = command("alpha"), backend: Backend = Backend.TUI, test: Fixture.() -> Unit) {
        val f = Fixture(agent, frame, backend)
        try {
            f.test()
        } finally {
            f.vm.questionsFeed.hide()
            f.vm.viewModelScope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Fixture(val agent: String, var frame: String, backend: Backend) : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val server = Server(id = "fixture-q", name = "FixtureQ", host = "127.0.0.1", port = 1, user = "fixq", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        var terminal: String? = "t1"
        /** The pane's recent history (`--source recent`), when it differs from the screen. */
        var recent: String? = null
        val sent = mutableListOf<String>()
        var afterSend: String? = null
        var failAfterSend = false
        var visibleReads = 0
        var paneGets = 0
        /** Runs at each read of the screen, for a screen that changes between reads. */
        var onVisible: (() -> Unit)? = null
        var scrolled = 0
        var cardChecks = 0
        var cardChecksRefused = 0
        var beforeCheck: (() -> Unit)? = null
        private var statusNow = Status.NEEDS_YOU

        init {
            field("herdr").set(vm, Herdr(ssh, server.herdrPath, backend, null, this))
            state("current", server)
            status(Status.NEEDS_YOU)
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            vm.screen = Screen.Questions
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun status(status: Status, label: String = agent) {
            statusNow = status
            state("panes", listOf(Pane("p1", "tab", "w1", agent, status, "", "/fixture", tabLabel = label, scrollOffset = 0, terminalId = terminal)))
        }

        fun readEntry(): QuestionEntry {
            runBlocking { vm.questionsFeed.refreshNow() }
            val e = vm.questionsFeed.entries.single()
            assertEquals(EntryState.READY, e.state)
            val target = e.target as AppViewModel.CardTarget
            assertSame("the same target the card's own checks compare", vm.questionsCardTarget("p1")?.client, target.client)
            return e
        }

        fun note(): String? = vm.questionsFeed.entries.singleOrNull()?.note

        /** As the screen does: what went wrong is shown on the entry. */
        fun feedbackFor(e: QuestionEntry): (String) -> Unit {
            val t = e.target as? AppViewModel.CardTarget
            return { vm.questionsFeed.note(e.key, vm.questionsRefusal(t, it)) }
        }

        fun settle() { drainUntil { !vm.answerInFlight("p1") }; shadowOf(Looper.getMainLooper()).idle() }

        fun answer(e: QuestionEntry, choiceIndex: Int = 0) {
            val p = e.prompt!!
            val t = e.target as AppViewModel.CardTarget
            // As the screen does: what went wrong is shown on the entry.
            vm.answerChoice("p1", p, p.choices[choiceIndex], target = t, feedback = { vm.questionsFeed.note(e.key, vm.questionsRefusal(t, it)) })
            drainUntil { !vm.answerInFlight("p1") }
            shadowOf(Looper.getMainLooper()).idle()
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

        override suspend fun bulk(command: String): ExecResult = ok("""{"result":{"panes":[$pane]}}
            |@@herdrdroid-tabs@@
            |{"result":{"tabs":[]}}
        """.trimMargin())

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            if (expectedConnection != null) assertSame(connection, expectedConnection)
            Regex("""(?s)'p1' '([0-9a-f]{64})' '((?:r:[0-9]+:[0-9]+:)?[0-9a-f]{64})'.*\) && (.*)$""").find(command)?.takeIf { "herdrdroid-check" in command }?.let { m ->
                cardChecks++
                beforeCheck?.invoke()
                // 2.2.8 (with the Grok lane): the guard's dialog rows (or the whole screen), as the checks on the computer compare them.
                val occupantNow = com.billmongan.herdrdroid.herdr.cardOccupantDigest(org.json.JSONObject(pane))
                if (occupantNow != m.groupValues[1] || !com.billmongan.herdrdroid.herdr.cardGuardScreenMatches(frame, m.groupValues[2], agent)) {
                    cardChecksRefused++
                    return ExecResult(com.billmongan.herdrdroid.herdr.CARD_CHANGED_EXIT, com.billmongan.herdrdroid.herdr.CARD_REFUSED_MARK + "\n", "")
                }
                // The rest runs as the computer would: the passed line, then the keys, stopping at a failure.
                var out = ""
                var last = ok("")
                for (part in m.groupValues[3].split(" && ")) {
                    last = if (part.startsWith("echo ")) ok(part.removePrefix("echo ") + "\n") else immediate(part, timeoutSeconds, expectedConnection)
                    out += last.out
                    if (last.exit != 0) break
                }
                return last.copy(out = out)
            }
            return when {
                command.endsWith("'pane' 'get' 'p1'") -> { paneGets++; ok("""{"result":{"pane":$pane}}""") }
                command.contains("'pane' 'read' 'p1' '--source' 'recent'") -> ok(recent ?: frame)
                command.contains("'pane' 'read' 'p1' '--source' 'visible'") -> { visibleReads++; onVisible?.invoke(); ok(frame) }
                command.contains("'pane' 'send-keys' 'p1' ") -> {
                    sent += command.substringAfter("'p1' ").replace("'", "")
                    afterSend?.let { frame = it }
                    if (failAfterSend) ExecResult(1, "", "fixture lost acknowledgement") else ok("")
                }
                command.contains("'pane' 'send-text' 'p1' ") -> { sent += "text:" + command.substringAfter("'p1' ").removeSurrounding("'"); ok("") }
                else -> ok("")
            }
        }

        private val pane get() = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"$agent","agent_status":"${when (statusNow) {
            Status.IDLE -> "idle"; Status.DONE -> "done"; Status.WORKING -> "working"; else -> "blocked"
        }}","cwd":"/fixture","terminal_id":${terminal?.let { "\"$it\"" } ?: "null"},"scroll":{"offset_from_bottom":$scrolled}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }

    private companion object {
        val kiroApproval = checkNotNull(QuestionsViewVm228Test::class.java.getResource(
            "/screens/kiro/derived-1.4.9/0901-default-page-label-set.txt"
        )).readText()
        val agyApproval = checkNotNull(QuestionsViewVm228Test::class.java.getResource(
            "/screens/agy/derived-2.2.8-subagent-approval/approval.txt"
        )).readText()
        val museQuota = checkNotNull(QuestionsViewVm228Test::class.java.getResource("/screens/muse/derived-2.1.0/quota-fast-full.txt")).readText()

        /** The warning read with styles: option [hi] bold and blue, the others plain (as SuccessiveCardAnswerTest draws it). */
        fun museStyled(hi: Int?): String {
            var n = 0
            return museQuota.lines().joinToString("\n") { row ->
                if (com.billmongan.herdrdroid.herdr.MUSE_QUOTA_TITLES.any { row.trimStart().startsWith(it) } && row.startsWith("  ") && !row.startsWith("    ")) {
                    n++
                    if (n == hi) "  \u001B[1;38;2;120;170;255m${row.trimStart()}\u001B[0m" else "  \u001B[38;2;230;230;230m${row.trimStart()}\u001B[0m"
                } else row
            }
        }

        // The tall Claude permission dialog of TallDialog227Test: its top scrolled off, its history whole.
        val tallScrolled = checkNotNull(QuestionsViewVm228Test::class.java.getResource("/screens/claude/derived-2.1.330-tall/scrolled-ask.txt")).readText()
        val tallHistory: String = run {
            val dashed = tallScrolled.lines().first { it.startsWith("╌") }
            val rule = "─".repeat(dashed.trimEnd().length)
            val hidden = listOf(" │ cd /home/user/scratch && python3 - <<'EOF'", " │ # capture test for a tall permission prompt") +
                (1..45).map { " │ # filler row $it" }
            (listOf("● I'll run the probe now.", "", rule, " Bash command · from the general-purpose agent",
                " Print canned probe text for prompt capture test", dashed) + hidden + tallScrolled.trimEnd('\n').lines()).joinToString("\n")
        }

        val ompBox = checkNotNull(QuestionsViewVm228Test::class.java.getResource("/screens/omp/synthetic-2.2.8-approval/approval-three.ansi")).readText()
        fun command(arg: String) = """
            Would you like to run the following command?
            ${'$'} echo $arg
            › 1. Yes, proceed (y)
              2. No, and tell Codex what to do differently (esc)
            Press enter to confirm or esc to cancel
        """.trimIndent()
    }
}
