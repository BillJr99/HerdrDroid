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
        suspend fun press(step: BackgroundStep, viewport: String): Boolean
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
        /** The host no longer sees the viewport the app verified. */
        CHANGED,
        /** The host reports a different occupant. */
        TARGET_CHANGED,
        /** The host could not complete its pre-write check. */
        CHECK_FAILED,
        /** The host reached the key write but could not confirm delivery. */
        UNCERTAIN,
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
            val pressed = port.press(step, screen)
            if (step == BackgroundStep.AGY_ARM) armedAt = read
            if (!pressed) return Outcome.CHANGED
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
    Backgrounder.Outcome.CHANGED -> "Stopped: $agentName's running view changed. The message is still in its queue."
    Backgrounder.Outcome.TARGET_CHANGED -> "Stopped: the pane occupant changed. No key was sent to the replacement."
    Backgrounder.Outcome.CHECK_FAILED -> "Stopped: the check on the computer failed."
    Backgrounder.Outcome.UNCERTAIN -> "Ctrl+B may have reached $agentName. No further key was sent."
    Backgrounder.Outcome.UNREADABLE -> "Stopped: $agentName's screen couldn't be read. The message is still in its queue."
    Backgrounder.Outcome.STUCK -> "Ctrl+B didn't move $agentName's running command to the background. The message is still in its queue."
    Backgrounder.Outcome.TIMED_OUT -> "$agentName hasn't taken the message yet. It is still in its queue."
}

/** Where a shell under a pane runs, as its agent's screen shows it for certain (2.2.8). */
internal enum class RunPlace { FOREGROUND, BACKGROUND, UNKNOWN }

/** Claude Code's command row above a running row: "! sleep 120" (shell mode) or "● Bash(sleep 600)". */
private val CLAUDE_BANG_ROW = Regex("""^! (.+)$""")
private val CLAUDE_TOOL_ROW = Regex("""^[●⏺]\s*Bash\((.+?)\)?$""")
/** Claude Code's count of background shells in its mode row ("⏵⏵ auto mode on · 1 shell · ↓ to manage"). */
private val CLAUDE_SHELL_COUNT = Regex("""·\s+(\d+) shells?\b""")
private val CLAUDE_MODE_LINE = Regex("""^ {1,4}(⏵⏵|⏸|⏵)\s+\S.*$""")
private val CLAUDE_HINT_ROW = Regex("""^\(?\s*ctrl\s*\+\s*b to run in background\s*\)?$""", RegexOption.IGNORE_CASE)

/**
 * The commands Claude Code shows running in the foreground: each one's command row right above its
 * running row and hint ("! sleep 122", "  ⎿  Running… (4s)", "     (ctrl+b to run in background)").
 * A cut command ends in "…".
 */
internal fun claudeForegroundCommands(screen: String): List<String> {
    val all = stripAnsi(screen).lines().map { it.trimEnd() }
    val box = all.indexOfLast { it.trimStart().startsWith("❯") }
    val rows = if (box >= 0) all.take(box) else all
    val out = mutableListOf<String>()
    for (i in rows.indices) {
        if (!CLAUDE_HINT_ROW.matches(rows[i].trim()) || !rows[i].startsWith(" ")) continue
        val running = (i - 1 downTo maxOf(0, i - 2)).firstOrNull { rows[it].isNotBlank() } ?: continue
        if (!rows[running].contains("Running…") && !Regex("""\(\d+(\.\d+)?m?s\)\s*$""").containsMatchIn(rows[running])) continue
        val head = (running - 1 downTo maxOf(0, running - 2)).firstOrNull { rows[it].isNotBlank() } ?: continue
        val r = rows[head].trim()
        val cmd = CLAUDE_BANG_ROW.matchEntire(r)?.groupValues?.get(1) ?: CLAUDE_TOOL_ROW.matchEntire(r)?.groupValues?.get(1)
        out += cmd?.trim() ?: return emptyList()
    }
    return out
}

/** Claude Code's background shell count from its mode row, 0 with the row but no count, null without the row. */
internal fun claudeBackgroundShellCount(screen: String): Int? {
    val row = stripAnsi(screen).lines().lastOrNull { CLAUDE_MODE_LINE.matches(it.trimEnd()) } ?: return null
    return CLAUDE_SHELL_COUNT.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** [shell] (Claude's eval'd command) is the command the screen shows as [shown] ("…" at its end: cut). */
private fun sameCommand(shown: String, shell: String): Boolean {
    val s = shell.trim()
    if (shown.endsWith("…")) {
        val prefix = shown.dropLast(1).trimEnd()
        return prefix.length >= 6 && s.startsWith(prefix)
    }
    return s == shown
}

/**
 * Where each running shell [procs] runs, by pid, only where it is certain (Bill, 2026-11-25: "figure
 * out which shell is the foreground shell and be certain, and then mark that"). Claude Code starts
 * every shell the same way (its own session, no terminal; a capture showed no process-group or
 * session difference between a foreground and a background one), so the process tree alone can't
 * tell them apart. The screen can: each foreground command shows with its own hint, and its text
 * must match exactly one shell's command ([ProcInfo.shell], from its command line). The others are
 * in the background only when their number is the background count Claude shows in its mode row.
 * Anything else is unknown. [backgrounded]: shells the app just moved with Ctrl+B, shown in the
 * background at once. Other agents: unknown.
 */
internal fun shellPlaces(agent: String?, screen: String?, procs: List<ProcInfo>, backgrounded: Set<Int> = emptySet()): Map<Int, RunPlace> {
    val unknown = procs.associate { it.pid to RunPlace.UNKNOWN }
    if (!agent.equals("claude", true) || screen.isNullOrBlank() || procs.isEmpty()) return unknown
    val byPid = procs.associateBy { it.pid }
    fun root(p: ProcInfo): ProcInfo? {
        var at = p
        var guard = 0
        while (at.shell == null && guard++ < 16) at = byPid[at.ppid] ?: return null
        return at.takeIf { it.shell != null }
    }
    val shells = procs.filter { it.shell != null && (byPid[it.ppid]?.let { parent -> root(parent) } == null) }
    if (shells.isEmpty()) return unknown
    val place = mutableMapOf<Int, RunPlace>()
    val shown = claudeForegroundCommands(screen)
    val fg = mutableSetOf<Int>()
    var certain = true
    for (c in shown) {
        val m = shells.filter { it.pid !in backgrounded && sameCommand(c, it.shell!!) }
        if (m.size == 1) fg += m.single().pid else certain = false
    }
    fg.forEach { place[it] = RunPlace.FOREGROUND }
    val rest = shells.filter { it.pid !in fg }
    val count = claudeBackgroundShellCount(screen)
    if (certain && count != null && rest.size == count) rest.forEach { place[it.pid] = RunPlace.BACKGROUND }
    backgrounded.filter { it in byPid }.forEach { place[it] = RunPlace.BACKGROUND }
    return procs.associate { p -> p.pid to (root(p)?.let { place[it.pid] } ?: RunPlace.UNKNOWN) }
}
