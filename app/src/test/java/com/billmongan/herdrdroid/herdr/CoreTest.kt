package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    // What hc ls --json printed in the throwaway container (herdr-core 0.1.21), plus a Claude terminal.
    private val ls = """{"hidden_finished":0,"terminals":[
 {"address":"t1","agent":"demo","cwd":"/home/dev","name":"sh1","state":"live","status":"blocked","turn":0},
 {"address":"t2","agent":"sleep","cwd":"/home/dev","name":"sl","state":"live","turn":0},
 {"address":"t3","agent":"claude","cwd":"/home/dev/app","name":"fix","state":"live","status":"working","turn":2},
 {"address":"t4","agent":"bash","cwd":"/home/dev","name":"old","state":"finished","turn":0}]}"""

    @Test fun terminalsBecomePanes() {
        val p = corePanes(ls)
        assertEquals(listOf("t1", "t3", "t2"), p.map { it.id })   // needs you, working, then the rest
        val claude = p.first { it.id == "t3" }
        assertEquals("claude", claude.agent)
        assertEquals(Status.WORKING, claude.status)
        assertEquals("fix", claude.tabLabel)
        val sleep = p.first { it.id == "t2" }
        assertNull(sleep.agent)                                     // a plain program is a shell
        assertEquals("sleep", sleep.title)
        assertEquals(Status.NEEDS_YOU, p.first { it.id == "t1" }.status)
    }

    @Test fun detectionLine() {
        val both = parseDetect("@@detect 1 1 1 1 /home/b/.local/bin/hc")
        assertTrue(both.tuiRunning && both.coreRunning)
        assertEquals("/home/b/.local/bin/hc", both.hcPath)
        val coreOnly = parseDetect("@@detect  0 1 1 /usr/bin/hc")
        assertFalse(coreOnly.tuiInstalled)
        assertTrue(coreOnly.coreRunning)
        val none = parseDetect("@@detect  0  0 ")
        assertFalse(none.tuiInstalled || none.coreInstalled)
        assertNull(none.hcPath)
    }

    @Test fun menuQuestionIsTheTextAboveTheChoices() {
        val screen = """
 Do you want to make this edit to Herdr.kt
 and the two files it touches
 ❯ 1. Yes
   2. Yes, and don't ask again for edits in this folder during this session because it keeps asking me the same thing
   3. No, and tell Claude what to do differently (esc)
"""
        val p = detectPrompt(screen, blocked = true)!!
        assertEquals("Do you want to make this edit to Herdr.kt and the two files it touches", p.question)
        assertEquals(listOf("1", "2", "3"), p.choices.map { it.keys })   // option 2's long label is kept
        // A numbered list in an agent's reply is not a question when the agent isn't waiting.
        assertNull(detectPrompt("Steps:\n 1. Build it\n 2. Test it\n", blocked = false))
    }
}
