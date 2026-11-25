package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.8 (Bill, 2026-11-25: "figure out which shell is the foreground shell and be certain, and then
 * mark that"): the Running under page's Foreground and Background marks. A throwaway Claude Code pane
 * (2026-11-25) ran `! sleep 301`, moved it with Ctrl+B, then ran `! sleep 122` in the foreground. Both
 * shells had their own session and process group and no terminal, so /proc can't tell them apart; the
 * screen's foreground command, matched to each shell's command, can.
 */
class RunPlace228Test {
    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText().replace("\r", "")
    private fun probe(name: String) = res("/screens/claude/live-2026-11-25-probe/$name.ansi")

    /** Claude's two shells as the page lists them: each wrapper with its command, and its child. */
    private fun wrapper(pid: Int, cmd: String) = ProcInfo(pid, 1, 0, "bash", "/bin/bash -c source …", 30, 0.0, 0, shell = cmd)
    private fun child(pid: Int, ppid: Int, cmd: String) = ProcInfo(pid, ppid, 1, "sleep", cmd, 30, 0.0, 0)
    private val procs = listOf(wrapper(28, "sleep 301"), child(29, 28, "sleep 301"), wrapper(3, "sleep 122"), child(4, 3, "sleep 122"))

    @Test fun `the screen names the foreground command and the background count`() {
        assertEquals(listOf("sleep 122"), claudeForegroundCommands(probe("claude-fg2")))
        assertEquals(1, claudeBackgroundShellCount(probe("claude-fg2")))
        assertEquals(listOf("sleep 301"), claudeForegroundCommands(probe("claude-fg1")))
        assertEquals(0, claudeBackgroundShellCount(probe("claude-fg1")))
        assertEquals(emptyList<String>(), claudeForegroundCommands(probe("claude-after-ctrlb")))
        assertEquals(1, claudeBackgroundShellCount(probe("claude-after-ctrlb")))
        // A tool call's own row: "● Bash(sleep 600)".
        val tool = SendNowFixtures.withHint(res("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi"))
        assertEquals(listOf("sleep 600"), claudeForegroundCommands(tool))
    }

    @Test fun `one foreground and one background shell are marked for certain`() {
        val p = shellPlaces("claude", probe("claude-fg2"), procs)
        assertEquals(RunPlace.FOREGROUND, p[3]); assertEquals(RunPlace.FOREGROUND, p[4])
        assertEquals(RunPlace.BACKGROUND, p[28]); assertEquals(RunPlace.BACKGROUND, p[29])
    }

    @Test fun `start order doesn't matter, only the command`() {
        // Muse's review: a background shell started after the foreground one is not the foreground one.
        val reordered = listOf(wrapper(50, "sleep 301"), wrapper(10, "sleep 122"))
        val p = shellPlaces("claude", probe("claude-fg2"), reordered)
        assertEquals(RunPlace.FOREGROUND, p[10]); assertEquals(RunPlace.BACKGROUND, p[50])
    }

    @Test fun `anything uncertain is unmarked`() {
        // Two shells run the same command: which one is in the foreground can't be told.
        val twins = listOf(wrapper(1, "sleep 122"), wrapper(2, "sleep 122"))
        assertTrue(shellPlaces("claude", probe("claude-fg2"), twins).values.all { it == RunPlace.UNKNOWN })
        // More shells than the foreground ones plus Claude's background count: the rest are unknown.
        val extra = procs + wrapper(7, "make test")
        val p = shellPlaces("claude", probe("claude-fg2"), extra)
        assertEquals(RunPlace.FOREGROUND, p[3]); assertEquals(RunPlace.UNKNOWN, p[28]); assertEquals(RunPlace.UNKNOWN, p[7])
        // A process Claude didn't start as a shell, another agent, or no screen yet.
        assertEquals(RunPlace.UNKNOWN, shellPlaces("claude", probe("claude-fg2"), listOf(ProcInfo(9, 1, 0, "node", "node x", 1, 0.0, 0)))[9])
        assertTrue(shellPlaces("grok", probe("claude-fg2"), procs).values.all { it == RunPlace.UNKNOWN })
        assertTrue(shellPlaces("claude", null, procs).values.all { it == RunPlace.UNKNOWN })
    }

    @Test fun `after ctrl+b every shell is background, and a just-moved one shows so at once`() {
        val moved = shellPlaces("claude", probe("claude-after-ctrlb"), listOf(wrapper(28, "sleep 301")))
        assertEquals(RunPlace.BACKGROUND, moved[28])
        assertEquals(RunPlace.BACKGROUND, shellPlaces("claude", probe("claude-fg2"), procs, backgrounded = setOf(3))[3])
    }

    @Test fun `a cut command matches by its start`() {
        val cut = stripAnsi(probe("claude-fg2")).replace("! sleep 122", "! sleep 12…")
        assertEquals(listOf("sleep 12…"), claudeForegroundCommands(cut))
        // Eight characters before the cut is enough; two ("sl…") is too few to be sure of.
        assertEquals(RunPlace.FOREGROUND, shellPlaces("claude", cut, procs)[3])
        assertNull(shellPlaces("claude", cut.replace("! sleep 12…", "! sl…"), procs)[3]?.takeIf { it != RunPlace.UNKNOWN })
    }
}
