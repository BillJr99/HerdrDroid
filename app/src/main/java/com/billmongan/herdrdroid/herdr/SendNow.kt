package com.billmongan.herdrdroid.herdr

/**
 * "Send now" on a message queued until the pane is idle (2.2.7, Bill: "if a message queues because
 * of a pane being busy it should allow me to long press the queued message and pick send
 * immediately which should background the task if possible and then send").
 *
 * The route is chosen from the pane's status and its latest screen:
 * - [SendNowRoute.NORMAL]: the agent is not working; an ordinary send with every guard.
 * - [SendNowRoute.BACKGROUND_THEN_SEND]: Claude Code is running a command and its screen shows the
 *   command's own hint "(ctrl+b to run in background)": Ctrl+B first, then a fresh read must show
 *   the hint gone (or the agent idle) before the message is sent once.
 * - [SendNowRoute.FORCE_CONFIRM]: Claude Code is working with no such hint: the 2.2.2 Force Send
 *   confirmation, since Claude takes text typed during a turn after its current step.
 *   Codex also supports confirmed steering from its verified working composer.
 * - [SendNowRoute.WAIT_FOR_IDLE]: other working agents stay queued unless their background action is supported.
 *
 * Grok and Antigravity follow the separate guarded background route in the view model.
 */
internal enum class SendNowRoute { NORMAL, BACKGROUND_THEN_SEND, FORCE_CONFIRM, WAIT_FOR_IDLE }

/** Claude Code's hint under a running command (captured 2.1.330: "     (ctrl+b to run in background)"). */
private val CLAUDE_BACKGROUND_HINT = Regex("""^\(?\s*ctrl\s*\+\s*b to run in background\s*\)?$""", RegexOption.IGNORE_CASE)

/** How far up the screen the hint is looked for: the running command sits just above the input box. */
private const val HINT_ROWS = 40

/** The screen's rows above Claude's input box, newest last (the box's own rows are typing, never a hint). */
private fun rowsAboveInput(screen: String): List<String> {
    val rows = stripAnsi(screen).lines()
    val box = rows.indexOfLast { it.trimStart().startsWith("❯") }
    return (if (box >= 0) rows.take(box) else rows).takeLast(HINT_ROWS)
}

/** A running tool's own status row just above the hint: "⎿  Running… (7s)" or a command line ending "… (7s)". */
private val RUNNING_ROW = Regex("""Running…|\(\d+(\.\d+)?m?s\)\s*$|\(\d+m \d+s\)\s*$""")

/**
 * True when Claude Code's screen shows a running command it can move to the background with Ctrl+B:
 * the hint as Claude draws it under that command (indented, right below the command's running row),
 * never the same words in the chat's text (pre-review L5).
 */
internal fun claudeBackgroundHint(screen: String): Boolean {
    // All rows above the input box, so the row above a hint at the top of the window is still seen.
    val all = stripAnsi(screen).lines()
    val box = all.indexOfLast { it.trimStart().startsWith("❯") }
    val rows = if (box >= 0) all.take(box) else all
    for (i in maxOf(0, rows.size - HINT_ROWS) until rows.size) {
        val r = rows[i]
        if (!CLAUDE_BACKGROUND_HINT.matches(r.trim()) || !r.startsWith(" ")) continue
        val above = (i - 1 downTo maxOf(0, i - 2)).map { rows[it] }.firstOrNull { it.isNotBlank() } ?: continue
        if (RUNNING_ROW.containsMatchIn(above)) return true
    }
    return false
}

/** herdr says working, or the agent's own running status row ("esc to interrupt") is on screen. */
internal fun agentWorking(status: Status, screen: String): Boolean =
    status == Status.WORKING || stripAnsi(screen).lines().takeLast(HINT_ROWS).any { isAgentStatusRow(it) }

internal fun sendNowRoute(agent: String?, status: Status, screen: String): SendNowRoute = when {
    !agentWorking(status, screen) -> SendNowRoute.NORMAL
    agent.equals("claude", true) && claudeBackgroundHint(screen) -> SendNowRoute.BACKGROUND_THEN_SEND
    (takesInputDuringBackgroundWork(agent) || agent == "codex" && codexSteeringReady(screen)) -> SendNowRoute.FORCE_CONFIRM
    else -> SendNowRoute.WAIT_FOR_IDLE
}

/** Why Send now keeps a message queued for an agent that takes no input while it works. */
internal fun sendNowWaitText(agentName: String) =
    "$agentName can't take a message while it works, so this one stays queued and goes when $agentName is idle."

/** Installed Codex 0.172.0 uses Enter to steer and Tab to queue during this working composer view. */
internal fun codexSteeringReady(screen: String): Boolean {
    val rows = stripAnsi(screen).lines().filter { it.isNotBlank() }.takeLast(12)
    val input = rows.indexOfLast { it.trimStart().startsWith("›") }
    if (input < 1 || !Regex("""^• Working \(.*esc to interrupt\)$""").matches(rows[input - 1].trim())) return false
    return inputLineOf(screen, "codex") == "" && composerDraftOf(screen, "codex") == null &&
        rows.drop(input + 1).any { it.trimStart().startsWith("← for agents") }
}

internal fun codexSteeringText(text: String): Boolean =
    text.isNotBlank() && text.trimStart().first() !in "/!$" &&
        text.none { it.isISOControl() } && LongText.plan(text) is LongText.Plan.Single

internal fun nativeQueuedSendAvailable(agent: String?, status: Status, screen: String): Boolean =
    sendNowRoute(agent, status, screen) != SendNowRoute.WAIT_FOR_IDLE ||
        backgroundSupport(agent, "") == null
