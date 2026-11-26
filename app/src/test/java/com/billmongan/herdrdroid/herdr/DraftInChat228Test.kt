package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.8 (Bill, 2026-11-25: "when a pane has an unsent draft and I'm in chat view on that pane, I should
 * see it and be able to send it or clear it"): the draft in every agent's box, and its Clear key.
 * Fixtures: each agent's empty box captured live (2026-11-20), with text typed where its cursor sits.
 */
class DraftInChat228Test {

    private val e = "\u001B"
    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText().replace("\r", "")
    private fun live(agent: String) = res("/screens/$agent/live-2026-11-20-composer/empty.ansi")

    private val agents = listOf("claude", "codex", "grok", "agy", "omp", "pi", "opencode", "copilot", "kiro", "vibe", "muse", "hermes")

    /** [agent]'s live empty box with [typed] typed in it, in the terminal's default colour. */
    private fun typed(agent: String, typed: String): String {
        val rows = live(agent).lines().toMutableList()
        fun put(index: Int, from: String, to: String) {
            check(rows[index].contains(from)) { "$agent row $index has no $from" }
            rows[index] = rows[index].replaceFirst(from, to)
        }
        fun last(marker: String) = rows.indexOfLast { it.contains(marker) }
        when (agent) {
            // The placeholder after the prompt goes; the typed text takes its place.
            "claude" -> put(last("❯"), "❯ $e[0m$e[2mstatus of the kanban cards and eta?", "❯ $e[0m$typed")
            "codex" -> put(last("›"), "$e[2mAsk Codex to do anything", "$e[0m$typed")
            "hermes" -> put(last("❯"), "$e[3m$e[38;5;136mPlan a feature, then build it step by step", "$e[0m$typed")
            "kiro" -> put(last("›"), "$e[7m $e[0m$e[38;2;158;158;158mask a question or describe a task ↵", "$typed$e[7m $e[0m")
            "agy" -> put(last(">"), ">$e[0m", "> $e[0m$typed")
            "vibe" -> put(last("> "), "> $e[0m", "> $e[0m$typed")
            "muse" -> put(last("❯ "), "❯ $e[0m", "❯ $e[0m$typed")
            "grok" -> put(last("❯"), "❯ $e[0m$e[38;2;225;225;225m$e[48;2;20;20;20m", "❯ $e[0m$e[38;2;225;225;225m$e[48;2;20;20;20m$typed")
            "pi" -> put(last("$e[7m"), "$e[7m $e[0m", "$typed$e[7m $e[0m")
            "omp" -> put(last("╰─"), "╰─ $e[0m", "╰─ $e[0m$typed")
            "copilot" -> put(last("┃"), "┃$e[0m$e[48;2;20;27;34m", "┃$e[0m$e[48;2;20;27;34m $typed")
            // OpenCode: its text row, the second of the box's three rows above "Build · model".
            "opencode" -> {
                val bars = rows.indices.filter { stripAnsi(rows[it]).trimStart().startsWith("┃") }
                val build = bars.last { stripAnsi(rows[it]).contains("Build") }
                put(build - 2, "$e[48;2;30;30;30m  ", "$e[48;2;30;30;30m  $typed")
            }
            else -> error(agent)
        }
        return rows.joinToString("\n")
    }

    // ---- A: the draft, per agent -------------------------------------------------------------

    @Test fun `no agent's empty box is a draft`() {
        for (a in agents) {
            assertNull(a, paneDraftOf(live(a), a))
        }
        assertNull(paneDraftOf(res("/screens/opencode/live-2026-11-20-second/empty.ansi"), "opencode"))
    }

    @Test fun `text typed in every agent's box is its draft`() {
        // Claude's capture has its helper list and a titled rule: read by the one-row reader instead.
        for (a in agents - "claude") {
            val d = paneDraftOf(typed(a, "fix the build"), a)
            assertNotNull("$a: no draft", d)
            assertEquals(a, "fix the build", d!!.text)
            assertFalse(a, d.menu)
        }
        assertEquals("fix the build", inputLineOf(typed("claude", "fix the build"), "claude"))
    }

    @Test fun `the glyph-less boxes are bounded, so Send draft can be offered`() {
        for (a in listOf("omp", "pi", "copilot", "opencode")) assertTrue(a, paneDraftOf(typed(a, "fix it"), a)!!.bounded)
    }

    @Test fun `the empty box check knows every agent's box`() {
        for (a in listOf("omp", "pi", "copilot", "opencode")) {
            assertTrue(a, paneBoxEmpty(live(a), a))
            assertFalse(a, paneBoxEmpty(typed(a, "x y"), a))
        }
        assertFalse(paneBoxEmpty(null, "codex"))
    }

    // Synthetic menus are based on the real composer frames and the harnesses' option renderers.
    @Test fun `slash menus are not drafts or proven empty composers`() {
        for (agent in listOf("opencode", "omp")) {
            val menu = res("/screens/$agent/derived-2.2.8-draft/slash-menu.txt")
            assertNull(agent, paneDraftOf(menu, agent))
            assertNull(agent, inputLineOf(menu, agent, menuWrite = true))
            assertFalse(agent, paneBoxEmpty(menu, agent))
        }
    }

    @Test fun `literal exit in the editable row is the full draft when the menu is closed`() {
        for (agent in listOf("opencode", "omp")) {
            val draft = res("/screens/$agent/derived-2.2.8-draft/typed-exit.txt")
            assertEquals(agent, "/exit", paneDraftOf(draft, agent)?.text)
        }
        assertEquals("/exit Exit the app", paneDraftOf(typed("opencode", "/exit Exit the app"), "opencode")?.text)
    }

    @Test fun `clear keys per agent`() {
        // 2.2.8 (Bill: "verify Clear live"): agy, kiro, vibe, muse, pi and opencode checked in a throwaway pane.
        for (a in listOf("claude", "codex", "hermes", "grok", "agy", "kiro", "vibe", "muse", "pi", "opencode")) assertEquals(a, "backspace", draftClearKey(a))
        for (a in listOf("omp", "copilot")) assertEquals(a, "ctrl+u", draftClearKey(a))
        for (a in listOf("gemini", "cursor", null)) assertNull("$a", draftClearKey(a))
        // Ctrl+U empties one row, so a draft of several rows has no Clear there.
        assertTrue(clearableDraft("omp", ComposerDraft("one row", true)))
        assertFalse(clearableDraft("omp", ComposerDraft("one\ntwo", true)))
        assertTrue(clearableDraft("claude", ComposerDraft("one\ntwo", true)))
        assertFalse(clearableDraft("claude", ComposerDraft("/c", true, menu = true)))
        assertTrue(clearableDraft("kiro", ComposerDraft("x", true)))
        assertFalse(clearableDraft("gemini", ComposerDraft("x", true)))
    }

    private fun probe(agent: String, name: String) = res("/screens/$agent/live-2026-11-25-probe/$name.ansi")

    @Test fun `drafts typed live in a throwaway pane read as the draft, and the cleared boxes as empty`() {
        // 2.2.8: "alpha beta gamma" typed, then 16 Backspaces (Ctrl+U for omp and copilot), 2026-11-25.
        val names = mapOf("agy" to "agy", "muse" to "muse", "opencode" to "oc", "kiro" to "kiro", "vibe" to "vibe", "pi" to "pi",
            "omp" to "omp", "copilot" to "copilot")
        for ((agent, n) in names) {
            assertEquals(agent, "alpha beta gamma", paneDraftOf(probe(agent, "$n-draft"), agent)?.text)
            assertNull(agent, paneDraftOf(probe(agent, "$n-draft-cleared"), agent))
        }
        for (a in listOf("omp", "pi", "opencode")) assertTrue(a, paneBoxEmpty(probe(a, (if (a == "opencode") "oc" else a) + "-draft-cleared"), a))
    }

    @Test fun `ctrl+u alone leaves text before the cursor, ctrl+e first empties the box`() {
        // Muse's review: Ctrl+U deletes from the cursor to the start of the row (cursor six left of the end).
        assertEquals("gamma", paneDraftOf(probe("omp", "omp-left-ctrlu"), "omp")?.text)
        assertNull(paneDraftOf(probe("omp", "omp-ctrle-ctrlu"), "omp"))
        assertEquals("gamma", paneDraftOf(probe("copilot", "cp-left-ctrlu"), "copilot")?.text)
        assertNull(paneDraftOf(probe("copilot", "cp-ctrle-ctrlu"), "copilot"))
    }

    private val ompPane = Pane("w2:p1", "w2:t1", "w2", "omp", Status.IDLE, "Oh My Pi", "/home/u", scrollOffset = 0,
        terminalId = "term-1", sessionId = "s-1")

    /** An Oh My Pi box over a buffer that Ctrl+U empties. */
    private inner class OmpBox(var text: String, val obeys: Boolean = true) : PaneDraftAction.Port {
        val writes = mutableListOf<List<String>>()
        override suspend fun pane(id: String) = ompPane
        override suspend fun viewport(id: String) = if (text.isEmpty()) live("omp") else typed("omp", text)
        private fun recordKeys(keys: List<String>, permit: WritePermit) {
            permit.admit()
            writes += keys
            if (obeys && keys == LINE_CLEAR_KEYS) text = ""
        }
        override suspend fun checkedKeys(id: String, keys: List<String>, viewport: String, permit: WritePermit): Boolean {
            recordKeys(keys, permit)
            return true
        }
        override suspend fun pause(ms: Long) {}
    }

    @Test fun `clear on oh my pi presses ctrl+e then ctrl+u once and confirms the box is empty`() = runBlocking {
        val box = OmpBox("fix the build")
        assertEquals(PaneDraftAction.Outcome.CLEARED, PaneDraftAction(PaneDispatch(), WriteGate()).clear("srv", ompPane, "fix the build", { true }, box))
        assertEquals(listOf(listOf("ctrl+e", "ctrl+u")), box.writes)
        val stuck = OmpBox("fix the build", obeys = false)
        assertEquals(PaneDraftAction.Outcome.UNCERTAIN, PaneDraftAction(PaneDispatch(), WriteGate()).clear("srv", ompPane, "fix the build", { true }, stuck))
        assertEquals(listOf(listOf("ctrl+e", "ctrl+u")), stuck.writes)
    }

    @Test fun `send draft on oh my pi presses enter once and sees the box empty`() = runBlocking {
        val box = object : PaneDraftAction.Port {
            var sent = false
            override suspend fun pane(id: String) = ompPane
            override suspend fun viewport(id: String) = if (sent) live("omp") else typed("omp", "fix the build")
            private fun recordKeys(keys: List<String>, permit: WritePermit) { permit.admit(); assertEquals(listOf("enter"), keys); sent = true }
            override suspend fun checkedKeys(id: String, keys: List<String>, viewport: String, permit: WritePermit): Boolean {
                recordKeys(keys, permit)
                return true
            }
            override suspend fun pause(ms: Long) {}
        }
        assertEquals(PaneDraftAction.Outcome.SENT, PaneDraftAction(PaneDispatch(), WriteGate()).send("srv", ompPane, "fix the build", { true }, box))
    }

}
