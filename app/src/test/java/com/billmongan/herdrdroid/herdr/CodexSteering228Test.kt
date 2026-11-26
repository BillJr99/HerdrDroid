package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSteering228Test {
    private fun capture(name: String) = checkNotNull(javaClass.getResource(
        "/screens/codex/live-2026-11-26-busy/$name.ansi",
    )).readText()

    @Test fun theLiveEmptyWorkingComposerAllowsConfirmedSteering() {
        val screen = capture("empty")
        assertTrue(codexSteeringReady(screen))
        assertEquals(SendNowRoute.FORCE_CONFIRM, sendNowRoute("codex", Status.WORKING, screen))
        assertEquals(null, backgroundStep("codex", screen))
    }

    @Test fun aRealDraftOrMissingWorkingFrameDoesNotAllowSteering() {
        val screen = capture("draft")
        assertTrue(stripAnsi(screen).contains("tab to queue message"))
        assertFalse(codexSteeringReady(screen))
        assertFalse(nativeQueuedSendAvailable("codex", Status.WORKING, screen))
        assertFalse(codexSteeringReady(capture("empty").replace("esc to interrupt", "done")))
    }

    @Test fun steeringAcceptsChatAndRefusesShellCommandsOrControlKeys() {
        assertTrue(codexSteeringText("Please use the smaller interface."))
        assertFalse(codexSteeringText(" /model"))
        assertFalse(codexSteeringText("! echo test"))
        assertFalse(codexSteeringText("hi\tthere"))
        assertFalse(codexSteeringText("hi\nthere"))
    }
}
