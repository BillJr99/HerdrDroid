package com.billmongan.herdrdroid.herdr

import org.json.JSONArray
import org.json.JSONObject

/** One process running in a pane; [cmd] has secret-looking values masked on the computer. */
data class ProcInfo(
    val pid: Int, val ppid: Int, val depth: Int, val name: String, val cmd: String,
    val ageSec: Long, val cpu: Double, val rssKb: Long,
)

/** A Claude subagent: its own log [file], a title, and whether it wrote in the last two minutes. */
data class SubagentInfo(val id: String, val file: String, val title: String, val updated: Long, val active: Boolean, val kb: Int)

/** A Claude background task: its output [file], what it is, and whether something still writes it. */
data class TaskInfo(val id: String, val file: String, val title: String, val updated: Long, val running: Boolean, val kb: Int)

/** Everything running under one pane. */
data class WorkInfo(val procs: List<ProcInfo>, val subagents: List<SubagentInfo>, val tasks: List<TaskInfo>) {
    val activeSubagents get() = subagents.count { it.active }
    val runningTasks get() = tasks.count { it.running }
    /** Processes other than the pane's shell, a launcher and the agent: what the agent started. */
    fun childProcs(agent: String?): Int = (protectedPids(this, agent)).let { keep -> procs.count { it.pid !in keep } }
}

/** New output of a background task since a byte offset. */
data class TailRead(val text: String, val next: Long, val size: Long, val cut: Boolean)

private fun JSONObject.list(k: String): List<JSONObject> = (optJSONArray(k) ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

fun parseWork(json: String): WorkInfo {
    val o = JSONObject(json.trim())
    if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("why").ifBlank { "That pane's work couldn't be read." })
    return WorkInfo(
        procs = o.list("procs").map {
            ProcInfo(it.optInt("pid"), it.optInt("ppid"), it.optInt("depth"), it.optString("name"), it.optString("cmd"),
                it.optLong("age"), it.optDouble("cpu"), it.optLong("rss"))
        },
        subagents = o.list("subagents").map {
            SubagentInfo(it.optString("id"), it.optString("file"), it.optString("title"), it.optLong("updated"), it.optBoolean("active"), it.optInt("kb"))
        },
        tasks = o.list("tasks").map {
            TaskInfo(it.optString("id"), it.optString("file"), it.optString("title"), it.optLong("updated"), it.optBoolean("running"), it.optInt("kb"))
        },
    )
}

fun parseWorkCounts(json: String): Map<String, Int> {
    val o = JSONObject(json.trim())
    if (!o.optBoolean("ok")) return emptyMap()
    val c = o.optJSONObject("counts") ?: return emptyMap()
    return c.keys().asSequence().associateWith { c.optInt(it) }
}

fun parseTail(json: String): TailRead {
    val o = JSONObject(json.trim())
    if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("why").ifBlank { "That output couldn't be read." })
    return TailRead(o.optString("text"), o.optLong("next"), o.optLong("size"), o.optBoolean("cut"))
}

/**
 * The pids that never get a Stop button: the agent's own process and everything above it (the
 * pane's shell, a launcher). The agent's process is the shallowest one whose program name has the
 * agent's name in it, or else whose command line does (not counting a launcher script).
 * A pane without an agent protects only its shell.
 */
fun protectedPids(work: WorkInfo, agent: String?): Set<Int> {
    val byPid = work.procs.associateBy { it.pid }
    val roots = work.procs.filter { it.depth == 0 }.map { it.pid }.toMutableSet()
    if (agent.isNullOrBlank()) return roots
    val a = agent.lowercase()
    val agentProc = work.procs.filter { it.name.lowercase().contains(a) }.minByOrNull { it.depth }
        ?: work.procs.filter { it.cmd.lowercase().contains(a) && !it.cmd.contains("agent-startup") }.minByOrNull { it.depth }
        ?: return roots
    var cur: ProcInfo? = agentProc
    while (cur != null) {
        roots += cur.pid
        cur = byPid[cur.ppid]
    }
    return roots
}

fun ageText(sec: Long): String = when {
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m"
    sec < 86400 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    else -> "${sec / 86400}d ${(sec % 86400) / 3600}h"
}
