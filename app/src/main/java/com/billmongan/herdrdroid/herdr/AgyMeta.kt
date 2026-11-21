package com.billmongan.herdrdroid.herdr

/**
 * Antigravity's meta output on its screen (2.1.1, Bill: "continue to improve chat view for
 * antigravity especially when rendering its meta output"). Its TUI prints thinking and tool-call
 * headers, an "… N more actions …" tally, spinners, a notice box, warnings and a status line
 * between the replies. Chat showed them as prose, glyphs and all. This sorts them out so Chat
 * can fold them into one activity chip, the way the transcript agents get one.
 */
object AgyMeta {
    /** What a meta line is. */
    enum class Kind { THINK, SHELL, READ, EDIT, SEARCH, TASK, MORE, STATUS, OTHER }

    // "○ Thought for 15m 39s (Verifying the build output by checking...)"
    private val THOUGHT = Regex("""^[○●◦]\s+(Thought for [0-9][0-9hms. ]*?)\s*(?:\((.*)\))?$""")

    // "● Ran 2 commands, listed tasks (tail -n 30 ...)", "● Explored 19 files (A.kt, +16)",
    // "● Ran (git ls-files)", "● Killed 2 tasks (task-1, task-2) (ctrl+o to expand)".
    // The verb is a capitalized past-tense word; then an optional count and noun, more such
    // clauses after commas, and one parenthesized argument. A reply such as "● Updated the plan."
    // doesn't fit, so it stays prose.
    private val TOOL = Regex(
        """^●\s+((?:Ran|Read|Wrote|Made|Found|Sent|Got|Began|[A-Z][a-z]+ed)""" +
            """(?:\s+\d+\s+[a-z]+(?:\s+[a-z]+)?)?(?:,\s+[a-z]+(?:\s+\d+)?(?:\s+[a-z]+)?)*)""" +
            """(?:\s+\((.*)\))?(\s+failed)?$""",
    )

    // "… 134 more actions (11 reads, 11 edits, 112 commands: 9 failed) …"
    private val MORE = Regex("""^…\s+(\d+ more actions?)\s*(?:\((.*)\))?\s*…?$""")

    // A spinner frame then a status, e.g. "⠋ Thinking… (12s · esc to cancel)".
    private val SPINNER = Regex("""^[⠀-⣿◐◓◑◒⟳↻]+\s+(\S.*)$""")

    private val EXPAND = Regex("""\s*\(ctrl\+o to (?:expand|collapse)\)\s*$""")
    private val FAILED_N = Regex("""(\d+)\s+failed""")
    private val COUNT = Regex("""^\S+\s+(\d+)\s""")

    /** One meta line, as a row in the activity chip. */
    data class Step(
        val kind: Kind, val title: String, val detail: String? = null,
        val count: Int = 1, val failed: Int = 0, val running: Boolean = false,
    )

    /** The meta line [t] (trimmed) as a step, or null when it is the agent's own text. */
    fun step(t: String): Step? {
        val s = t.replace(EXPAND, "").trim()
        THOUGHT.matchEntire(s)?.let { m ->
            return Step(Kind.THINK, m.groupValues[1].trim(), m.groupValues[2].ifBlank { null }?.let(::scrub))
        }
        MORE.matchEntire(s)?.let { m ->
            val detail = m.groupValues[2].ifBlank { null }
            val n = m.groupValues[1].substringBefore(' ').toIntOrNull() ?: 1
            return Step(Kind.MORE, m.groupValues[1], detail, count = n,
                failed = detail?.let { FAILED_N.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0)
        }
        TOOL.matchEntire(s)?.let { m ->
            val head = m.groupValues[1]
            val arg = m.groupValues[2].ifBlank { null }
            val failed = m.groupValues[3].isNotBlank() || arg?.trimEnd()?.endsWith("failed") == true
            val n = COUNT.find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return Step(kindOf(head), scrub(head), arg?.let(::scrub), count = n, failed = if (failed) 1 else 0)
        }
        SPINNER.matchEntire(s)?.let { m ->
            return Step(Kind.STATUS, scrub(m.groupValues[1]), running = true)
        }
        return null
    }

    private fun kindOf(head: String): Kind {
        val verb = head.substringBefore(' ').lowercase()
        return when (verb) {
            "ran", "executed", "started", "killed", "stopped" -> if ("task" in head.substringBefore(',')) Kind.TASK else Kind.SHELL
            "explored", "read", "viewed", "opened", "listed" -> Kind.READ
            "edited", "wrote", "created", "updated", "deleted", "moved", "renamed", "applied", "made" -> Kind.EDIT
            "searched", "found", "grepped", "fetched", "browsed" -> Kind.SEARCH
            else -> Kind.OTHER
        }
    }

    /** A one-line summary of a run of steps, for the chip's title. */
    fun summary(steps: List<Step>): String {
        val thought = steps.firstOrNull { it.kind == Kind.THINK }?.title
        // Each header is one action ("Explored 19 files" too); the tally adds the ones agy folded away.
        val actions = steps.sumOf { when (it.kind) { Kind.THINK, Kind.STATUS -> 0; Kind.MORE -> it.count; else -> 1 } }
        val failed = steps.sumOf { it.failed }
        val status = steps.lastOrNull { it.running }?.title
        val parts = buildList {
            if (thought != null) add(thought)
            if (actions > 0) add(if (actions == 1) "1 action" else "$actions actions")
            if (failed > 0) add("$failed failed")
            if (isEmpty() && status != null) add(status)
        }
        return parts.joinToString(" · ").ifEmpty { "Working" }
    }

    /** A notice box row ("┃ Auto is now the default agent mode"), without its edge; null if not one. */
    fun notice(t: String): String? {
        if (!t.startsWith("┃")) return null
        val s = t.removePrefix("┃").trim()
        return s
    }

    /** "esc to dismiss" and the like: a key hint in a notice, which a phone can't use. */
    fun isKeyHint(s: String): Boolean = s.matches(Regex("""(?i)(?:press\s+)?esc to (?:dismiss|close|cancel)\.?"""))

    /** "⚠ Individual quota reached." as "Warning: Individual quota reached." */
    fun warning(t: String): String? =
        if (t.startsWith("⚠")) "Warning: " + scrub(t.removePrefix("⚠").removePrefix("️").trim()) else null

    /** A rule with a title in it ("──── Conversation compacted ────"): the title; else null. */
    fun ruleTitle(t: String): String? {
        val s = t.trim()
        if (!s.startsWith("──") || !s.endsWith("──")) return null
        val inner = s.trim('─', ' ')
        return inner.takeIf { it.isNotEmpty() && it.none { c -> c == '─' } && it.any(Char::isLetter) }
    }

    private val USAGE_HEAD = Regex("""^\s*└\s*Models\s*&\s*Quota\s*$""")

    /** The first row of agy's usage panel, "└ Models & Quota" (2.1.1). */
    fun isUsageHead(line: String): Boolean = USAGE_HEAD.matches(line)

    private val USAGE_ROW = Regex(
        """^(?:\[[█░▒▓━─ ]{3,}]\s*\d+(?:\.\d+)?%|Quota (?:available|exhausted)|(?:Refreshes|Resets?) in\b.*|""" +
            """.*\bLimit(?: Remaining)?|Models within this group:.*|Account:.*|[A-Z0-9][A-Z0-9 &]{2,}|.*\b(?i:esc to close)\b.*|""" +
            """For paid .*)$""",
    )

    /** A row of agy's usage panel: a limit, its bar, a group heading, the account, the close hint. */
    fun isUsageRow(line: String): Boolean = USAGE_ROW.matches(line.trim())

    /**
     * Agy's usage panel as one quiet line: what is left of each limit, never the account or other
     * rows (2.1.1). [open]: the panel is still showing under the composer, closed with Esc there.
     */
    fun usageLine(panel: String, open: Boolean): String {
        val left = parseQuota(panel, bareMeansLeft = true).joinToString(" · ") {
            // Whole percents, rounded down: never more left than the panel showed.
            val pct = kotlin.math.floor((100.0 - it.usedPercent).coerceIn(0.0, 100.0) + 1e-9).toInt()
            shortLabel(it.label) + " " + pct + "% left"
        }
        val head = if (open) "Models & Quota is open on the computer (Esc there closes it)" else "Models & Quota"
        return if (left.isEmpty()) head else "$head: $left"
    }

    /** A line of agy's status bar ("? for shortcuts          Orchestrator (Neon)") as the phone shows it. */
    fun footerLine(s: String): String =
        scrub(s).replace(Regex("""^\?\s+for shortcuts\b"""), "").trim()
            .split(Regex(""" {3,}""")).filter { it.isNotBlank() }.joinToString(" · ")

    /** True for a line that marks agy's status bar under its composer. */
    fun isStatusBar(s: String): Boolean =
        s.contains("Shift+Tab to change mode", ignoreCase = true) || s.trimStart().startsWith("? for shortcuts") ||
            s.contains("esc to cancel", ignoreCase = true) || s.contains("esc to interrupt", ignoreCase = true)

    /**
     * Prose without private-use characters (Nerd Font and other icon-font glyphs: U+E000 to
     * U+F8FF, and the supplementary planes from U+F0000), which the app's fonts draw as boxes, and
     * without a variation selector left behind by one.
     */
    fun scrub(s: String): String {
        if (s.none { it in ''..'' || Character.isSurrogate(it) }) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (!(cp in 0xE000..0xF8FF || cp >= 0xF0000)) sb.appendCodePoint(cp)
            else if (i + n < s.length && s[i + n] == '️') i++ // its emoji selector goes too
            i += n
        }
        return sb.toString().replace(Regex(""" {2,}"""), " ").trim()
    }
}
