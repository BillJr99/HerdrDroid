package com.billmongan.herdrdroid.herdr

/** One piece of an agent's screen, as the phone shows it. */
sealed interface Seg {
    /**
     * A paragraph. The agent wrapped it at the terminal's width; the lines are joined back into
     * one so it wraps again at the phone's width. [indent] is in columns, relative to the
     * screen's left margin; [mark] is its bullet, number or status glyph, if any.
     */
    data class Para(val indent: Int, val mark: String?, val text: String, val detail: Boolean = false) : Seg

    /** Lines that only read right at their full width: tables, boxes, aligned columns. */
    data class Code(val lines: List<String>) : Seg

    /** A message you sent, as the agent shows it. */
    data class You(val text: String) : Seg

    /** The agent's own input box and status line, at the bottom of its screen. */
    data class Footer(val lines: List<String>) : Seg

    /**
     * Antigravity's meta lines (thinking, tool calls, the "more actions" tally, a spinner) in a
     * row, folded into one activity chip (2.1.1).
     */
    data class Activity(val steps: List<AgyMeta.Step>) : Seg {
        val summary: String get() = AgyMeta.summary(steps)
        val running: Boolean get() = steps.lastOrNull()?.running == true
        val failed: Int get() = steps.sumOf { it.failed }
    }

    data object Rule : Seg
    data object Gap : Seg
}

/**
 * Turns a terminal screen into readable pieces: it drops scrollbars, right-hand clocks and side
 * panels, joins the lines the agent wrapped, keeps tables and boxes as they are, picks out the
 * messages you sent, and moves the agent's input box and status line to a footer.
 */
object Reflow {
    private const val BOX = "─│┌┐└┘├┤┬┴┼╭╮╰╯═║╔╗╚╝━┃▔▁█░▓▒■□╹▀▄"
    private const val RULE_CHARS = "─━═▔▁▀▄-_ "
    private const val CHROME_START = "╭┌╹┃"
    private val ALIGNED = Regex("""\S {3,}\S""")
    private val MARK = Regex("""^([-*•·▸▹]\s+|\d{1,3}[.)]\s+|[⏺●○◦✓✗✔✘⎿◆◈◇★☤⏵]\s*)""")
    private val USER = Regex("""^[❯›>]\s+(\S.*)$""")
    private val PROMPT_ONLY = Regex("""^[│┃\s]*[❯›>]\s.*""")
    private val SCROLLBAR = Regex("""\s+[█▐▌▕▏]$""")
    private val CLOCK = Regex("""\s{3,}\d{1,2}:\d{2}\s?[AaPp][Mm]$""")
    private val GAP6 = Regex(""" {6,}""")
    private val GROK_THOUGHT = Regex("""Thought for \d+(?:\.\d+)?s""")
    private val GROK_PROMPT = Regex("""│\s*❯(?:\s+.*?)?\s*│""")
    private val SELECTED_CHOICE = Regex("""[❯›]\s+(\d{1,3})[.)]\s+\S.*""")
    private val OTHER_CHOICE = Regex("""(\d{1,3})[.)]\s+\S.*""")
    private val TABLE_RULE = Regex("""(?:[┌├└][─┬┼┴]+[┐┤┘]|[╔╠╚][═╦╬╩]+[╗╣╝])""")

    /** The line without a scrollbar, a right-aligned clock, or a side panel to its right. */
    fun clean(line: String): String {
        var l = line.trimEnd()
        repeat(3) { l = l.replace(SCROLLBAR, "") }
        l = l.replace(CLOCK, "").trimEnd()
        val lead = l.length - l.trimStart().length
        if (lead >= 50) return "" // only something pinned to the right edge
        if (l.count { it in BOX } < 2) {
            for (g in GAP6.findAll(l)) {
                if (g.range.first >= 40 && l.substring(0, g.range.first).trim().length >= 30) {
                    l = l.substring(0, g.range.first)
                    break
                }
            }
        }
        return l.trimEnd()
    }

    fun isRule(t: String): Boolean {
        val s = t.trim()
        return s.length >= 12 && s.count { it in RULE_CHARS } >= s.length * 0.8 && s.count { it in RULE_CHARS && it != ' ' } >= 8
    }

    fun isWide(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return false
        if (s.count { it in BOX } >= 2) return true
        if (s.startsWith("|") && s.count { it == '|' } >= 2) return true
        return ALIGNED.containsMatchIn(s) && s.length > 24
    }

    private class P(val indent: Int, val mark: String?, val hang: Int, val detail: Boolean, first: String, var lastLen: Int) {
        val sb = StringBuilder(first)
    }

    private class Y(val hang: Int, first: String) {
        val sb = StringBuilder(first)
    }

    /** Adds a wrapped line to a paragraph, putting back the space the wrap took (or not, mid-word). */
    private fun join(sb: StringBuilder, next: String, prevLen: Int, width: Int) {
        val last = sb.split(' ').lastOrNull().orEmpty()
        val midWord = (prevLen >= width - 1 && last.length >= 15) ||
            (last.length >= 2 && last.endsWith('-') && last[last.length - 2].isLetter() && next.firstOrNull()?.isLetter() == true)
        if (!midWord) sb.append(' ')
        sb.append(next)
    }

    /**
     * Claude's mode glyphs ("⏵⏵ auto mode on", "⏸ plan mode on"), which the app's fonts lack and
     * Android drew as a missing-glyph box (2.1.2, 2.1.1 acceptance finding 5). Mapped as [forPhone]
     * maps them, except that a single "⏵" becomes "»" too: "›" would read as a sent message.
     */
    private val STATUS_GLYPHS = listOf(
        "\u23F5\u23F5" to "\u00BB", // ⏵⏵ -> »
        "\u23F5" to "\u00BB", // ⏵ -> »
        "\u23F8" to "\u2016", // ⏸ -> ‖
        "\u23EF" to "\u00BB", // ⏯ -> »
        "\u23F9" to "\u25A0", // ⏹ -> ■
    )

    private fun plainGlyphs(line: String): String = STATUS_GLYPHS.fold(line) { acc, (from, to) -> acc.replace(from, to) }

    /**
     * [agent] is the pane's canonical agent name. For Claude and Hermes (2.1.2) the input box found
     * by [composerBoxStart] and everything under it is the footer, never prose: a draft or Hermes's
     * placeholder in the box showed as a sent message when no session log gave the chat.
     */
    fun parse(text: String, antigravity: Boolean = false, grok: Boolean = false, agent: String? = null): List<Seg> {
        val boxAt = composerBoxStart(text, agent)
        var raw = text.lines().map { plainGlyphs(clean(it)) }
        // Antigravity's usage panel ("└ Models & Quota", from /usage) is a quiet line, never prose
        // (2.1.1). Open, it hangs under the composer to the bottom of the screen; one left above
        // the composer ends at the next rule or input row.
        val usageAt = mutableMapOf<Int, String>()
        var usageOpen = false
        if (antigravity) {
            val kept = mutableListOf<String>()
            var i = 0
            while (i < raw.size) {
                if (!AgyMeta.isUsageHead(raw[i])) { kept += raw[i]; i++; continue }
                // Open when no input row follows it; its own rows may hold rules.
                val open = raw.drop(i + 1).none { val t = it.trim(); t == ">" || PROMPT_ONLY.matches(it) }
                var end = i + 1
                if (open) end = raw.size
                else {
                    while (end < raw.size && !isRule(raw[end]) && !PROMPT_ONLY.matches(raw[end])) end++
                    // Up to its last row that reads as the panel's own; text after that is the agent's.
                    end = (end - 1 downTo i + 1).firstOrNull { AgyMeta.isUsageRow(raw[it]) }?.plus(1) ?: (i + 1)
                }
                usageAt[kept.size] = AgyMeta.usageLine(raw.subList(i, end).joinToString("\n"), open)
                if (open) usageOpen = true else kept += ""
                i = end
            }
            raw = kept
        }

        // The agent's input box and status line: from the first rule or box edge among the last
        // five lines with text on them, or else from the last input prompt there.
        val window = raw.indices.filter { raw[it].isNotBlank() }.takeLast(5)
        // Antigravity replies can end with a short table or separator when the composer is
        // not visible. Only peel off its footer when we see a prompt between composer rules.
        val agyComposer = if (antigravity) window.lastOrNull { i ->
            val prompt = raw[i].trim()
            (prompt == ">" || prompt.startsWith("> ")) &&
                raw.getOrNull(i - 1)?.let(::isRule) == true &&
                raw.getOrNull(i + 1)?.let(::isRule) == true &&
                // With the usage panel open, the panel stands where the status bar was.
                (raw.drop(i + 2).any(AgyMeta::isStatusBar) || usageOpen && raw.drop(i + 2).all { it.isBlank() })
        } else null
        // A Grok reply can end in a box too. Require the native prompt, model border and mode hint.
        val grokComposer = if (grok) window.lastOrNull { i ->
            GROK_PROMPT.matches(raw[i].trim()) &&
                raw.getOrNull(i - 1)?.trim()?.matches(Regex("╭─+╮")) == true &&
                raw.getOrNull(i + 1)?.trim()?.let { it.startsWith("╰─") && it.endsWith("─╯") && " Grok " in it } == true &&
                raw.drop(i + 2).firstOrNull { it.isNotBlank() }?.trimStart()?.startsWith("Shift+Tab:mode") == true
        } else null
        // Antigravity marks the selected menu row with the same glyph as a user echo.
        // Recognize a numbered menu only when a neighboring numbered choice confirms it.
        val agyChoices = if (antigravity && agyComposer == null) window.lastOrNull { i ->
            val selected = SELECTED_CHOICE.matchEntire(raw[i].trim())?.groupValues?.get(1)?.toInt()
            selected != null && listOf(-1, 1).any { offset ->
                val neighbor = raw.getOrNull(i + offset)?.trim()?.let(OTHER_CHOICE::matchEntire)
                neighbor?.groupValues?.get(1)?.toInt() == selected + offset
            }
        }?.let { selected ->
            var start = selected
            var number = SELECTED_CHOICE.matchEntire(raw[selected].trim())!!.groupValues[1].toInt()
            while (start > 0 && OTHER_CHOICE.matchEntire(raw[start - 1].trim())?.groupValues?.get(1)?.toInt() == number - 1) {
                start--
                number--
            }
            start
        } else null
        val footerAt = if (boxAt != null && !antigravity && !grok) boxAt else if (antigravity || grok) (agyComposer ?: grokComposer)?.minus(1) ?: agyChoices ?: raw.size else
            window.firstOrNull { i ->
                isRule(raw[i]) || raw[i].trimStart().firstOrNull()?.let { it in CHROME_START } == true
            } ?: window.lastOrNull { PROMPT_ONLY.matches(raw[it]) } ?: raw.size
        val body = raw.subList(0, footerAt)
        val footer = (footerAt until raw.size)
            // The rule over Claude's or Hermes's box may carry the session's title: an edge, not text.
            .filterNot { isRule(raw[it]) || it == boxAt }
            .map { i ->
                if (i == agyComposer) raw[i].trimStart().removePrefix(">").trim()
                else if (antigravity) AgyMeta.footerLine(raw[i].filterNot { it in BOX }.trim().removePrefix("❯").removePrefix("›").trim())
                else raw[i].filterNot { it in BOX }.trim().removePrefix("❯").removePrefix("›").trim()
            }
            .filter { it.isNotEmpty() }
            // An empty box's own hint ("describe a task for a new session") is not text either.
            .filterNot { boxAt != null && isComposerHint(agent, it) }

        val width = body.maxOfOrNull { it.length }?.coerceAtLeast(20) ?: 80
        val margin = body.filter { it.isNotBlank() && !isWide(it) }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0

        val out = mutableListOf<Seg>()
        var para: P? = null
        var you: Y? = null
        val code = mutableListOf<String>()
        // Antigravity: the meta lines seen since its last text, and whether a blank line came after them.
        val act = mutableListOf<AgyMeta.Step>()
        var gapAfterAct = false
        // Only prose loses private-use glyphs; tables and code keep every character.
        val prose: (String) -> String = if (antigravity) AgyMeta::scrub else { s -> s }

        fun flushAct() {
            if (act.isEmpty()) return
            out += Seg.Activity(act.toList())
            act.clear()
            if (gapAfterAct) out += Seg.Gap
            gapAfterAct = false
        }
        fun flushPara() {
            para?.let { p -> prose(p.sb.toString()).takeIf { it.isNotEmpty() }?.let { out += Seg.Para((p.indent - margin).coerceAtLeast(0), p.mark, it, p.detail) } }
            para = null
        }
        fun flushYou() {
            you?.let { y -> prose(y.sb.toString()).takeIf { it.isNotEmpty() }?.let { out += Seg.You(it) } }
            you = null
        }
        fun flushCode() {
            if (code.isEmpty()) return
            val cut = code.minOf { it.length - it.trimStart().length }
            out += Seg.Code(code.map { it.drop(cut) })
            code.clear()
        }
        fun flushAll() { flushPara(); flushYou(); flushCode() }

        for ((at, l) in body.withIndex()) {
            val usage = usageAt[at]
            if (usage != null) {
                flushAll(); flushAct()
                out += Seg.Para(0, null, usage, detail = true)
                if (l.isBlank()) continue
            }
            if (l.isBlank()) {
                flushAll()
                if (act.isNotEmpty()) gapAfterAct = true
                else if (out.isNotEmpty() && out.last() != Seg.Gap) out += Seg.Gap
                continue
            }
            val t = l.trim()
            val lead = l.length - l.trimStart().length
            if (antigravity && lead - margin <= 2) {
                val step = AgyMeta.step(t)
                if (step != null) {
                    flushAll()
                    gapAfterAct = false
                    act += step
                    continue
                }
                // "──── Conversation compacted ────": a rule, and what it says, quietly.
                val title = AgyMeta.ruleTitle(t)
                if (title != null) {
                    flushAll(); flushAct()
                    out += Seg.Rule
                    out += Seg.Para(0, null, title, detail = true)
                    continue
                }
                // A notice box ("┃ Auto is now the default agent mode"): its text, without the edge or key hint.
                val notice = AgyMeta.notice(t)
                if (notice != null) {
                    flushAll(); flushAct()
                    if (notice.isNotEmpty() && !AgyMeta.isKeyHint(notice)) out += Seg.Para(0, null, AgyMeta.scrub(notice), detail = true)
                    continue
                }
                val warning = AgyMeta.warning(t)
                if (warning != null) {
                    flushAll(); flushAct()
                    para = P(lead, null, lead, false, warning, l.length)
                    continue
                }
            }
            flushAct()
            if (isRule(t) && !((antigravity || grok) && TABLE_RULE.matches(t))) {
                flushAll()
                out += Seg.Rule
                continue
            }
            if (isWide(t)) {
                flushPara(); flushYou()
                code += l
                continue
            }
            flushCode()
            val m = MARK.find(t)
            val y = you
            if (y != null && m == null && lead >= y.hang) {
                y.sb.append(' ').append(t)
                continue
            }
            flushYou()
            val u = USER.find(t)
            if (u != null && lead - margin <= 4) {
                flushPara()
                you = Y(lead + 2, u.groupValues[1])
                continue
            }
            val p = para
            if (p != null && m == null && (lead == p.hang || lead == p.indent) && p.lastLen >= width * 0.6) {
                join(p.sb, t, p.lastLen, width)
                p.lastLen = l.length
                continue
            }
            flushPara()
            para = if (m != null) {
                val mark = m.value.trim()
                // Antigravity prefixes top-level replies and activity with terminal markers.
                // Keep the hanging indent for reflow, and leave nested bullets and tables alone.
                val content = t.substring(m.value.length)
                val chrome = antigravity && lead == margin && (mark == "●" || mark == "○") &&
                    m.value.last().isWhitespace()
                val grokThought = grok && lead == margin && mark == "◆" && GROK_THOUGHT.matches(content)
                P(lead, mark.takeUnless { chrome || grokThought }, lead + m.value.length,
                    mark == "⎿" || (chrome && mark == "○") || grokThought, content, l.length)
            } else {
                P(lead, null, lead, false, t, l.length)
            }
        }
        flushAll()
        gapAfterAct = false
        flushAct()
        // An open panel cut at the composer: after the reply, before the footer.
        usageAt.filterKeys { it >= body.size }.values.forEach { out += Seg.Para(0, null, it, detail = true) }
        while (out.firstOrNull() == Seg.Gap) out.removeAt(0)
        while (out.lastOrNull() == Seg.Gap || out.lastOrNull() == Seg.Rule) out.removeAt(out.size - 1)
        if (footer.isNotEmpty()) out += Seg.Footer(footer)
        return out
    }
}
