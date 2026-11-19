package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTest {
    @Test fun classifiedWorkDoesNotReinferHiddenLauncherOrRuntime() {
        val w = parseWork("""{"ok":true,"active_procs":[{"pid":7,"ppid":5,"depth":4,"name":"sh","cmd":"sh"}],"procs":[{"pid":1,"name":"bash"}]}""")
        assertEquals(listOf(7), w.activeProcs("opencode").map { it.pid })
        assertEquals(1, w.childProcs("opencode"))
    }
    // The shape the reader printed for devbox2's Claude pane (pids and commands as seen there).
    private val json = """
{"ok": true, "procs": [
 {"pid": 1654, "ppid": 1, "depth": 0, "cmd": "/bin/bash", "name": "bash", "age": 365840, "cpu": 0.0, "rss": 3656},
 {"pid": 5426, "ppid": 1654, "depth": 1, "cmd": "/usr/bin/python3 /home/user/.config/herdr/agent-startup.py agent claude", "name": "python3", "age": 365806, "cpu": 0.0, "rss": 7820},
 {"pid": 5475, "ppid": 5426, "depth": 2, "cmd": "claude --continue", "name": "claude", "age": 365806, "cpu": 2.2, "rss": 505416},
 {"pid": 7001, "ppid": 5475, "depth": 3, "cmd": "/bin/bash -c sleep 600", "name": "bash", "age": 30, "cpu": 0.0, "rss": 4000},
 {"pid": 7002, "ppid": 7001, "depth": 4, "cmd": "sleep 600", "name": "sleep", "age": 30, "cpu": 0.0, "rss": 900}],
 "subagents": [{"id": "a1", "file": "/h/s/subagents/agent-a1.jsonl", "title": "Build the brief", "updated": 1, "active": true, "kb": 12},
               {"id": "a2", "file": "/h/s/subagents/agent-a2.jsonl", "title": "Old one", "updated": 1, "active": false, "kb": 3}],
 "tasks": [{"id": "b1", "file": "/tmp/claude-1000/x/s/tasks/b1.output", "title": "Build 0.5.0", "updated": 1, "running": true, "kb": 4}]}
"""

    @Test fun parsesAndCounts() {
        val w = parseWork(json)
        assertEquals(5, w.procs.size)
        assertEquals(1, w.activeSubagents)
        assertEquals(1, w.runningTasks)
        assertEquals(2, w.childProcs("claude"))
    }

    @Test fun neverOffersStopOnTheShellLauncherOrAgent() {
        val w = parseWork(json)
        assertEquals(setOf(1654, 5426, 5475), protectedPids(w, "claude"))
        // A pane without an agent protects its shell and, since 1.2.5, any launcher (agent-startup.py).
        assertEquals(setOf(1654, 5426), protectedPids(w, null))
    }

    @Test fun countsAndTailParse() {
        assertEquals(mapOf("w2:p5" to 2, "w2:pT" to 0), parseWorkCounts("""{"ok": true, "counts": {"w2:p5": 2, "w2:pT": 0}}"""))
        val t = parseTail("""{"ok": true, "text": "line\n", "next": 120, "size": 120, "cut": false}""")
        assertEquals(120L, t.next)
        assertTrue(t.text.startsWith("line"))
        assertEquals("1h 1m", ageText(3660))
    }
}
