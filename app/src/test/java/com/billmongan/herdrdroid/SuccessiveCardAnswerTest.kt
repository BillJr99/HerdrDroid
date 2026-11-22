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
                assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
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
        assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
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
        assertTrue("a badge correction is not send authorization", vm.hasPendingQuestion(pane))
        status(Status.NEEDS_YOU, sessionId = "pane-stored-session")
        assertEquals(Status.NEEDS_YOU, vm.shownStatus(checkNotNull(vm.pane("p1"))))
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
        for (agent in listOf("opencode", "opencode-college")) fixture(agent, openCodePermission) {
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
        for (status in listOf(Status.DONE, Status.IDLE)) fixture("opencode-college", v232) {
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

    @Test fun nativeOpenCodeRejectDoesNotConfirmTheHighlightedApproval() = fixture("opencode", openCodePermission) {
        readSelectedPane()
        val card = displayed()
        frame = focusedPermission("Allow once")
        // Reject is the third button since 2.1.2 shows Always allow too.
        answer(card, checkNotNull(vm.paneTextTarget), index = 2)
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
        for (agent in listOf("opencode", "opencode-college")) fixture(agent, nativeQuestion(1)) {
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
            assertTrue(vm.notice.orEmpty().startsWith("Not sent."))
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
        var liveScrollOffset = 0
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

        private var statusNow = Status.NEEDS_YOU
        fun status(status: Status, scrollOffset: Int = 0, sessionId: String? = null) {
            statusNow = status
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
                command.contains("'agent' 'prompt' 'p1' ") && agent == "hermes" -> {
                    sent += "prompt:" + command.substringAfter("'p1' ").removeSurrounding("'")
                    ok("")
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
        }}","cwd":"/fixture","terminal_id":"$terminal","scroll":{"offset_from_bottom":$liveScrollOffset}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }

    private companion object {
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
        const val hermesComposer = "──────────\n❯\n──────────\n☤ fixture · 1K/100K"
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
