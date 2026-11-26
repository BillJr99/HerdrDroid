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
        assertTrue("native empty composer was not recognized", codexSteeringReady(screen))
        assertEquals(SendNowRoute.FORCE_CONFIRM, sendNowRoute("codex", Status.WORKING, screen))
        assertEquals(null, backgroundStep("codex", screen))
    }

    @Test fun busyComposerKeepsTheDraftSeparateFromItsFooterAndViewportPadding() {
        val empty = capture("empty")
        assertEquals(null, paneDraftOf(empty + "\n  \n", "codex"))
        assertTrue(paneBoxEmpty(empty + "\n  \n", "codex"))
        val draft = capture("draft")
        assertEquals("Follow-up fixture text", paneDraftOf(draft, "codex")?.text)
        val multiline = draft.replaceFirst("Follow-up fixture text", "Follow-up fixture text\n\n  second paragraph")
        assertEquals("Follow-up fixture text\n\nsecond paragraph", paneDraftOf(multiline, "codex")?.text)
        val withoutFooter = draft.substringBefore("Follow-up fixture text") +
            "Follow-up fixture text\n\n  tab to queue message\n"
        assertEquals("Follow-up fixture text\n\ntab to queue message", paneDraftOf(withoutFooter, "codex")?.text)
    }

    @Test fun aRealDraftOrMissingWorkingFrameDoesNotAllowSteering() {
        val screen = capture("draft")
        assertTrue(stripAnsi(screen).contains("tab to queue message"))
        assertFalse("a typed draft", codexSteeringReady(screen))
        assertFalse("the busy menu stays hidden while a draft is present",
            nativeQueuedSendAvailable("codex", Status.WORKING, screen))
        val empty = capture("empty")
        val withoutStatus = empty.replace(" to interrupt", " to dismiss")
        assertFalse(stripAnsi(withoutStatus).contains("esc to interrupt"))
        assertFalse("a changed running hint", codexSteeringReady(withoutStatus))
        assertFalse("typed text with the empty composer's footer",
            codexSteeringReady(empty.replace("Ask Codex to do anything", "Another draft")))
        assertFalse("placeholder words without the native style", codexSteeringReady(stripAnsi(empty)))
        assertFalse("a blank-looking row may contain user spaces",
            codexSteeringReady(empty.replace("Ask Codex to do anything", "     ")))
    }

    @Test fun theSingleEnterCaptureShowsBothFramesOfTheWorkingSpinner() {
        val before = capture("one-enter-before")
        val first = capture("one-enter-enter-one")
        val second = capture("one-enter-enter-two")
        assertTrue(stripAnsi(before).contains("tab to queue message"))
        assertFalse(codexSteeringReady(before))
        assertTrue(stripAnsi(first).contains("◦ Working"))
        assertTrue(stripAnsi(second).contains("• Working"))
        assertTrue(codexSteeringReady(first))
        assertTrue(codexSteeringReady(second))
    }

    @Test fun steeringAcceptsChatAndRefusesShellCommandsOrControlKeys() {
        assertTrue(codexSteeringText("Please use the smaller interface."))
        assertFalse(codexSteeringText(" /model"))
        assertFalse(codexSteeringText("! echo test"))
        assertFalse(codexSteeringText("hi\tthere"))
        assertFalse(codexSteeringText("hi\nthere"))
    }
}
