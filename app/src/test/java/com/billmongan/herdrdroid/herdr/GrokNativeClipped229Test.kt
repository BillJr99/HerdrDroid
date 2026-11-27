package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.notify.alertPlan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokNativeClipped229Test {
    @Test fun nativeApprovalWithNoAdditionalHistoryKeepsItsExpansionGate() {
        // Grok 1.0.92 drew this 18-row screen. Its 2,000-line recent read was identical.
        // The final token is mentioned in the user text but is absent from the approval box.
        val frame = checkNotNull(
            javaClass.getResource("/screens/grok/captured-1.0.92-tall/clipped.txt")
        ).readText()
        assertTrue(frame.contains("fixture-a-420"))
        val approval = checkNotNull(detectPrompt(frame, blocked = true, agent = "grok"))
        assertTrue(approval.clipped)
        assertTrue(approval.question.contains("fixture-a-001"))
        assertFalse(approval.question.contains("fixture-a-420"))
        assertTrue(approval.choices.any { it.label == "Yes, proceed" })
        assertTrue(alertPlan(approval).buttons.isEmpty())
    }
    @Test fun delayedNativeRejectionDoesNotTurnTheViewerOrReceiptIntoApproval() {
        fun frame(name: String) = checkNotNull(
            javaClass.getResource("/screens/grok/captured-1.0.92-tall/$name.txt")
        ).readText()

        val stillOpen = checkNotNull(detectPrompt(frame("late-reject"), blocked = true, agent = "grok"))
        assertTrue(stillOpen.clipped)
        assertTrue(alertPlan(stillOpen).buttons.isEmpty())
        assertNull(detectPrompt(frame("viewer-open"), blocked = false, agent = "grok"))
        assertNull(detectPrompt(frame("reject-receipt"), blocked = false, agent = "grok"))
    }
}
