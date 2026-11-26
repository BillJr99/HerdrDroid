package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeBusyControls228Test {
    @Test fun busyOpenCodeCapturesKeepForceSendAndBackgroundUnavailable() {
        for (frame in listOf("before", "first", "second")) {
            val path = "/screens/opencode/derived-2.2.8-busy/$frame.txt"
            val screen = checkNotNull(javaClass.getResource(path)).readText()
            assertEquals(SendNowRoute.WAIT_FOR_IDLE, sendNowRoute("opencode", Status.WORKING, screen))
            assertFalse(nativeQueuedSendAvailable("opencode", Status.WORKING, screen))
            assertNotNull(backgroundSupport("opencode", "OpenCode"))
            assertNull(backgroundStep("opencode", screen))
        }
    }
}
