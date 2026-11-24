package com.billmongan.herdrdroid.herdr

/**
 * 2.2.7 (Bill, 2026-11-23: "Hermes pane failed to render a question and also doesn't show the Blue
 * Dot and busy when the agent is actually working instead it shows under idle"): Hermes's live
 * composer at the bottom of its screen, the part prompt_toolkit redraws on every frame.
 *
 * Read from Hermes's classic CLI (hermes_cli/cli_tui_mixin.py and cli_modern_layout.py, checkout
 * caafe5389a on minipc2). In the default modern layout the bottom of the screen is, top to bottom:
 *
 *     ── ⠹ Working… · 14s ─────────────── session title ─   the composer's top rule
 *     ☤ ❯ type to redirect this turn · Tab queues it for after   the input row(s)
 *     ──────────────────────────────────────────────────    the bottom rule
 *      ☤ model · 188K/500K · …     enter interrupt · tab queue · ctrl+c stop   the footer
 *
 * The top rule carries a braille spinner on its left while the agent or a command runs, and the
 * input row starts with a state icon before the prompt glyph: "☤" while the agent runs, a spinner
 * frame while a command runs, "⚠" during a dangerous-command approval or a slash-command confirm,
 * "?" during a clarify question, "✎" while typing a clarify answer, "🔐"/"🔑" for a sudo password
 * or a secret, "⚙" during connection setup. The footer's key hints end in "ctrl+c stop" only while
 * the agent runs. The classic layout puts the status bar above the top rule instead of under the
 * bottom rule. herdr's own agent status for Hermes can say done or idle through a whole turn and
 * through an approval (minipc2, 2026-11-23), so these rows decide instead.
 *
 * Only the full chrome is read (a terminal 64 columns or wider). Under 64 columns Hermes drops the
 * bottom rule and the prompt glyph, and then this finds nothing and herdr's status stands.
 */
internal data class HermesComposer(
    /** Index of the first chrome row (the top rule, or the classic status bar right above it). */
    val chromeStart: Int,
    /** The composer's first input row, trimmed. */
    val input: String,
    /** The state icon before the prompt glyph, or null for the plain prompt. */
    val icon: String?,
    /** The top rule shows the live spinner. */
    val spinning: Boolean,
    /** The footer offers "ctrl+c stop". */
    val stopHint: Boolean,
) {
    /** Hermes waits for you: an approval, confirm, clarify, password, secret or setup prompt owns the input. */
    val waiting: Boolean get() = icon in HERMES_WAITING_ICONS
    /** Hermes is running a turn or a command and waits for nothing. */
    val busy: Boolean get() = !waiting && (spinning || stopHint || icon == "☤" || icon?.singleOrNull()?.let { it in HERMES_SPIN } == true)
}

private const val HERMES_SPIN = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
private val HERMES_WAITING_ICONS = setOf("⚠", "?", "✎", "🔐", "🔑", "⚙")
private val HERMES_KNOWN_ICONS = HERMES_WAITING_ICONS + setOf("☤", "●", "◉", "🎤") + HERMES_SPIN.map { it.toString() }
private val HERMES_PURE_RULE = Regex("""^─{10,}$""")
private val HERMES_SPINNING_TOP = Regex("""^── [⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏](?:\s|$)""")
private val HERMES_STOP_HINT = Regex("""\bctrl\+c stop\s*$""")
private const val HERMES_LOOK_BACK = 16
private const val HERMES_MAX_INPUT_ROWS = 8

private fun hermesTopRule(row: String): Boolean =
    row.startsWith("──") && row.endsWith("─") && row.count { it == '─' } >= 10

/**
 * The composer at the bottom of [screen] (styles allowed), or null when the bottom of the screen
 * is not Hermes's full composer chrome. The lowest top rule that has an input row under it, a
 * bottom rule at most [HERMES_MAX_INPUT_ROWS] rows lower and at most three footer rows after that
 * is the composer; a transcript separator higher up never qualifies because the search starts at
 * the bottom.
 */
internal fun hermesComposer(screen: String): HermesComposer? {
    val rows = stripAnsi(screen).lines().map { it.replace("\r", "").trimEnd() }
    var end = rows.size
    while (end > 0 && rows[end - 1].isBlank()) end--
    for (t in end - 3 downTo maxOf(0, end - HERMES_LOOK_BACK)) {
        val top = rows[t].trim()
        if (!hermesTopRule(top)) continue
        val input = rows[t + 1].trim()
        if (input.isEmpty() || input.all { it == '─' }) continue
        val bottom = (t + 2 until minOf(end, t + 2 + HERMES_MAX_INPUT_ROWS)).firstOrNull { HERMES_PURE_RULE.matches(rows[it].trim()) } ?: continue
        val footer = rows.subList(bottom + 1, end).filter { it.isNotBlank() }
        if (footer.size > 3 || footer.any { it.trim().startsWith("──") || it.trim().startsWith('╭') || it.trim().startsWith('╰') }) continue
        // "⚠ ❯": the icon, then the prompt glyph ("☤ Hermes", an answer's label, is not a state).
        val words = input.split(' ').filter { it.isNotEmpty() }
        val glyph = words.getOrNull(1)?.let { it.length <= 2 && it.none(Char::isLetterOrDigit) } ?: true
        val icon = words.first().replace("️", "").takeIf { it in HERMES_KNOWN_ICONS && glyph }
        // Classic layout: the status bar sits right above the top rule (nothing under the bottom rule).
        val above = rows.getOrNull(t - 1)?.trim().orEmpty()
        val classicStatus = footer.isEmpty() && above.contains(" · ") && !above.contains("Enter to") &&
            above.firstOrNull()?.let { it !in "│╭╰─" } == true
        return HermesComposer(
            chromeStart = if (classicStatus) t - 1 else t,
            input = input,
            icon = icon,
            spinning = HERMES_SPINNING_TOP.containsMatchIn(top),
            stopHint = footer.any { HERMES_STOP_HINT.containsMatchIn(it) },
        )
    }
    return null
}

/**
 * [screen] (plain) cut at Hermes's composer with only its input row kept, for the question parsers,
 * when that composer says a prompt owns the input; null otherwise. The parsers were written for a
 * dialog with nothing under it but the "⚠ ❯" input row, so the rules and the footer of the modern
 * layout (the session title, the model and context gauge) can no longer read as newer output.
 */
internal fun hermesWaitingScreen(screen: String): String? {
    val c = hermesComposer(screen)?.takeIf { it.waiting } ?: return null
    return (stripAnsi(screen).lines().take(c.chromeStart) + c.input).joinToString("\n")
}

/** Hermes's own composer shows a running turn or command and no prompt waiting for you. */
internal fun hermesScreenWorking(screen: String): Boolean = hermesComposer(screen)?.busy == true

/** Hermes's own composer shows a prompt waiting for you (approval, confirm, clarify, password). */
internal fun hermesScreenWaiting(screen: String): Boolean = hermesComposer(screen)?.waiting == true
