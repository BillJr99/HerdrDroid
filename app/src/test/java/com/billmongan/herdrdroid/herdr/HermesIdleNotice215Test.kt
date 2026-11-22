package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2.1.5 (Bill: "hermes thinks there is a question right now but there is not and so it is stuck"):
 * the live minipc2 Hermes screen, idle after a HerdrDroid cross-server notice and an "ok", with
 * Hermes's styled example prompt in an empty composer. No parser may read a question from it,
 * styled or plain, visible rows or the longer recent read with its "Previous Conversation" box.
 */
class HermesIdleNotice215Test {
    private fun screen(name: String) = checkNotNull(javaClass.getResource("/screens/hermes/live-2026-11-22-idle-notice/$name"))
        .readText().replace("\r", "")

    @Test fun idleScreenAfterTheNoticeHasNoQuestion() {
        for (name in listOf("visible.ansi", "visible.txt", "recent.txt")) {
            val text = screen(name)
            assertNull(name, detectPrompt(text, blocked = false, agent = "hermes"))
            assertNull(name, hermesClarifyFrame(stripAnsi(text)))
            assertNull(name, detectAgentError(text))
        }
    }

    @Test fun styledExamplePromptReadsAsAnEmptyComposer() {
        assertEquals("", inputLineOf(screen("visible.ansi"), "hermes"))
    }

    @Test fun theNoticeOnScreenIsNotAFrame() {
        for (name in listOf("visible.ansi", "recent.txt")) {
            assertEquals(name, emptyList<CrossServerFrame>(), crossServerFrames(stripAnsi(screen(name))))
        }
    }
}
