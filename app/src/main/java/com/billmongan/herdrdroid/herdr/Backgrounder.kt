package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.CancellationException

/**
 * 2.2.8 (Bill, 2026-11-25: "long pressing it allows me to copy it or other things like share, add
 * send immediately to that menu which would background all foreground tasks until it sends"; and
 * "could it indicate what is currently in the foreground vs background ... and refresh as I click
 * background"): moving an agent's foreground command to the background with the agent's own key,
 * as often as it takes, until a goal is met.
 *
 * Only an agent whose screen proves a foreground command is running ([foregroundHint]) gets the key:
 * today Claude Code, which draws "(ctrl+b to run in background)" under a running command. Other
 * agents in [Backgrounding] also take Ctrl+B, but draw no hint the app has verified, so nothing here
 * presses it for them; [backgroundSupport] says why. Each round reads the screen first and never
 * presses the key while a question, approval or panel is open ([Port.blocked]), and never sends Esc
 * or Ctrl+C.
 */
internal class Backgrounder(private val port: Port, private val limits: Limits = Limits()) {
    interface Port {
        /** A fresh read of the pane's screen, or null when it can't be read. */
        suspend fun screen(): String?
        /** A question, approval prompt or panel is open on [screen], or the pane's occupant changed. */
        fun blocked(screen: String): Boolean
        /** The goal is met (the message went, or the agent is idle and the caller can send). */
        fun done(screen: String): Boolean
        /** Presses the background key once. */
        suspend fun press()
        suspend fun pause(ms: Long)
    }

    data class Limits(
        /** Ctrl+B presses at most, over the whole run. */
        val maxPresses: Int = 6,
        /** Reads at most, over the whole run (each [pollMs] apart): about 90 seconds by default. */
        val maxReads: Int = 60,
        val pollMs: Long = 1_500,
    )

    enum class Outcome {
        /** [Port.done] held. */
        DONE,
        /** A question, approval or panel opened: nothing more was pressed. */
        BLOCKED,
        /** The screen could not be read twice running. */
        UNREADABLE,
        /** The hint was still there after a press and a full wait: the key did not take. */
        STUCK,
        /** The reads or presses ran out before the goal. */
        TIMED_OUT,
    }

    /** Runs until [Port.done], pressing the key each time the screen shows a foreground command. */
    suspend fun run(): Outcome {
        var presses = 0
        var misses = 0
        var pressedAt = -1
        for (read in 0 until limits.maxReads) {
            if (read > 0) port.pause(limits.pollMs)
            val screen = try { port.screen() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            if (screen == null) { if (++misses >= 2) return Outcome.UNREADABLE; continue }
            misses = 0
            if (port.blocked(screen)) return Outcome.BLOCKED
            if (port.done(screen)) return Outcome.DONE
            if (!claudeBackgroundHint(screen)) { pressedAt = -1; continue }
            // The same command still in the foreground three reads after a press: the key didn't take.
            if (pressedAt >= 0 && read - pressedAt < 3) continue
            if (pressedAt >= 0) return Outcome.STUCK
            if (presses >= limits.maxPresses) return Outcome.TIMED_OUT
            port.press()
            presses++
            pressedAt = read
        }
        return Outcome.TIMED_OUT
    }
}

/**
 * Whether the app can move [agent]'s foreground work to the background for Send immediately, and if
 * not, why (2.2.8). Null: it can.
 */
internal fun backgroundSupport(agent: String?, agentName: String): String? = when {
    agent.equals("claude", true) -> null
    Backgrounding.supported(agent) ->
        "$agentName takes Ctrl+B, but its screen shows no sign the app can check that a command moved, so Send immediately is off. Use Terminal."
    else -> "$agentName has no key that moves a running task to the background, so this waits until it is idle."
}

/** True when [screen] shows [agent] running a command in the foreground that Ctrl+B can move. */
internal fun foregroundHint(agent: String?, screen: String): Boolean = agent.equals("claude", true) && claudeBackgroundHint(screen)

/** What the chat says after Send immediately on a message in the agent's own queue (2.2.8). */
internal fun backgroundOutcomeText(outcome: Backgrounder.Outcome, agentName: String): String? = when (outcome) {
    Backgrounder.Outcome.DONE -> null
    Backgrounder.Outcome.BLOCKED -> "Stopped moving tasks to the background: $agentName has a question or panel open. Answer it first."
    Backgrounder.Outcome.UNREADABLE -> "Stopped: $agentName's screen couldn't be read. The message is still in its queue."
    Backgrounder.Outcome.STUCK -> "Ctrl+B didn't move $agentName's running command to the background. The message is still in its queue."
    Backgrounder.Outcome.TIMED_OUT -> "$agentName hasn't taken the message yet. It is still in its queue."
}

/** Where a shell or subagent under a pane runs, as its agent's screen shows it (2.2.8). */
internal enum class RunPlace { FOREGROUND, BACKGROUND, UNKNOWN }

private val CLAUDE_HELPER_ROW = Regex("""^ {2}[◯○◉] (\S+)\s{2,}(.+?)(?:\s{2,}.*)?$""")

/**
 * The descriptions of Claude Code's background helpers in the list it draws under its input box
 * ("  ◯ general-purpose  Watching CI workflow run    16m 39s · ↓ 1"). The "● main" row is the session.
 */
internal fun claudeBackgroundHelpers(screen: String): List<String> =
    stripAnsi(screen).lines().mapNotNull { CLAUDE_HELPER_ROW.matchEntire(it.trimEnd())?.groupValues?.get(2)?.trim() }

private fun norm(t: String) = t.filter { it.isLetterOrDigit() }.lowercase()

/**
 * Where a Claude subagent titled [title] runs: in the background when Claude's helper list shows it,
 * in the foreground when the list is there without it, unknown with no list or for other agents.
 */
internal fun subagentPlace(agent: String?, screen: String?, title: String): RunPlace {
    if (!agent.equals("claude", true) || screen.isNullOrBlank()) return RunPlace.UNKNOWN
    val helpers = claudeBackgroundHelpers(screen)
    if (helpers.isEmpty()) return RunPlace.UNKNOWN
    val t = norm(title)
    if (t.isEmpty()) return RunPlace.UNKNOWN
    return if (helpers.any { h -> norm(h).let { it.isNotEmpty() && (t.startsWith(it.take(24)) || it.startsWith(t.take(24))) } })
        RunPlace.BACKGROUND else RunPlace.FOREGROUND
}

/**
 * Where each running shell [procs] runs, by pid. Claude Code only: with its foreground hint on the
 * screen, the newest top-level shell is in the foreground and the rest in the background; with no
 * hint, all of them are in the background. [backgrounded]: pids the app just moved with Ctrl+B,
 * shown in the background until a read says otherwise. Other agents: unknown.
 */
internal fun shellPlaces(agent: String?, screen: String?, procs: List<ProcInfo>, backgrounded: Set<Int> = emptySet()): Map<Int, RunPlace> {
    if (!agent.equals("claude", true) || screen.isNullOrBlank() || procs.isEmpty()) return procs.associate { it.pid to RunPlace.UNKNOWN }
    val top = procs.minOf { it.depth }
    val roots = procs.filter { it.depth == top }
    // The newest shell holds the hint; one the app just moved stays in the background until a read clears the hint.
    val fg = if (claudeBackgroundHint(screen)) roots.minByOrNull { it.ageSec }?.takeIf { it.pid !in backgrounded } else null
    // A child shows where its top-level shell runs.
    val byPid = procs.associateBy { it.pid }
    fun root(p: ProcInfo): ProcInfo {
        var at = p
        var guard = 0
        while (at.depth > top && guard++ < 16) at = byPid[at.ppid] ?: break
        return at
    }
    return procs.associate { p -> p.pid to if (fg != null && root(p).pid == fg.pid) RunPlace.FOREGROUND else RunPlace.BACKGROUND }
}
