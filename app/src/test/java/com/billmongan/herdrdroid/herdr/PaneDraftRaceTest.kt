package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.1 (2.1.0 acceptance, D5c trial 6): an edit at the computer landed after Send draft's check and
 * before its Enter, and the changed text went. Send draft now reads the box once more right before
 * Enter, inside the dispatch lane with the permit open, and stops if the text is not the one shown.
 */
class PaneDraftRaceTest {
    private val rule = "─".repeat(60)
    private fun hermes(vararg rows: String) =
        (listOf("  ┊ review done", rule) + rows + listOf(rule, " ☤ gpt-6.1-sol · 295K/500K")).joinToString("\n")

    private val shown = Pane("w2:p1", "w2:t1", "w2", "hermes", Status.IDLE, "Hermes", "/home/u", scrollOffset = 0,
        terminalId = "term-1", sessionId = "s-1")
    private val draftText = "Please draft the reply\nabout the vault push"
    private val draftScreen = hermes("❯ Please draft the reply", "about the vault push")
    private val emptyScreen = hermes("❯ ")

    /**
     * The computer's screen. Each viewport read before Enter returns the next of [beforeEnter] (the
     * last one repeats); after Enter it returns [afterEnter]. [events] records every call in order.
     */
    private class Computer(val beforeEnter: List<String?>, val afterEnter: String) : PaneDraftAction.Port {
        val events = mutableListOf<String>()
        val writes = mutableListOf<List<String>>()
        private var reads = 0
        var pane: Pane? = null
        override suspend fun pane(id: String): Pane? { events += "pane"; return pane }
        override suspend fun viewport(id: String): String? {
            events += "viewport"
            if (writes.isNotEmpty()) return afterEnter
            return beforeEnter[minOf(reads++, beforeEnter.size - 1)]
        }
        private fun recordKeys(keys: List<String>, permit: WritePermit) {
            permit.admit()
            events += "keys:" + keys.joinToString(",")
            writes += keys
        }
        override suspend fun checkedKeys(id: String, keys: List<String>, viewport: String, permit: WritePermit): Boolean {
            recordKeys(keys, permit)
            return true
        }
        override suspend fun pause(ms: Long) {}
    }

    private fun computer(vararg beforeEnter: String?, afterEnter: String = emptyScreen) =
        Computer(beforeEnter.toList(), afterEnter).also { it.pane = shown }

    private fun send(c: Computer) = runBlocking {
        PaneDraftAction(PaneDispatch(), WriteGate()).send("srv", shown, draftText, { true }, c)
    }

    @Test fun `an edit between the check and Enter is caught and nothing is typed`() {
        // First read: the text shown. The read right before Enter: the person kept typing.
        val c = computer(draftScreen, hermes("❯ Please draft the reply", "about the vault push v2"))
        assertEquals(PaneDraftAction.Outcome.CHANGED, send(c))
        assertTrue(c.writes.isEmpty())
        assertEquals("Not sent: the draft changed at the computer. Nothing was typed. Check the new text and try again.",
            draftOutcomeText(PaneDraftAction.Outcome.CHANGED, "Hermes", clear = false))
    }

    @Test fun `a box emptied, a question, an unreadable screen or a command list before Enter types nothing`() {
        for ((late, want) in listOf(
            emptyScreen to PaneDraftAction.Outcome.CHANGED,
            "Proceed?\n❯ 1. Yes\n  2. No" to PaneDraftAction.Outcome.REFUSED,
            null to PaneDraftAction.Outcome.REFUSED,
            hermes("❯ Please draft the reply", "about the vault push /c") to PaneDraftAction.Outcome.CHANGED,
        )) {
            val c = computer(draftScreen, late)
            assertEquals(late.toString(), want, send(c))
            assertTrue(late.toString(), c.writes.isEmpty())
        }
    }

    @Test fun `the last read is right before Enter, after the occupant check, and Enter goes once`() {
        val c = computer(draftScreen)
        assertEquals(PaneDraftAction.Outcome.SENT, send(c))
        assertEquals(listOf(listOf("enter")), c.writes)
        val enter = c.events.indexOf("keys:enter")
        // pane, viewport (the check), pane (the occupant), viewport (the last read), Enter.
        assertEquals(listOf("pane", "viewport", "pane", "viewport", "keys:enter"), c.events.subList(0, enter + 1))
    }

    @Test fun `a change after Enter is never followed by a second Enter`() {
        // Enter went, and the box then shows other text: unconfirmed, and never retried.
        val c = computer(draftScreen, afterEnter = hermes("❯ typed at the computer after Enter"))
        assertEquals(PaneDraftAction.Outcome.UNCERTAIN, send(c))
        assertEquals(listOf(listOf("enter")), c.writes)
    }
}
