package com.billmongan.herdrdroid.diag

import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2.7: Export pane screen's file: both screens as herdr gave them, the agent, its status and the parse result. */
class PaneScreenExportTest {
    private val pane = Pane("w1:p1", "w1:t1", "w1", "claude", Status.NEEDS_YOU, "", "/fixture", tabLabel = "claude-2", scrollOffset = 0)
    private val plain = "Do you want to proceed?\n❯ 1. Yes\n  2. No\n"
    private val ansi = "\u001B[1mDo you want to proceed?\u001B[0m\n\u001B[36m❯ 1. Yes\u001B[0m\n  2. No\n"
    private val at = 1_790_000_000_000L

    @Test fun theBundleHoldsBothScreensTheAgentStatusAndTheDetectedPrompt() {
        val prompt = Prompt("Do you want to proceed?", listOf(Choice("Yes", "1", false), Choice("No", "2", false)), selected = "1")
        val e = PaneScreenExports.build("2.2.7", "minipc2", "w1:p1", pane, "2.1.330", plain, ansi, prompt, at)
        val t = e.text
        assertTrue(t.startsWith("HerdrDroid 2.2.7 pane screen export"))
        assertTrue(t.contains("server: minipc2"))
        assertTrue(t.contains("pane: w1:p1 (tab claude-2)"))
        assertTrue(t.contains("agent: claude (Claude), version 2.1.330"))
        assertTrue(t.contains("status: NEEDS_YOU"))
        assertTrue(t.contains("detected prompt: yes"))
        assertTrue(t.contains("question: Do you want to proceed?"))
        assertTrue(t.contains("choices: 1=Yes | 2=No"))
        assertTrue(t.contains("highlighted: 1"))
        // Each screen exactly as herdr returned it, escape codes and all.
        assertTrue(t.contains("## Visible screen, plain text (4 lines)\n" + plain.trimEnd('\n') + "\n## End of plain text"))
        assertTrue(t.contains(ansi.trimEnd('\n') + "\n## End of ANSI"))
        assertTrue(t.contains('\u001B'))
        assertTrue(e.fileName, e.fileName.matches(Regex("herdrdroid-pane-w1_p1-\\d{8}-\\d{6}\\.txt")))
        assertTrue(e.fileName.endsWith(".txt"))
        assertEquals(t.toByteArray(Charsets.UTF_8).size, e.size)
    }

    @Test fun noPromptSaysNone() {
        val e = PaneScreenExports.build("2.2.7", "minipc2", "w1:p9", null, null, "$ ", "$ ", null, at)
        assertTrue(e.text.contains("detected prompt: none"))
        assertTrue(e.text.contains("agent: none (?), version unknown"))
        assertFalse(e.text.contains("question:"))
    }

    @Test fun theSizeLabelReadsInBytesThenKilobytes() {
        assertEquals("512 bytes", PaneScreenExports.sizeLabel(512))
        assertEquals("2.0 KB", PaneScreenExports.sizeLabel(2048))
    }

    @Test fun herdrsReadsAreTheVisibleScreenPlainThenAnsiUnchanged() = runBlocking {
        val commands = mutableListOf<String>()
        val fake = object : Herdr.Commands {
            override val connection: Any? = Any()
            override suspend fun bulk(command: String) = immediate(command, 30, null)
            override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
                commands += command
                return ExecResult(0, if ("'--format' 'ansi'" in command) ansi else plain, "")
            }
        }
        val ssh = HerdrSsh("127.0.0.1", 1, "fake", Credentials(null, null), null, { null })
        val h = Herdr(ssh, "/fixture/herdr", Backend.TUI, null, fake)
        val (p, a) = h.exportScreens("w1:p1")
        assertEquals(plain, p)
        assertEquals(ansi, a)
        assertEquals(listOf(
            "'/fixture/herdr' 'pane' 'read' 'w1:p1' '--source' 'visible'",
            "'/fixture/herdr' 'pane' 'read' 'w1:p1' '--source' 'visible' '--format' 'ansi'",
        ), commands)
    }
}
