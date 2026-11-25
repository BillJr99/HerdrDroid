package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.herdr.ProcInfo
import com.billmongan.herdrdroid.herdr.SubagentInfo
import com.billmongan.herdrdroid.herdr.TaskInfo
import com.billmongan.herdrdroid.herdr.WorkInfo
import com.billmongan.herdrdroid.herdr.visibleBackgroundShellCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2.1.5: the header pill is the only way into "what's running", so it must show whenever something does. */
class RunningPillTest {
    private val proc = ProcInfo(10, 1, 1, "npm", "npm test", 5, 0.0, 1024)
    private fun sub(active: Boolean) = SubagentInfo("s", "s.jsonl", "Review", 0, active, 1)
    private fun task(running: Boolean) = TaskInfo("t", "t.out", "Build", 0, running, 1)

    @Test fun hiddenWhenNothingRanThisSession() {
        assertNull(runningPill(WorkInfo(emptyList(), emptyList(), emptyList(), agentOwnedOnly = true), null, 0))
    }

    @Test fun countsShellsAndSubagentsWithASpokenDescription() {
        val pill = runningPill(WorkInfo(listOf(proc, proc.copy(pid = 11)), listOf(sub(true)), emptyList(), agentOwnedOnly = true), null, 0)!!
        assertEquals("2 sh - 1 sub", pill.label)
        assertEquals("2 shells and 1 subagent running", pill.description)
        assertEquals(true, pill.active)
    }

    @Test fun aRunningBackgroundTaskAloneStillShowsThePill() {
        val pill = runningPill(WorkInfo(emptyList(), emptyList(), listOf(task(true)), agentOwnedOnly = true), null, 0)!!
        assertEquals("1 sh - 0 sub", pill.label)
        assertEquals(true, pill.active)
    }

    @Test fun aProcessOnlyReadDoesNotReportZeroCodexSubagents() {
        val work = WorkInfo(emptyList(), emptyList(), emptyList(), agentOwnedOnly = true)
        val pill = runningPill(work, "codex", 0)!!
        assertEquals("? sub", pill.label)
        assertEquals("0 observed shells; subagent count unavailable", pill.description)
        assertEquals(false, pill.active)
        assertEquals("1 sh - ? sub", runningPill(work.copy(procs = listOf(proc)), "codex", 0)!!.label)
    }

    @Test fun claudeComposerFooterReportsBackgroundShellsWithoutCountingProcessesTwice() {
        val capture = checkNotNull(javaClass.getResource(
            "/screens/claude/live-2026-11-25-probe/claude-after-ctrlb.ansi"
        )).readText()
        val reported = visibleBackgroundShellCount("claude", capture)
        assertEquals(1, reported)
        assertNull(visibleBackgroundShellCount("codex", capture))
        assertNull(visibleBackgroundShellCount("claude", "  ⏵⏵ auto mode on · 1 shell · ↓ to manage"))
        val work = WorkInfo(listOf(proc), emptyList(), emptyList(), agentOwnedOnly = true, reportedBackgroundShells = reported)
        assertEquals("1 sh - ? sub", runningPill(work, "claude", 0)!!.label)
        assertEquals("1 sh - ? sub", runningPill(work.copy(procs = emptyList()), "claude", 0)!!.label)
    }

    @Test fun finishedWorkKeepsAMutedWayIn() {
        val pill = runningPill(WorkInfo(emptyList(), listOf(sub(false)), listOf(task(false)), agentOwnedOnly = true), null, 1)!!
        assertEquals("3 done", pill.label)
        assertEquals(false, pill.active)
    }
}
