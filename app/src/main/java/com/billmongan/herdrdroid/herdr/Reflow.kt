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

    fun parse(text: String, antigravity: Boolean = false): List<Seg> {
        val raw = text.lines().map(::clean)

        // The agent's input box and status line: from the first rule or box edge among the last
        // five lines with text on them, or else from the last input prompt there.
        val window = raw.indices.filter { raw[it].isNotBlank() }.takeLast(5)
        val footerAt = window.firstOrNull { i ->
            isRule(raw[i]) || raw[i].trimStart().firstOrNull()?.let { it in CHROME_START } == true
        } ?: window.lastOrNull { PROMPT_ONLY.matches(raw[it]) } ?: raw.size
        val body = raw.subList(0, footerAt)
        val footer = raw.subList(footerAt, raw.size)
            .filterNot { isRule(it) }
            .map { l -> l.filterNot { it in BOX }.trim().removePrefix("❯").removePrefix("›").trim() }
            .filter { it.isNotEmpty() && !(antigravity && it == ">") }

        val width = body.maxOfOrNull { it.length }?.coerceAtLeast(20) ?: 80
        val margin = body.filter { it.isNotBlank() && !isWide(it) }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0

        val out = mutableListOf<Seg>()
        var para: P? = null
        var you: Y? = null
        val code = mutableListOf<String>()

        fun flushPara() {
            para?.let { out += Seg.Para((it.indent - margin).coerceAtLeast(0), it.mark, it.sb.toString(), it.detail) }
            para = null
        }
        fun flushYou() {
            you?.let { out += Seg.You(it.sb.toString()) }
            you = null
        }
        fun flushCode() {
            if (code.isEmpty()) return
            val cut = code.minOf { it.length - it.trimStart().length }
            out += Seg.Code(code.map { it.drop(cut) })
            code.clear()
        }
        fun flushAll() { flushPara(); flushYou(); flushCode() }

        for (l in body) {
            if (l.isBlank()) {
                flushAll()
                if (out.isNotEmpty() && out.last() != Seg.Gap) out += Seg.Gap
                continue
            }
            val t = l.trim()
            val lead = l.length - l.trimStart().length
            if (isRule(t)) {
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
                val chrome = antigravity && lead == margin && (mark == "●" || mark == "○") &&
                    m.value.last().isWhitespace()
                P(lead, mark.takeUnless { chrome }, lead + m.value.length,
                    mark == "⎿" || (chrome && mark == "○"), t.substring(m.value.length), l.length)
            } else {
                P(lead, null, lead, false, t, l.length)
            }
        }
        flushAll()
        while (out.firstOrNull() == Seg.Gap) out.removeAt(0)
        while (out.lastOrNull() == Seg.Gap || out.lastOrNull() == Seg.Rule) out.removeAt(out.size - 1)
        if (footer.isNotEmpty()) out += Seg.Footer(footer)
        return out
    }
}
