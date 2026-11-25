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
