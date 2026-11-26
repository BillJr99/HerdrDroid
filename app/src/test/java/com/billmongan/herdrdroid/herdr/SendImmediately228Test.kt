package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * 2.2.8 (Bill, 2026-11-25: "add send immediately to that menu which would background all foreground
 * tasks until it sends"): the Ctrl+B state machine, and which agents it serves.
 */
class SendImmediately228Test {
    private val e = "\u001B"
    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText().replace("\r", "")
    private val working = res("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi")
    private val withHint = SendNowFixtures.withHint(working)

    // ---- B: Send immediately, the state machine ---------------------------------------------


    /** Screens in turn (the last repeats); [taken] after that many reads the goal holds. */
    private class Fake(val screens: List<String?>, val taken: Int = Int.MAX_VALUE, val blockAt: Int = -1,
                       /** What the computer's own check sees when the key goes: the screen at that moment (null: the last read). */
                       val atKey: ((Int) -> String?)? = null) : Backgrounder.Port {
        var reads = 0
        var presses = 0
        val steps = mutableListOf<BackgroundStep>()
        override suspend fun screen(): String? = screens[minOf(reads, screens.size - 1)].also { reads++ }
        override fun blocked(screen: String) = blockAt in 0 until reads
        override fun done(screen: String) = reads > taken
        override suspend fun press(step: BackgroundStep, viewport: String): Boolean {
            val now = atKey?.invoke(reads) ?: screens[minOf(reads - 1, screens.size - 1)]
            if (now == null || now != viewport || !backgroundCheckPasses(step, now)) return false
            presses++; steps += step; return true
        }
        override suspend fun pause(ms: Long) {}
    }

    private fun claude(f: Fake) = Backgrounder("claude", f, fast)

    private val fast = Backgrounder.Limits(maxPresses = 3, maxReads = 12, pollMs = 0)

    @Test fun `ctrl+b each time a foreground command shows, until the message goes`() = runBlocking {
        // A command in the foreground, moved; another one later, moved; then the message is taken.
        val f = Fake(listOf(withHint, working, working, withHint, working, working), taken = 5)
        assertEquals(Backgrounder.Outcome.DONE, claude(f).run())
        assertEquals(2, f.presses)
    }

    @Test fun `nothing in the foreground means no key, only waiting`() = runBlocking {
        val f = Fake(listOf(working), taken = 3)
        assertEquals(Backgrounder.Outcome.DONE, claude(f).run())
        assertEquals(0, f.presses)
        val never = Fake(listOf(working))
        assertEquals(Backgrounder.Outcome.TIMED_OUT, claude(never).run())
        assertEquals(0, never.presses)
    }

    @Test fun `a question or approval stops it before any key`() = runBlocking {
        val f = Fake(listOf(withHint), blockAt = 0)
        assertEquals(Backgrounder.Outcome.BLOCKED, claude(f).run())
        assertEquals(0, f.presses)
    }

    @Test fun `a key that doesn't take is pressed once, then reported`() = runBlocking {
        val f = Fake(listOf(withHint))
        assertEquals(Backgrounder.Outcome.STUCK, claude(f).run())
        assertEquals(1, f.presses)
    }

    @Test fun `presses and reads are bounded`() = runBlocking {
        // A fresh foreground command every other read: at most maxPresses presses.
        val f = Fake(List(40) { if (it % 2 == 0) withHint else working })
        assertEquals(Backgrounder.Outcome.TIMED_OUT, claude(f).run())
        assertEquals(3, f.presses)
        assertTrue(f.reads <= fast.maxReads)
        val blind = Fake(listOf(null))
        assertEquals(Backgrounder.Outcome.UNREADABLE, claude(blind).run())
        assertEquals(0, blind.presses)
    }

    @Test fun `which agents send immediately, and why the others don't`() {
        // 2.2.8 (Bill: "capture screens first"): Claude, Grok and Agy show the move; OpenCode and Muse don't.
        for (a in listOf("claude", "grok", "agy")) assertNull(a, backgroundSupport(a, a))
        for (a in listOf("opencode", "muse")) assertTrue(a, backgroundSupport(a, a)!!.contains("Ctrl+B"))
        for (a in listOf("codex", "omp", "pi", "copilot", "kiro", "vibe", "hermes")) assertTrue(a, backgroundSupport(a, a)!!.contains("no key"))
        assertTrue(foregroundHint("claude", withHint))
        assertFalse(foregroundHint("grok", withHint))
        assertFalse(foregroundHint("claude", working))
        assertNull(backgroundOutcomeText(Backgrounder.Outcome.DONE, "Claude"))
        assertTrue(backgroundOutcomeText(Backgrounder.Outcome.BLOCKED, "Claude")!!.contains("question"))
    }


    // ---- Captured live, 2026-11-25: a `sleep 120` in each agent's shell mode, then Ctrl+B -----------

    private fun probe(agent: String, name: String) = res("/screens/$agent/live-2026-11-25-probe/$name.ansi")

    @Test fun `each agent's captured running screen gives its step, and the moved one none`() {
        assertEquals(BackgroundStep.CLAUDE, backgroundStep("claude", probe("claude", "claude-fg1")))
        assertNull(backgroundStep("claude", probe("claude", "claude-after-ctrlb")))
        assertEquals(BackgroundStep.GROK, backgroundStep("grok", probe("grok", "grok-running")))
        assertNull(backgroundStep("grok", probe("grok", "grok-after-ctrlb")))
        // Agy shows nothing while it runs; its first Ctrl+B asks for a second; the second moves it.
        assertEquals(BackgroundStep.AGY_ARM, backgroundStep("agy", probe("agy", "agy-running")))
        assertEquals(BackgroundStep.AGY_CONFIRM, backgroundStep("agy", probe("agy", "agy-after-ctrlb")))
        assertTrue(stripAnsi(probe("agy", "agy-after-ctrlb2")).contains("(run in background)"))
        // A draft in Agy's box is never under its first Ctrl+B.
        assertNull(backgroundStep("agy", probe("agy", "agy-draft")))
        // OpenCode and Muse: nothing on screen changed after Ctrl+B, and no step.
        assertNull(backgroundStep("opencode", probe("opencode", "oc-running")))
        assertNull(backgroundStep("muse", probe("muse", "muse-running")))
        assertEquals(stripAnsi(probe("muse", "muse-running")).lines().takeLast(4).map { it.trim() }.filter { it.startsWith("◆") },
            stripAnsi(probe("muse", "muse-after-ctrlb2")).lines().takeLast(4).map { it.trim() }.filter { it.startsWith("◆") })
    }

    @Test fun `the computer's check refuses under a dialog and on a screen without the step's hint`() {
        val dialog = probe("claude", "claude-fg1").trimEnd() + "\n Do you want to proceed?\n ❯ 1. Yes\n   2. No\n\n Esc to cancel · Tab to amend"
        assertTrue(backgroundCheckPasses(BackgroundStep.CLAUDE, probe("claude", "claude-fg1")))
        assertFalse(backgroundCheckPasses(BackgroundStep.CLAUDE, dialog))
        assertFalse(backgroundCheckPasses(BackgroundStep.CLAUDE, probe("claude", "claude-after-ctrlb")))
        assertFalse(backgroundCheckPasses(BackgroundStep.GROK, probe("grok", "grok-after-ctrlb")))
        assertFalse(backgroundCheckPasses(BackgroundStep.AGY_CONFIRM, probe("agy", "agy-running")))
        assertFalse(backgroundCheckPasses(BackgroundStep.AGY_ARM, probe("agy", "agy-after-ctrlb")))
        assertTrue(BACKGROUND_CHECK.contains(BACKGROUND_REFUSED_MARK))
    }

    @Test fun `generated host check agrees with the app on captures and dialog boundaries`() {
        val dir = createTempDirectory("background-check-228").toFile()
        try {
            val helper = dir.resolve("fixture-herdr")
            val snapshot = dir.resolve("fixture-herdr.screen")
            helper.writeText("""
                #!/bin/sh
                [ "$*" = 'pane read fixture:p1 --source visible' ] || exit 9
                cat -- "$0.screen"
            """.trimIndent() + "\n")
            assertTrue(helper.setExecutable(true))

            data class Sample(val agent: String, val frame: String, val expected: BackgroundStep?)
            val claudeRunning = probe("claude", "claude-fg1")
            val samples = listOf(
                Sample("claude", claudeRunning, BackgroundStep.CLAUDE),
                Sample("claude", probe("claude", "claude-after-ctrlb"), null),
                Sample("claude", claudeRunning.trimEnd() + "\n Esc to cancel", null),
                Sample("grok", probe("grok", "grok-running"), BackgroundStep.GROK),
                Sample("grok", probe("grok", "grok-after-ctrlb"), null),
                Sample("agy", probe("agy", "agy-running"), BackgroundStep.AGY_ARM),
                Sample("agy", probe("agy", "agy-after-ctrlb"), BackgroundStep.AGY_CONFIRM),
                Sample("agy", probe("agy", "agy-draft"), null),
            )
            for ((agent, frame, expected) in samples) {
                assertEquals(agent, expected, backgroundStep(agent, frame))
                snapshot.writeText(frame)
                for (step in BackgroundStep.entries.filter { it.check == agent || it.check.startsWith("$agent-") }) {
                    val process = ProcessBuilder("/usr/bin/python3", "-c", BACKGROUND_CHECK,
                        helper.path, "fixture:p1", step.check).redirectErrorStream(true).start()
                    assertTrue("host check timed out", process.waitFor(12, TimeUnit.SECONDS))
                    val output = process.inputStream.bufferedReader().readText().trim()
                    val allowed = expected == step
                    assertEquals(step.check, allowed, backgroundCheckPasses(step, frame))
                    assertEquals("$agent ${step.check}: $output", if (allowed) 0 else BACKGROUND_REFUSED_EXIT, process.exitValue())
                    assertEquals(if (allowed) "" else BACKGROUND_REFUSED_MARK, output)
                }
            }

            assertTrue(snapshot.delete())
            val process = ProcessBuilder("/usr/bin/python3", "-c", BACKGROUND_CHECK,
                helper.path, "fixture:p1", BackgroundStep.CLAUDE.check).redirectErrorStream(true).start()
            assertTrue("host check timed out", process.waitFor(12, TimeUnit.SECONDS))
            assertEquals(4, process.exitValue())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a dialog that opens between the read and the key gets no key`() = runBlocking {
        val dialog = withHint.trimEnd() + "\n Do you want to proceed?\n ❯ 1. Yes\n   2. No\n\n Esc to cancel"
        val f = Fake(listOf(withHint), atKey = { dialog })
        assertEquals(Backgrounder.Outcome.CHANGED, claude(f).run())
        assertEquals(0, f.presses)
    }

    @Test fun `grok is moved once and agy twice, then done`() = runBlocking {
        val g = Fake(listOf(probe("grok", "grok-running"), probe("grok", "grok-after-ctrlb")), taken = 2)
        assertEquals(Backgrounder.Outcome.DONE, Backgrounder("grok", g, fast).run())
        assertEquals(listOf(BackgroundStep.GROK), g.steps)
        val a = Fake(listOf(probe("agy", "agy-running"), probe("agy", "agy-after-ctrlb"), probe("agy", "agy-after-ctrlb2")), taken = 3)
        assertEquals(Backgrounder.Outcome.DONE, Backgrounder("agy", a, fast).run())
        assertEquals(listOf(BackgroundStep.AGY_ARM, BackgroundStep.AGY_CONFIRM), a.steps)
        // Agy with nothing running: its first Ctrl+B changes nothing, and it is not pressed again at once.
        val idle = Fake(listOf(probe("agy", "agy-ctrlb-idle")))
        assertEquals(Backgrounder.Outcome.TIMED_OUT, Backgrounder("agy", idle, fast).run())
        assertTrue(idle.presses <= 2)
    }
}
