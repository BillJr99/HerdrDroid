package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotBusyControls229Test {
    @Test fun pendingFollowUpDoesNotProveImmediateDeliveryOrTaskPromotion() {
        // Native Copilot 1.0.116 with a loopback provider holding the first turn open.
        val screen = checkNotNull(javaClass.getResource(
            "/screens/copilot/captured-1.0.116-busy/after-enter.txt"
        )).readText()
        assertTrue(screen.contains("Synthetic request two"))
        assertTrue(screen.contains("[pending · ↑ recall]"))
        assertTrue(screen.contains("Working ·"))
        assertEquals(SendNowRoute.WAIT_FOR_IDLE, sendNowRoute("copilot", Status.WORKING, screen))
        assertFalse(nativeQueuedSendAvailable("copilot", Status.WORKING, screen))
        assertFalse(foregroundHint("copilot", screen))
    }
}
