package com.billmongan.herdrdroid.herdr

import org.json.JSONArray
import org.json.JSONObject

/** One process running in a pane; [cmd] has secret-looking values masked on the computer. */
data class ProcInfo(
    val pid: Int, val ppid: Int, val depth: Int, val name: String, val cmd: String,
    val ageSec: Long, val cpu: Double, val rssKb: Long,
    /** 2.2.8: for a shell Claude Code started, the command it runs (masked like [cmd]), else null. */
    val shell: String? = null,
)

/** A Claude subagent: its own log [file], a title, and whether it wrote in the last two minutes. */
data class SubagentInfo(val id: String, val file: String, val title: String, val updated: Long, val active: Boolean, val kb: Int)

/** A Claude background task: its output [file], what it is, and whether something still writes it. */
data class TaskInfo(val id: String, val file: String, val title: String, val updated: Long, val running: Boolean, val kb: Int)

/** Everything running under one pane. */
data class WorkInfo(val procs: List<ProcInfo>, val subagents: List<SubagentInfo>, val tasks: List<TaskInfo>,
    val agentOwnedOnly: Boolean = false,
) {
    val activeSubagents get() = subagents.count { it.active }
    val runningTasks get() = tasks.count { it.running }
    /** A process list alone cannot establish an empty subagent list. */
    fun subagentCount(agent: String?): Int? = when {
        activeSubagents > 0 -> activeSubagents
        agent.isNullOrBlank() -> 0
        else -> null
    }
    /** Processes other than the pane's shell, a launcher and the agent: what the agent started. */
    fun childProcs(agent: String?): Int = if (agentOwnedOnly) procs.size
        else (protectedPids(this, agent)).let { keep -> procs.count { it.pid !in keep } }

    /** Live agent descendants, excluding known helpers and their descendants. */
    fun activeProcs(agent: String?): List<ProcInfo> {
        if (agentOwnedOnly) return procs
        val root = agentProcess(procs, agent)
        val parents = root?.let { mutableSetOf(it.pid) }
            ?: procs.filter { agent.isNullOrBlank() && it.depth == 0 }.mapTo(mutableSetOf()) { it.pid }
        return buildList {
            for (proc in procs) {
                if (proc.pid != root?.pid && proc.ppid in parents && !HELPER.containsMatchIn(proc.cmd)) {
                    parents += proc.pid
                    add(proc)
                }
            }
        }
    }

    /** What is still active: running subagents, running background tasks, and active processes. */
    fun activeCount(agent: String?): Int = activeSubagents + runningTasks + activeProcs(agent).size
}

/** Long-lived helpers an agent starts on demand. Kept in step with HELPER_RE in transcript.py. */
val HELPER = Regex(
    "language[-_ ]?server|langserver|\\blsp\\b|pyright|pylsp|gopls|rust-analyzer|clangd|jdtls|tsserver|" +
        "eslint_d|biome|\\bmcp\\b|mcp[-_]|[-_]mcp\\b|mcpproxy|watchman|ssh-agent|gpg-agent|dbus-|app-server|daemon",
    RegexOption.IGNORE_CASE,
)

/** New output of a background task since a byte offset. */
data class TailRead(val text: String, val next: Long, val size: Long, val cut: Boolean)

private fun JSONObject.list(k: String): List<JSONObject> = (optJSONArray(k) ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

fun parseWork(json: String): WorkInfo {
    val o = JSONObject(json.trim())
    if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("why").ifBlank { "That pane's work couldn't be read." })
    val agentOwnedOnly = o.has("active_procs")
    return WorkInfo(
        procs = o.list(if (agentOwnedOnly) "active_procs" else "procs").map {
            ProcInfo(it.optInt("pid"), it.optInt("ppid"), it.optInt("depth"), it.optString("name"), it.optString("cmd"),
                it.optLong("age"), it.optDouble("cpu"), it.optLong("rss"),
                it.optString("shell").takeIf { s -> s.isNotEmpty() && !it.isNull("shell") })
        },
        subagents = o.list("subagents").map {
            SubagentInfo(it.optString("id"), it.optString("file"), it.optString("title"), it.optLong("updated"), it.optBoolean("active"), it.optInt("kb"))
        },
        tasks = o.list("tasks").map {
            TaskInfo(it.optString("id"), it.optString("file"), it.optString("title"), it.optLong("updated"), it.optBoolean("running"), it.optInt("kb"))
        },
        agentOwnedOnly = agentOwnedOnly,
    )
}

fun parseWorkCounts(json: String): Map<String, Int> = parseCounts(json, "counts")

/** Subagents plus background tasks still running, per pane. */
fun parseBusyCounts(json: String): Map<String, Int> = parseCounts(json, "busy")

private fun parseCounts(json: String, key: String): Map<String, Int> {
    val o = JSONObject(json.trim())
    if (!o.optBoolean("ok")) return emptyMap()
    val c = o.optJSONObject(key) ?: return emptyMap()
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
    if (work.agentOwnedOnly) return emptySet()
    val byPid = work.procs.associateBy { it.pid }
    // The pane's shell and any launcher (agent-startup.py) are never work.
    val roots = work.procs.filter { it.depth == 0 || it.cmd.contains("agent-startup") }.map { it.pid }.toMutableSet()
    val agentProc = agentProcess(work.procs, agent)
    var cur: ProcInfo? = agentProc
    while (cur != null) {
        roots += cur.pid
        cur = byPid[cur.ppid]
    }
    return roots
}

private fun agentProcess(procs: List<ProcInfo>, agent: String?): ProcInfo? {
    if (agent.isNullOrBlank()) return null
    val a = agent.lowercase()
    // A helper the agent left running (Codex's app server, say) is never taken for the agent itself.
    val ok = procs.filter { !HELPER.containsMatchIn(it.cmd) }
    // A whole word, as in transcript.py (1.3.0): "pi" is not pip or pigz; "kiro-cli" still matches kiro.
    val word = Regex("(?<![a-z0-9])" + Regex.escape(a) + "(?![a-z0-9])")
    return ok.filter { word.containsMatchIn(it.name.lowercase()) }.minByOrNull { it.depth }
        ?: ok.filter { a != "claude" && word.containsMatchIn(it.cmd.lowercase()) && !it.cmd.contains("agent-startup") }
            .minByOrNull { it.depth }
}

fun ageText(sec: Long): String = when {
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m"
    sec < 86400 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    else -> "${sec / 86400}d ${(sec % 86400) / 3600}h"
}

/** A folder's git state for its card: branch, changed files, commits ahead of and behind upstream. */
data class GitInfo(val branch: String?, val changed: Int, val ahead: Int, val behind: Int)

fun parseGit(json: String): Map<String, GitInfo> {
    val o = JSONObject(json.trim())
    if (!o.optBoolean("ok")) return emptyMap()
    val g = o.optJSONObject("git") ?: return emptyMap()
    return g.keys().asSequence().associateWith { k ->
        val i = g.getJSONObject(k)
        GitInfo(i.optString("branch").takeIf { !i.isNull("branch") && it.isNotBlank() }, i.optInt("changed"), i.optInt("ahead"), i.optInt("behind"))
    }
}

/** One search result: the agent, its session ([ref], in [db] for SQLite agents), and the matching text. */
data class SearchHit(val agent: String, val ref: String, val db: String?, val whenSec: Long, val title: String?, val snippet: String)

fun parseSearch(json: String): List<SearchHit> {
    val o = JSONObject(json.trim())
    if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("why").ifBlank { "The search failed." })
    return o.list("hits").map {
        SearchHit(
            it.optString("agent"), it.optString("ref"), it.optString("db").takeIf { d -> d.isNotBlank() && !it.isNull("db") },
            it.optLong("when"), it.optString("title").takeIf { t -> t.isNotBlank() && !it.isNull("title") }, it.optString("snippet"),
        )
    }
}
