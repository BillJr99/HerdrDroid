package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.CancellationException

/**
 * 2.2.8 (Bill, 2026-11-25: "long pressing it allows me to copy it or other things like share, add
 * send immediately to that menu which would background all foreground tasks until it sends"; and
 * "could it indicate what is currently in the foreground vs background ... and refresh as I click
 * background"): moving an agent's foreground command to the background with the agent's own key,
 * as often as it takes, until a goal is met.
 *
 * Only agents whose screen shows the move were enabled, from captures in a throwaway pane
 * (2026-11-25, a `sleep 120` in each agent's own shell mode, then Ctrl+B):
 * - Claude Code draws "(ctrl+b to run in background)" under a running command; after Ctrl+B the hint is gone.
 * - Grok 1.0.92 shows "Ctrl+b:send to bg" in its key row while a command runs; after Ctrl+B it is gone
 *   ("Running (user)" becomes "Ran (user)").
 * - Agy 1.5.17 shows nothing while a command runs; the first Ctrl+B shows "press … again to send command
 *   to background" in its key row, the second moves it ("(run in background)", "1 task(s)"). Ctrl+B with
 *   nothing running changes nothing on screen.
 * - OpenCode 2.3.2 and Muse Code 1.4.24 show no change after Ctrl+B on a shell command, so they stay off.
 *
 * Each press goes in one command on the computer ([Herdr.sendBackgroundKey]) that first checks the
 * pane's occupant and reads its screen again ([BACKGROUND_CHECK]): the key goes only while that
 * screen still shows the step's hint and no question, approval or panel. Never Esc or Ctrl+C.
 */
internal class Backgrounder(private val agent: String?, private val port: Port, private val limits: Limits = Limits()) {
    interface Port {
        /** A fresh read of the pane's screen, or null when it can't be read. */
        suspend fun screen(): String?
        /** A question, approval prompt or panel is open on [screen], or the pane's occupant changed. */
        fun blocked(screen: String): Boolean
        /** The goal is met (the message went, or the agent is idle and the caller can send). */
        fun done(screen: String): Boolean
        /**
         * Presses the background key once for [step], behind the computer's own check of the screen.
         * False: the check refused (the screen no longer shows the step's hint); nothing was typed.
         */
        suspend fun press(step: BackgroundStep): Boolean
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

    /** Runs until [Port.done], pressing the key each time the screen shows a step ([backgroundStep]). */
    suspend fun run(): Outcome {
        var presses = 0
        var misses = 0
        var pressedAt = -1
        var pressedStep: BackgroundStep? = null
        var armedAt = -100
        for (read in 0 until limits.maxReads) {
            if (read > 0) port.pause(limits.pollMs)
            val screen = try { port.screen() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            if (screen == null) { if (++misses >= 2) return Outcome.UNREADABLE; continue }
            misses = 0
            if (port.blocked(screen)) return Outcome.BLOCKED
            if (port.done(screen)) return Outcome.DONE
            val step = backgroundStep(agent, screen)
            if (step == null) { pressedAt = -1; pressedStep = null; continue }
            // Agy's first press only shows its confirmation when a command runs: at most once in 6 reads.
            if (step == BackgroundStep.AGY_ARM && read - armedAt < 6) continue
            // The same hint three reads after a press: the key didn't take.
            if (pressedAt >= 0 && step == pressedStep && step != BackgroundStep.AGY_ARM) {
                if (read - pressedAt < 3) continue
                return Outcome.STUCK
            }
            if (presses >= limits.maxPresses) return Outcome.TIMED_OUT
            val pressed = port.press(step)
            if (step == BackgroundStep.AGY_ARM) armedAt = read
            if (!pressed) { pressedAt = -1; pressedStep = null; continue }
            presses++
            pressedAt = read
            pressedStep = step
        }
        return Outcome.TIMED_OUT
    }
}

/** What the background key does now, by the screen (2.2.8); each has its own check on the computer. */
internal enum class BackgroundStep(val check: String) {
    /** Claude Code's "(ctrl+b to run in background)" under a running command. */
    CLAUDE("claude"),
    /** Grok's "Ctrl+b:send to bg" key hint. */
    GROK("grok"),
    /** Agy at its empty input with no dialog: the first Ctrl+B, which asks for a second when a command runs. */
    AGY_ARM("agy-arm"),
    /** Agy's "press … again to send command to background". */
    AGY_CONFIRM("agy-confirm"),
}

private fun tailRows(screen: String, n: Int): List<String> =
    stripAnsi(screen).lines().map { it.trimEnd() }.dropLastWhile { it.isBlank() }.takeLast(n)

private val GROK_BG_HINT = Regex("""(^|│|\s)Ctrl\+b:send to bg(\s|│|$)""")
private val AGY_CONFIRM_ROW = Regex("""^press .{0,12}again to send command to background(\s.*)?$""")
private val RULE_ROW = Regex("""^[─━]{20,}.*$""")
/** A dialog's own rows near the bottom: kept in step with [BACKGROUND_CHECK]. */
private val BG_DIALOG = Regex("""Do you want to|Enter to confirm|Esc to cancel|❯\s*\d+\.|Yes, proceed|Allow once|allow tool""", RegexOption.IGNORE_CASE)

/** Grok shows "Ctrl+b:send to bg" in its last row while a command runs (captured 1.0.92). */
internal fun grokBackgroundHint(screen: String): Boolean = tailRows(screen, 1).any { GROK_BG_HINT.containsMatchIn(it) }

/** Agy's key row asks for a second Ctrl+B (captured 1.5.17). */
internal fun agyConfirmShown(screen: String): Boolean = tailRows(screen, 1).any { AGY_CONFIRM_ROW.matches(it.trim()) }

/** Agy at rest: its empty ">" row between two rules near the bottom, "? for shortcuts" under them. */
internal fun agyComposerReady(screen: String): Boolean {
    val rows = tailRows(screen, 10).filter { it.isNotBlank() }
    if (rows.isEmpty() || !rows.last().trimStart().startsWith("? for shortcuts")) return false
    val at = rows.indexOfLast { it.trim() == ">" }
    return at in 1 until rows.size - 1 && RULE_ROW.matches(rows[at - 1].trim()) && RULE_ROW.matches(rows[at + 1].trim())
}

/** A dialog drawn at the bottom of the screen ([BG_DIALOG]). */
internal fun backgroundDialogShown(screen: String): Boolean = tailRows(screen, 14).any { BG_DIALOG.containsMatchIn(it) }

/** The step the background key would take on [screen] for [agent], or null when it would do nothing the app can check. */
internal fun backgroundStep(agent: String?, screen: String): BackgroundStep? {
    if (backgroundDialogShown(screen)) return null
    return when (agent?.lowercase()) {
        "claude" -> BackgroundStep.CLAUDE.takeIf { claudeBackgroundHint(screen) }
        "grok" -> BackgroundStep.GROK.takeIf { grokBackgroundHint(screen) }
        "agy" -> when {
            agyConfirmShown(screen) -> BackgroundStep.AGY_CONFIRM
            agyComposerReady(screen) -> BackgroundStep.AGY_ARM
            else -> null
        }
        else -> null
    }
}

/** The step [screen] passes on the computer: the same rules as [BACKGROUND_CHECK], for tests and the fake. */
internal fun backgroundCheckPasses(step: BackgroundStep, screen: String): Boolean = when (step) {
    BackgroundStep.CLAUDE -> backgroundStep("claude", screen) == step
    BackgroundStep.GROK -> backgroundStep("grok", screen) == step
    BackgroundStep.AGY_ARM -> !backgroundDialogShown(screen) && !agyConfirmShown(screen) && agyComposerReady(screen)
    BackgroundStep.AGY_CONFIRM -> !backgroundDialogShown(screen) && agyConfirmShown(screen)
}

/**
 * Run on the computer just before the background key, in the same command (2.2.8, from Muse's review):
 * reads the pane's visible screen and exits 0 only when it still shows the step's hint ([backgroundCheckPasses]),
 * 6 with [BACKGROUND_REFUSED_MARK] when it doesn't, 4 when it couldn't read. Arguments: herdr, pane, step.
 */
internal val BACKGROUND_CHECK = """
    import re, subprocess, sys
    herdr, pane, step = sys.argv[1:4]
    try:
        out = subprocess.check_output([herdr, 'pane', 'read', pane, '--source', 'visible'], timeout=8).decode('utf-8', 'replace')
    except Exception:
        sys.exit(4)
    rows = [re.sub(r'\x1b\[[0-9;?]*[ -/]*[@-~]', '', r).rstrip() for r in out.replace('\r', '').split('\n')]
    while rows and not rows[-1].strip():
        rows.pop()
    def tail(n):
        return rows[-n:]
    DIALOG = re.compile(r'Do you want to|Enter to confirm|Esc to cancel|❯\s*\d+\.|Yes, proceed|Allow once|allow tool', re.I)
    RULE = re.compile(r'^[─━]{20,}')
    def dialog():
        return any(DIALOG.search(r) for r in tail(14))
    def claude():
        box = max([i for i, r in enumerate(rows) if r.lstrip().startswith('❯')], default=-1)
        above = rows[:box] if box >= 0 else rows
        hint = re.compile(r'^\(?\s*ctrl\s*\+\s*b to run in background\s*\)?$', re.I)
        running = re.compile(r'Running…|\(\d+(\.\d+)?m?s\)\s*$|\(\d+m \d+s\)\s*$')
        for i in range(max(0, len(above) - 40), len(above)):
            r = above[i]
            if not hint.match(r.strip()) or not r.startswith(' '):
                continue
            prev = [above[j] for j in range(i - 1, max(0, i - 2) - 1, -1) if above[j].strip()]
            if prev and running.search(prev[0]):
                return True
        return False
    def grok():
        return bool(rows) and re.search(r'(^|│|\s)Ctrl\+b:send to bg(\s|│|$)', rows[-1]) is not None
    def agy_confirm():
        return bool(rows) and re.match(r'^press .{0,12}again to send command to background(\s.*)?$', rows[-1].strip()) is not None
    def agy_ready():
        t = [r for r in tail(10) if r.strip()]
        if not t or not t[-1].lstrip().startswith('? for shortcuts'):
            return False
        at = max([i for i, r in enumerate(t) if r.strip() == '>'], default=-1)
        return 1 <= at < len(t) - 1 and RULE.match(t[at - 1].strip()) is not None and RULE.match(t[at + 1].strip()) is not None
    ok = not dialog() and {
        'claude': claude, 'grok': grok,
        'agy-arm': lambda: agy_ready() and not agy_confirm(),
        'agy-confirm': agy_confirm,
    }.get(step, lambda: False)()
    if not ok:
        print('$BACKGROUND_REFUSED_MARK')
        sys.exit(6)
""".trimIndent()

internal const val BACKGROUND_REFUSED_MARK = "@@herdrdroid-background-refused@@"
internal const val BACKGROUND_REFUSED_EXIT = 6

/**
 * Whether the app can move [agent]'s foreground work to the background for Send immediately, and if
 * not, why (2.2.8). Null: it can.
 */
internal fun backgroundSupport(agent: String?, agentName: String): String? = when (agent?.lowercase()) {
    "claude", "grok", "agy" -> null
    "opencode", "muse" ->
        "$agentName takes Ctrl+B, but its screen shows no sign a command moved, so the app can't check it. Use Terminal."
    else -> "$agentName has no key that moves a running task to the background, so this waits until it is idle."
}

/** True when [screen] shows [agent] running a command in the foreground that Ctrl+B can move (agy: its confirmation). */
internal fun foregroundHint(agent: String?, screen: String): Boolean = when (backgroundStep(agent, screen)) {
    BackgroundStep.CLAUDE, BackgroundStep.GROK, BackgroundStep.AGY_CONFIRM -> true
    else -> false
}

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
