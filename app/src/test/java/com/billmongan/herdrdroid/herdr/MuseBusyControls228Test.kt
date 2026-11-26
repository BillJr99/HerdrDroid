package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuseBusyControls228Test {
    private fun frame(name: String) =
        checkNotNull(javaClass.getResource("/screens/muse/live-1.4.24-busy/$name.ansi")).readText()

    @Test fun capturedBusyComposerKeepsImmediateAndBackgroundActionsUnavailable() {
        for (name in listOf("busy-empty", "after-enter", "after-second-enter")) {
            val screen = frame(name)
            assertEquals(name, SendNowRoute.WAIT_FOR_IDLE, sendNowRoute("muse", Status.WORKING, screen))
            assertFalse(name, nativeQueuedSendAvailable("muse", Status.WORKING, screen))
            assertFalse(name, foregroundHint("muse", screen))
        }
    }

    @Test fun aSecondEnterLeavesTheSameSingleQueuedInput() {
        for (name in listOf("after-enter", "after-second-enter")) {
            val screen = stripAnsi(frame(name))
            assertTrue(name, screen.contains("◆ Queued input · ↑ to edit"))
            assertEquals(name, listOf("└ Second harmless echo"),
                screen.lines().map(String::trimEnd).filter { it.contains("Second harmless echo") })
        }
    }
}
