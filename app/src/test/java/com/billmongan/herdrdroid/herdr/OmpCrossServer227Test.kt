package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * 2.2.7 (Bill: "make sure herdrdroid can tell omp about the cross server session"). Oh My Pi draws no
 * cursor, so its blank box was never proven empty and every notice, relayed message and pane-read
 * reply to it was held or refused as a draft. Now a prompt to Oh My Pi (and Copilot) clears the box
 * with their own Ctrl+U in the same command, and the blank box counts as empty for it. Its replies,
 * fenced as Oh My Pi draws them, are read as frames.
 */
class OmpCrossServer227Test {
    private fun screen(path: String) = checkNotNull(javaClass.getResource("/screens/$path")) { path }.readText()
    private val omp = Pane("p1", "t1", "w1", "omp", Status.DONE, "", "/work", scrollOffset = 0, terminalId = "term-1")

    private class Seam : Herdr.Commands {
        override val connection: Any? = Any()
        val writes = mutableListOf<String>()
        override suspend fun bulk(command: String) = ExecResult(0, "", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            writes += command
            return ExecResult(0, "", "")
        }
    }

    private fun client(seam: Seam) = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), "herdr", Backend.TUI, null, seam)

    @Test fun aPromptToOmpClearsItsBoxFirstInTheSameCommand() = runBlocking {
        for (agent in listOf("omp", "copilot")) {
            val seam = Seam()
            client(seam).sendPermitted(omp.copy(agent = agent), "HerdrDroid notice", seam.connection!!, WritePermit())
            val c = seam.writes.single()
            assertTrue(c, c.contains("'pane' 'send-keys' 'p1' 'ctrl+u' && "))
            assertTrue(c.indexOf("ctrl+u") < c.indexOf("'agent' 'prompt' 'p1' 'HerdrDroid notice'"))
        }
        val seam = Seam()
        client(seam).sendPermitted(omp.copy(agent = "claude"), "hi", seam.connection!!, WritePermit())
        assertFalse("no other agent gets it", seam.writes.single().contains("ctrl+u"))
    }

    @Test fun ompsBlankBoxIsEmptyForAPromptAndARelay() = runBlocking {
        val blank = screen("omp/live-2026-11-22-menus/empty.ansi")
        assertEquals("", inputLineOf(blank, "omp", menuWrite = true))
        assertFalse("typed text still holds it", inputLineOf(screen("omp/live-2026-11-22-menus/typed.ansi"), "omp", menuWrite = true).isNullOrEmpty())
        // The relay sends to a blank Oh My Pi box (it refused it as a draft before).
        val sent = mutableListOf<String>()
        val host = object : PromptRelayPort {
            val c = Any()
            override val connectionIdentity get() = c
            override suspend fun pane(id: String, connection: Any) = omp.takeIf { it.id == id }
            override suspend fun hasWork(pane: Pane, connection: Any) = false
            override suspend fun viewport(id: String, connection: Any) = blank
            override suspend fun send(pane: Pane, text: String, connection: Any) { sent += text }
        }
        val relay = PromptRelay(PaneDispatch(), { if (it == "lab") host else null }) { _, _ -> true }
        val target = checkNotNull(relay.bind("lab", omp))
        assertEquals(RelayResult.SENT, relay.send(target, "HerdrDroid notice, for information only.") { true })
        assertEquals(listOf("HerdrDroid notice, for information only."), sent)
    }

    @Test fun ompsFencedRepliesAreReadAsFrames() {
        val s = screen("omp/live-2026-11-24-frames/reply-frames.txt")
        val frames = crossServerFrames(s)
        assertEquals(1, frames.size)
        assertEquals("Hermes on labbox2", frames.single().destination)
        assertEquals("Please run the build and report.", frames.single().text)
        assertTrue("another agent's unmarked rows are not its reply", paneReadFrames(s).isEmpty())
        val reads = paneReadFrames(s, "omp")
        assertEquals(1, reads.size)
        assertEquals("labbox", reads.single().server)
        assertEquals("codex", reads.single().pane)
    }

    /** A frame in Oh My Pi's tool output box, or in a block with other text, is not its own. */
    @Test fun ompFramesOutsideAFencedReplyBlockAreSkipped() {
        val s = screen("omp/live-2026-11-24-frames/reply-frames.txt")
        val boxed = s.replace("   HDREAD1 M4N8RT", "│   HDREAD1 M4N8RT")
        assertTrue(paneReadFrames(boxed, "omp").isEmpty())
        val mixed = s.replace("   HDREAD1 M4N8RT", "   cat notes.txt\n   HDREAD1 M4N8RT")
        assertTrue(paneReadFrames(mixed, "omp").isEmpty())
        val unfenced = s.lines().filterIndexed { i, l -> !(l.trim() == "```" && s.lines().getOrNull(i + 1)?.contains("HDREAD1") == true) }.joinToString("\n")
        assertTrue(paneReadFrames(unfenced, "omp").isEmpty())
    }
}
