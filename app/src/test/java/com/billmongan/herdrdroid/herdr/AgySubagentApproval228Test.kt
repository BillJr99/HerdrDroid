package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.notify.alertPlan
import com.billmongan.herdrdroid.notify.notificationOffersReply
import org.junit.Assert.*
import org.junit.Test

class AgySubagentApproval228Test {
    private val frame = checkNotNull(javaClass.getResource(
        "/screens/agy/derived-2.2.8-subagent-approval/approval.txt"
    )).readText()

    @Test fun livePanelHasOneApprovalAndNoUnverifiedRejectOrReply() {
        val prompt = checkNotNull(detectPrompt(frame, false, "agy"))
        assertEquals("research needs approval for Bash", prompt.question)
        assertEquals("printf sample-only", prompt.shownParts().command)
        assertEquals(listOf("ctrl+k"), menuKeys(prompt, prompt.choices.single()))
        assertNull(menuKeys(prompt, Choice("Manage", "alt+j", false)))
        assertTrue(alertPlan(prompt).buttons.isEmpty())
        assertFalse(notificationOffersReply(prompt))
        assertTrue(questionRead(frame, prompt, "agy", null, false).complete)
    }

    @Test fun supportedControlKeySpellingsStillRequireTheApprovalHintAndACompleteFrame() {
        for (alias in listOf("ctrl+k", "Ctrl+K", "Ctrl-K", "^K", "C-k")) {
            val screen = frame.replace("ctrl+k", alias)
            val prompt = checkNotNull(agySubagentApproval(screen))
            assertEquals(alias, listOf("ctrl+k"), menuKeys(prompt, prompt.choices.single()))
            assertNull(alias, agySubagentApproval(screen.replace(" approve ", " answer ")))
        }
    }

    @Test fun pendingSubagentQuestionOffersNoApprovalOrManagementKey() {
        val screen = " ┃ research has a question\n"
        val prompt = checkNotNull(detectPrompt(screen, false, "agy"))
        assertEquals("research has a question", prompt.question)
        assertTrue(prompt.terminalReview)
        assertFalse(prompt.agyApproval)
        assertTrue(prompt.choices.isEmpty())
        assertTrue(alertPlan(prompt).buttons.isEmpty())
        assertNull(agySubagentQuestion(screen + "❯ New input"))
        assertNull(agySubagentQuestion(screen.trimStart().removePrefix("┃ ")))
    }

    private fun nativeQuestionScreen(name: String): String = checkNotNull(javaClass.getResource(
        "/screens/agy/captured-1.5.18-native-question/$name.txt"
    )).readText()

    @Test fun nativeParentBannerAboveComposerMatchesOnlyItsOwnBlockedHelper() {
        val screen = nativeQuestionScreen("parent")
        val prompt = checkNotNull(detectPrompt(screen, false, "agy"))
        assertEquals("Which fixture label?", prompt.question)
        assertTrue(prompt.terminalReview)
        assertTrue(prompt.choices.isEmpty())
        assertTrue(alertPlan(prompt).buttons.isEmpty())
        assertNull(agySubagentQuestion(screen.replace("Agent(self:", "Agent(other:")))
        assertNull(agySubagentQuestion(screen.replace("Blocked · AskQuestion", "Running · Work")))
        assertNull(agySubagentQuestion(screen + "\n> another prompt"))
    }

    @Test fun nativePickerShowsQuestionAndOptionsWithoutOfferingKeys() {
        val screen = nativeQuestionScreen("picker")
        val prompt = checkNotNull(detectPrompt(screen, false, "agy"))
        assertEquals("Which fixture label?", prompt.question)
        assertEquals(listOf("Alpha", "Beta", "Write-in..."), prompt.parts?.details)
        assertTrue(prompt.terminalReview)
        assertTrue(prompt.choices.isEmpty())
        assertTrue(alertPlan(prompt).buttons.isEmpty())
        for (clipped in listOf(screen.replace("> 2. ", "  2. "), screen.replace("  3. ", "  4. "))) {
            val review = checkNotNull(detectPrompt(clipped, false, "agy"))
            assertTrue(review.terminalReview)
            assertTrue(review.choices.isEmpty())
        }
    }

    @Test fun nativeManagedApprovalAndItsParentShowOnlyTerminalReview() {
        for (name in listOf("approval-parent", "approval-picker")) {
            val screen = nativeQuestionScreen(name)
            val prompt = checkNotNull(detectPrompt(screen, false, "agy"))
            assertEquals("printf 'fixture-deny\\n'", prompt.parts?.command)
            assertTrue(prompt.terminalReview)
            assertTrue(prompt.choices.isEmpty())
            assertTrue(alertPlan(prompt).buttons.isEmpty())
            assertNull(agySubagentApproval(screen + "\n> another prompt"))
        }
    }

    private fun capturedTransition(name: String): String = checkNotNull(javaClass.getResource(
        "/captures/agy-1.5.18/$name.txt"
    )).readText()

    @Test fun nativeQuestionFocusMovementDoesNotEnableAppActionsAndReceiptClosesCard() {
        val alpha = capturedTransition("question-alpha")
        val beta = nativeQuestionScreen("picker")
        assertTrue(alpha.contains("> 1. Alpha"))
        assertTrue(beta.contains("> 2. Beta"))
        for (screen in listOf(alpha, beta)) {
            val prompt = checkNotNull(detectPrompt(screen, false, "agy"))
            assertTrue(prompt.terminalReview)
            assertTrue(prompt.choices.isEmpty())
            assertEquals(listOf("Alpha", "Beta", "Write-in..."), prompt.parts?.details)
        }
        val answered = capturedTransition("question-answered")
        assertTrue(answered.contains("> Beta"))
        assertNull(detectPrompt(answered, false, "agy"))
    }

    @Test fun nativeApprovalFocusMovementDoesNotEnableAppActionsAndDenialClosesCard() {
        val approve = capturedTransition("deny-approve-focused")
        val deny = nativeQuestionScreen("approval-picker")
        assertTrue(approve.contains("> 1. Yes, approve"))
        assertTrue(deny.contains("> 2. No, deny"))
        for (screen in listOf(approve, deny)) {
            val prompt = checkNotNull(detectPrompt(screen, false, "agy"))
            assertTrue(prompt.terminalReview)
            assertTrue(prompt.choices.isEmpty())
            assertTrue(alertPlan(prompt).buttons.isEmpty())
        }
        assertNull(detectPrompt(capturedTransition("deny-completed"), false, "agy"))
    }

    @Test fun staleAndUnprovenPanelRowsCannotOfferApproval() {
        assertNull(agySubagentApproval(frame + " ❯ New input\n"))
        assertNull(agySubagentApproval(frame.replace(" ┃ ctrl+k", "   ctrl+k")))
        for (partial in listOf(
            frame.replace("printf sample-only", "printf …"),
            frame.replace(" ┃ ───────────", " ┃ Ordinary output"),
            frame.replace(" ┃ ● Bash", "  ┃ ● Bash"),
            frame.lines().drop(1).joinToString("\n"),
        )) {
            val review = checkNotNull(agySubagentApproval(partial))
            assertTrue(review.terminalReview)
            assertTrue(review.choices.isEmpty())
        }
    }

    @Test fun changedCommandInvalidatesBothDisplayedCardAndHostGuard() {
        val changed = frame.replace("sample-only", "new-sample")
        val prompt = checkNotNull(detectPrompt(frame, false, "agy"))
        assertFalse(cardAnswerStillFits(prompt, detectPrompt(changed, false, "agy")))
        val guard = cardGuardScreen(frame, prompt, "agy")
        assertTrue(cardGuardScreenMatches(frame, guard, "agy"))
        assertFalse(cardGuardScreenMatches(changed, guard, "agy"))
    }

    @Test fun workingAgyRemainsWorkingForDispatchWhenItsPanelIsPromoted() {
        val pane = Pane("p1", "tab", "w1", "agy", Status.WORKING, "", "/fixture", tabLabel = "agy")
        val shown = markScreenNeedsYou(listOf(pane), mapOf(pane.id to frame)).single()
        assertEquals(Status.NEEDS_YOU, shown.status)
        assertEquals(Status.WORKING, shown.sendStatus)
        assertEquals(pane, markScreenNeedsYou(listOf(pane), mapOf(pane.id to "ordinary text")).single())
        assertTrue(screenAskable(listOf(pane.copy(tabLabel = "probe-agy"))).isEmpty())
    }
}
