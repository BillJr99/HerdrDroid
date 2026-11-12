package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchTest {
    private fun pane(id: String, agent: String, status: Status, tab: String = agent, cwd: String = "/home/user/agents/$agent", focused: Boolean = false) =
        Pane(id, "t$id", "w2", agent, status, "", cwd, focused, tab)

    private val panes = listOf(
        pane("1", "claude", Status.IDLE),
        pane("2", "codex", Status.IDLE),
        pane("3", "agy", Status.WORKING),
        pane("4", "grok", Status.IDLE),
        pane("5", "opencode", Status.IDLE, tab = "opencode-scoped", cwd = "/home/user/agents/opencode-scoped"),
        pane("6", "opencode", Status.IDLE),
        pane("7", "hermes", Status.NEEDS_YOU),
        pane("8", "pi", Status.IDLE, focused = true),
    )

    @Test
    fun readyFreeAgentsComeFirstAndPaidLast() {
        val order = rankCandidates(panes, { null }, sensitive = false).map { it.pane.id }
        // grok, general opencode, pi (focused, so after the others) are free and ready; then paid ones; busy agy last.
        assertEquals(listOf("4", "6", "8", "1", "2", "3"), order)
        assertTrue("hermes needs you, so it is never offered", "7" !in order)
        assertTrue("the scoped OpenCode never takes ordinary work", "5" !in order)
    }

    @Test
    fun studentWorkGoesOnlyToScopedOpenCode() {
        assertEquals(listOf("5"), rankCandidates(panes, { null }, sensitive = true).map { it.pane.id })
    }

    @Test
    fun anAgentNearItsLimitIsSkipped() {
        val full = QuotaReading(0, listOf(QuotaItem("Weekly", 95.0, null)))
        val order = rankCandidates(panes, { p -> if (p.agent == "grok") full else null }, sensitive = false).map { it.pane.id }
        assertTrue("4" !in order)
    }

    @Test
    fun costTiers() {
        assertEquals(CostTier.PAID, costTier(panes[0]))
        assertEquals(CostTier.FREE, costTier(panes[3]))
        assertEquals(CostTier.MID, costTier(panes[4]))
        assertEquals(CostTier.FREE, costTier(panes[5]))
    }

    @Test
    fun folderListing() {
        val e = parseDir("""{"path":"/home/user","entries":[["agents",true,4096,1],["notes.md",false,120,2]]}""")
        assertEquals("/home/user/agents", e[0].path)
        assertTrue(e[0].isDir)
        assertEquals(120L, e[1].size)
        assertEquals("/x", parseDir("""{"path":"/","entries":[["x",false,1,1]]}""")[0].path)
    }
}
