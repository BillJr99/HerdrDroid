package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    private class Fake(val screens: List<String?>, val taken: Int = Int.MAX_VALUE, val blockAt: Int = -1) : Backgrounder.Port {
        var reads = 0
        var presses = 0
        override suspend fun screen(): String? = screens[minOf(reads, screens.size - 1)].also { reads++ }
        override fun blocked(screen: String) = blockAt in 0 until reads
        override fun done(screen: String) = reads > taken
        override suspend fun press() { presses++ }
        override suspend fun pause(ms: Long) {}
    }

    private val fast = Backgrounder.Limits(maxPresses = 3, maxReads = 12, pollMs = 0)

    @Test fun `ctrl+b each time a foreground command shows, until the message goes`() = runBlocking {
        // A command in the foreground, moved; another one later, moved; then the message is taken.
        val f = Fake(listOf(withHint, working, working, withHint, working, working), taken = 5)
        assertEquals(Backgrounder.Outcome.DONE, Backgrounder(f, fast).run())
        assertEquals(2, f.presses)
    }

    @Test fun `nothing in the foreground means no key, only waiting`() = runBlocking {
        val f = Fake(listOf(working), taken = 3)
        assertEquals(Backgrounder.Outcome.DONE, Backgrounder(f, fast).run())
        assertEquals(0, f.presses)
        val never = Fake(listOf(working))
        assertEquals(Backgrounder.Outcome.TIMED_OUT, Backgrounder(never, fast).run())
        assertEquals(0, never.presses)
    }

    @Test fun `a question or approval stops it before any key`() = runBlocking {
        val f = Fake(listOf(withHint), blockAt = 0)
        assertEquals(Backgrounder.Outcome.BLOCKED, Backgrounder(f, fast).run())
        assertEquals(0, f.presses)
    }

    @Test fun `a key that doesn't take is pressed once, then reported`() = runBlocking {
        val f = Fake(listOf(withHint))
        assertEquals(Backgrounder.Outcome.STUCK, Backgrounder(f, fast).run())
        assertEquals(1, f.presses)
    }

    @Test fun `presses and reads are bounded`() = runBlocking {
        // A fresh foreground command every other read: at most maxPresses presses.
        val f = Fake(List(40) { if (it % 2 == 0) withHint else working })
        assertEquals(Backgrounder.Outcome.TIMED_OUT, Backgrounder(f, fast).run())
        assertEquals(3, f.presses)
        assertTrue(f.reads <= fast.maxReads)
        val blind = Fake(listOf(null))
        assertEquals(Backgrounder.Outcome.UNREADABLE, Backgrounder(blind, fast).run())
        assertEquals(0, blind.presses)
    }

    @Test fun `which agents send immediately, and why the others don't`() {
        assertNull(backgroundSupport("claude", "Claude"))
        for (a in listOf("opencode", "grok", "agy", "muse")) assertTrue(a, backgroundSupport(a, a)!!.contains("Ctrl+B"))
        for (a in listOf("codex", "omp", "pi", "copilot", "kiro", "vibe", "hermes")) assertTrue(a, backgroundSupport(a, a)!!.contains("no key"))
        assertTrue(foregroundHint("claude", withHint))
        assertFalse(foregroundHint("grok", withHint))
        assertNull(backgroundOutcomeText(Backgrounder.Outcome.DONE, "Claude"))
        assertTrue(backgroundOutcomeText(Backgrounder.Outcome.BLOCKED, "Claude")!!.contains("question"))
    }

}
