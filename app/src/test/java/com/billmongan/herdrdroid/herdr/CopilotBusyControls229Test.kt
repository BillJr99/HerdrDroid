package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotBusyControls229Test {
    private fun capture(name: String) = checkNotNull(javaClass.getResource(
        "/screens/copilot/captured-1.0.116-busy/$name.txt"
    )).readText()

    @Test fun pendingFollowUpDoesNotProveImmediateDeliveryOrTaskPromotion() {
        // Native Copilot 1.0.116 with a loopback provider holding the first turn open.
        val screen = capture("after-enter")
        assertTrue(screen.contains("Synthetic request two"))
        assertTrue(screen.contains("[pending · ↑ recall]"))
        assertTrue(screen.contains("Working ·"))
        assertEquals(SendNowRoute.WAIT_FOR_IDLE, sendNowRoute("copilot", Status.WORKING, screen))
        assertFalse(nativeQueuedSendAvailable("copilot", Status.WORKING, screen))
        assertFalse(foregroundHint("copilot", screen))
    }

    @Test fun pendingRecallHintNeverBecomesAQuestionChoice() {
        val screen = capture("after-enter")
        val question = checkNotNull(javaClass.getResource(
            "/screens/copilot/derived-1.4.9/0407-question-ask-user.txt"
        )).readText()
        for (agent in listOf("copilot", "github-copilot")) {
            assertNull(detectPrompt(screen, blocked = true, agent = agent))
            assertNull(detectPrompt(screen, blocked = false, agent = agent))
            assertEquals(listOf("SQLite", "Postgres", "Other (type your answer)"),
                detectPrompt(question, blocked = true, agent = agent)?.choices?.map { it.label })
        }
    }

    @Test fun nativeChordMovedTheTaskButTheAppDoesNotAssumeItIsSafe() {
        val before = capture("chord-before")
        val after = capture("chord-after")
        assertTrue(before.contains("○ Shell Wait for a synthetic test"))
        assertTrue(after.contains("Moving current shell command (shellId: 0) to background."))
        assertFalse(foregroundHint("copilot", before))
        assertFalse(foregroundHint("copilot", after))
    }
}
