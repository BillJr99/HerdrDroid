package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/** 2.2.7: the route Send now takes for a queued message, from the pane's status and screen. */
class SendNow227Test {
    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText().replace("\r", "")
    private val working = res("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi")
    private val idle = res("/screens/claude/derived-2.1.330-shells/idle-3-shells.ansi")
    private val withHint = SendNowFixtures.withHint(working)

    @Test fun claudesCapturedHintIsSeenAboveTheInputBoxOnly() {
        assertTrue(claudeBackgroundHint(res("/screens/claude/captured-2.1.330-tall/tall-ask.txt")))
        assertTrue(claudeBackgroundHint(withHint))
        assertFalse(claudeBackgroundHint(working))
        assertFalse(claudeBackgroundHint(idle))
        // Typed into the input box, it is a draft, not the hint.
        assertFalse(claudeBackgroundHint(working.replace("❯", "❯ (ctrl+b to run in background)")))
        // Prose that mentions the key is not the hint row.
        assertFalse(claudeBackgroundHint("Press ctrl+b to run in background if it takes long.\n❯ "))
    }

    @Test fun theRouteFollowsStatusScreenAndAgent() {
        assertEquals(SendNowRoute.NORMAL, sendNowRoute("claude", Status.IDLE, idle))
        assertEquals(SendNowRoute.BACKGROUND_THEN_SEND, sendNowRoute("claude", Status.WORKING, withHint))
        assertEquals(SendNowRoute.FORCE_CONFIRM, sendNowRoute("claude", Status.WORKING, working))
        // The footer's "esc to interrupt" counts as working even when herdr's status lags.
        assertEquals(SendNowRoute.FORCE_CONFIRM, sendNowRoute("claude", Status.IDLE, working))
        for (agent in listOf("codex", "opencode", "grok", "agy", "muse", "pi", "hermes", null)) {
            assertEquals("$agent", SendNowRoute.WAIT_FOR_IDLE, sendNowRoute(agent, Status.WORKING, withHint))
            assertEquals("$agent", SendNowRoute.NORMAL, sendNowRoute(agent, Status.IDLE, "done\n❯ "))
        }
        assertTrue(sendNowWaitText("Codex").contains("stays queued"))
    }
}

/** Shared screens for the 2.2.7 Send now tests. */
object SendNowFixtures {
    /** [working] with a running command's background hint inserted above the spinner row, as Claude Code 2.1.330 draws it. */
    fun withHint(working: String): String {
        val rows = working.lines().toMutableList()
        val at = rows.indexOfFirst { stripAnsi(it).contains("Vibing") }
        rows.addAll(at, listOf("● Bash(sleep 600)", "  ⎿  Running… (7s)", "     (ctrl+b to run in background)", ""))
        return rows.joinToString("\n")
    }
}
