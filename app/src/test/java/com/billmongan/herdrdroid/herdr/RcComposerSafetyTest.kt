package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.LinkDown
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RcComposerSafetyTest {
    private val ompStatus = " π > ⬢ model > dir"
    private val shown = Pane("w2:p1", "w2:t1", "w2", "omp", Status.IDLE, "Oh My Pi", "/home/u",
        scrollOffset = 0, terminalId = "term-1", sessionId = "s-1")

    @Test fun completionRowsCannotBecomeDrafts() {
        val line = "─".repeat(30)
        val cases = listOf(
            "omp" to "$ompStatus\n╰─ check @src\n   › @src/main.kt  Main entry point",
            "omp" to "$ompStatus\n╰─ /model fast\n   › /model fast-other  Change model",
            "pi" to "$line\ncheck @src\n$line\n › @src/main.kt  Main entry point\n0.0%/128k (auto)",
            "copilot" to "╻${"▄".repeat(30)}\n┃ check @src\n╹${"▀".repeat(30)}\n › @src/main.kt  Main entry point\n Interactive",
        )
        for ((agent, screen) in cases) assertNull(agent, paneDraftOf(screen, agent))
    }

    @Test fun compoundSgrHintIsNotADraft() {
        for (style in listOf("2;3", "3;38;2;107;114;128")) {
            val screen = "$ompStatus\n╰─        shift+tab \u001b[${style}mto change thinking effort\u001b[0m"
            assertEquals("", AgentComposers.omp(screen))
            assertNull(paneDraftOf(screen, "omp"))
        }
        val typed = "$ompStatus\n╰─ \u001b[0mshift+tab to change thinking effort"
        assertEquals("shift+tab to change thinking effort", paneDraftOf(typed, "omp")?.text)
    }

    @Test fun sendRechecksStatusAfterLastViewportRead() = runBlocking {
        var reads = 0
        var writes = 0
        val port = object : PaneDraftAction.Port {
            override suspend fun pane(id: String) = if (reads == 2) shown.copy(status = Status.WORKING) else shown
            override suspend fun viewport(id: String): String { reads++; return "$ompStatus\n╰─ fix build" }
            override suspend fun checkedKeys(id: String, keys: List<String>, viewport: String, permit: WritePermit): Boolean {
                writes++
                return true
            }
            override suspend fun pause(ms: Long) {}
        }
        val outcome = PaneDraftAction(PaneDispatch(), WriteGate()).send("srv", shown, "fix build", { true }, port)
        assertEquals(PaneDraftAction.Outcome.NOT_SENDABLE, outcome)
        assertEquals(0, writes)
    }

    @Test fun clearReportsLinkLossAndKeepsUncertainOutcomeAfterDispatch() = runBlocking {
        val failure = LinkDown(IllegalStateException("disconnected"))
        for (admitted in listOf(false, true)) {
            var reported: LinkDown? = null
            var attempts = 0
            val port = object : PaneDraftAction.Port {
                override suspend fun pane(id: String) = shown
                override suspend fun viewport(id: String) = "$ompStatus\n╰─ fix build"
                override suspend fun checkedKeys(id: String, keys: List<String>, viewport: String, permit: WritePermit): Boolean {
                    attempts++
                    if (admitted) permit.admit()
                    throw failure
                }
                override suspend fun pause(ms: Long) {}
                override fun linkLost(error: LinkDown) { reported = error }
            }
            val outcome = PaneDraftAction(PaneDispatch(), WriteGate()).clear("srv", shown, "fix build", { true }, port)
            assertEquals(if (admitted) PaneDraftAction.Outcome.UNCERTAIN else PaneDraftAction.Outcome.REFUSED, outcome)
            assertSame(failure, reported)
            assertEquals(1, attempts)
        }
    }
}
