package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.8 (Bill, 2026-11-25: "could it indicate what is currently in the foreground vs background"):
 * the Running under page's Foreground and Background marks, from Claude Code's own screen.
 */
class RunPlace228Test {
    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText().replace("\r", "")
    private fun live(agent: String) = res("/screens/$agent/live-2026-11-20-composer/empty.ansi")
    private val working = res("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi")
    private val withHint = SendNowFixtures.withHint(working)

    // ---- The Running under page: Foreground or Background --------------------------------------

    private fun proc(pid: Int, depth: Int, age: Long, ppid: Int = 1) = ProcInfo(pid, ppid, depth, "bash", "bash -c x", age, 0.0, 0)

    @Test fun `claude's helper list names its background subagents`() {
        val s = live("claude")
        assertEquals(listOf("Watching CI workflow run", "Booting fresh emulator via emu_fresh", "Placeholder text audit and fix"),
            claudeBackgroundHelpers(s))
        assertEquals(RunPlace.BACKGROUND, subagentPlace("claude", s, "Watching CI workflow run"))
        assertEquals(RunPlace.FOREGROUND, subagentPlace("claude", s, "Review the diff"))
        assertEquals(RunPlace.UNKNOWN, subagentPlace("claude", working, "Watching CI workflow run"))
        assertEquals(RunPlace.UNKNOWN, subagentPlace("codex", s, "Watching CI workflow run"))
    }

    @Test fun `the newest shell is in the foreground while claude shows its hint`() {
        val procs = listOf(proc(10, 0, 600), proc(11, 0, 7), proc(12, 1, 6, ppid = 11))
        val places = shellPlaces("claude", withHint, procs)
        assertEquals(RunPlace.BACKGROUND, places[10])
        assertEquals(RunPlace.FOREGROUND, places[11])
        assertEquals(RunPlace.FOREGROUND, places[12])
        // No hint: every shell runs in the background.
        assertTrue(shellPlaces("claude", working, procs).values.all { it == RunPlace.BACKGROUND })
        // Just moved with Background: shown in the background at once, before a read confirms it.
        assertEquals(RunPlace.BACKGROUND, shellPlaces("claude", withHint, procs, backgrounded = setOf(11))[11])
        assertEquals(RunPlace.BACKGROUND, shellPlaces("claude", withHint, procs, backgrounded = setOf(11))[10])
        // Other agents, or no screen yet: unknown.
        assertTrue(shellPlaces("opencode", withHint, procs).values.all { it == RunPlace.UNKNOWN })
        assertTrue(shellPlaces("claude", null, procs).values.all { it == RunPlace.UNKNOWN })
    }
}
