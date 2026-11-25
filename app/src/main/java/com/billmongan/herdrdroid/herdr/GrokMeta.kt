package com.billmongan.herdrdroid.herdr

/**
 * Grok's meta output on its screen (2.1.2, Bill: "touch up the rendering view for grok, it's not
 * bad but the occasional glyph"). Grok draws its tool groups ("◈ Explored 3 files, ran 3
 * commands"), a running tool ("◆ Running scan recordings"), its status row ("◆ Scan… 4m28s
 * 14m29s ⇣84.1k [↓][stop]"), the "Worked for 7m19s" turn marker, a header bar with the folder and
 * context use, the data-sharing banner and an approval box with a "┃" edge between its replies.
 * Chat showed them as prose or code, glyphs and all. This sorts them the way [AgyMeta] does for
 * Antigravity, and reuses its steps, so Chat folds them into the same activity chip.
 *
 * 2.1.3: a long command in the approval box runs on over rows with a "┆" gutter and a "·" at the
 * end of each wrapped row. [box] marks those rows, and Reflow joins them into one command line.
 * 2.1.4: [commandRow] reads those rows in any frame (heavy "┃", light "│ … │", or none), and a
 * "┆" row joins the row above only when that row ends in "·"; otherwise it is a line of its own.
 */
object GrokMeta {
    // "◆ Thought for 2.3s", "◆ Thought for 1m 4s".
    private val THOUGHT = Regex("""^◆\s+(Thought for \d[\d.hms ]*[hms])$""")

    // "◈ Explored 3 files, ran 3 commands", "◈ Created a.md, ran 13 commands +23", "◈ Explored 5 files, 7 searches".
    private val TOOL = Regex("""^◈\s+(\S.*)$""")

    // "◆ Running scan recordings and compare published player": the tool in flight.
    private val RUNNING = Regex("""^◆\s+(\S.*)$""")

    // The status row: "◆ Scan recordings and compare published pl… 4m28s      14m29s ⇣84.1k [↓][stop]".
    private val STATUS = Regex("""^\s*[◆◇◈]?\s*(\S.*?)\s{3,}(?:(\d[\dhms.]*)\s+)?⇣\s*[\d.]+[kKM]?\b.*$""")
    private val STOP = Regex("""\[stop]\s*$|⇣\s*[\d.]+[kKM]?\s*(?:\[↓])?\s*(?:\[stop])?\s*$""")

    // "Worked for 7m19s": the line that closes a turn.
    private val WORKED = Regex("""^Worked for \d[\d.hms ]*[hms]$""")

    // "Context 81% full. Compacting…"
    private val COMPACTING = Regex("""^Context \d{1,3}% full\. Compacting(?:…|\.\.\.)?$""")

    // "Context compacted: 206.1k → 29.8k tokens (114.0s)", the notice after it (2.1.3).
    private val COMPACTED = Regex("""^Context compacted: [\d.]+[kKM]? (?:→|->) [\d.]+[kKM]? tokens(?: \([\d.]+s\))?$""")

    // The header bar: "~/agents/grok          174k / 256k │ [Dashboard]".
    private val HEADER = Regex("""^([~/]\S*)\s{3,}(\d+(?:\.\d+)?[kKmM]?\s*/\s*\d+(?:\.\d+)?[kKmM]?)(?:\s*│\s*\[Dashboard])?$""")

    // The data-sharing banner (the same rows Herdr.kt keeps out of a card's question).
    private val BANNER = Regex("""Help improve Grok|\[Opt out]\s*\[Opt in]|Opt-in to allow SpaceXAI|Read Terms and Privacy Policy|Change anytime via settings""")
    private val BUTTONS = Regex("""\s*\[Opt out]\s*\[Opt in]\s*$""")

    // A key row under an approval or question box: "1/4:select  │  Tab:next option  │  Esc:scrollback".
    private val KEYS = Regex("""^\S+:\S+(?: \S+)*(?:\s+│\s+\S+:\S+(?: \S+)*)+$""")

    // Box rows that are only a key hint: "... Ctrl-F to expand", "← → narrow scope".
    // 2.2.8: and "← widen scope" (Grok 4.7's scope row).
    private val BOX_HINT = Regex("""^(?:(?:\.\.\.|…)\s*Ctrl[-+]\w+ to \w+|←\s*→\s+.*|←\s+widen scope)$""")
    private val BOX_CHOICE = Regex("""^(\d{1,2})\s+\(([●○])\)\s+(\S.*)$""")

    /** Grok's meta line [t] (trimmed) as an activity step, or null when it is Grok's own text. */
    fun step(t: String): AgyMeta.Step? {
        THOUGHT.matchEntire(t)?.let { return AgyMeta.Step(AgyMeta.Kind.THINK, it.groupValues[1].trim()) }
        TOOL.matchEntire(t)?.let { m ->
            val head = AgyMeta.scrub(m.groupValues[1])
            return AgyMeta.Step(AgyMeta.kindOf(head), head)
        }
        RUNNING.matchEntire(t)?.let { m ->
            val head = AgyMeta.scrub(m.groupValues[1])
            return AgyMeta.Step(AgyMeta.kindOf(head), head, running = true)
        }
        return null
    }

    /**
     * Grok's status row ([line] as read, before [Reflow.clean] cuts its right-hand side) as a
     * running step: what it is doing, with the turn's time as the detail; null if not one.
     */
    fun statusRow(line: String): AgyMeta.Step? {
        if (!STOP.containsMatchIn(line)) return null
        val m = STATUS.matchEntire(line.trimEnd()) ?: return null
        val what = AgyMeta.scrub(m.groupValues[1]).ifEmpty { return null }
        return AgyMeta.Step(AgyMeta.Kind.STATUS, what, m.groupValues[2].ifBlank { null }, running = true)
    }

    /** A meta line shown as a quiet row: the turn marker, compaction notice or header bar; else null. */
    fun quiet(t: String): String? {
        if (WORKED.matches(t) || COMPACTING.matches(t) || COMPACTED.matches(t)) return t
        HEADER.matchEntire(t)?.let { return it.groupValues[1] + " · " + it.groupValues[2].replace(Regex("""\s+"""), " ") }
        return null
    }

    /** A row of the data-sharing banner, without its "[Opt out] [Opt in]" buttons; null if not one. */
    fun banner(t: String): String? = if (BANNER.containsMatchIn(t)) t.replace(BUTTONS, "").trim() else null

    /** True for the key row under an approval or question box, which goes to the footer. */
    fun isKeyRow(t: String): Boolean = KEYS.matches(t.trim())

    /** What one row of Grok's approval or question box ("┃ ...") holds. */
    sealed interface Box {
        data object Blank : Box
        data object Hint : Box
        data class Title(val text: String) : Box
        /**
         * A command row; [cont] when it carries on the row above ("┆", 2.1.3); [wraps] when Grok
         * wrapped it ("·" at its end), so the next "┆" row is the rest of this line (2.1.4).
         */
        data class Code(val line: String, val cont: Boolean = false, val wraps: Boolean = false) : Box
        data class Choice(val n: String, val text: String) : Box
        data class Text(val text: String) : Box
    }

    /**
     * The gutters Grok draws down a wrapped command's rows (2.1.3, Bill: "Notice the vertical pipe
     * glyphs coming through"). Chat showed each row as prose, "┆" and "·" included.
     */
    const val GUTTER = "┆┊╎╏"

    /** A command row without Grok's "·" wrap mark at its end. */
    private fun unwrap(row: String): String = row.trimEnd().removeSuffix("·").trimEnd()

    /**
     * A wrapped command's row outside the approval box ("┆ /tmp/b.zip;·"), as its text, or null
     * when [t] (trimmed) isn't one (2.1.3).
     */
    fun gutterRow(t: String): String? =
        if (t.length > 1 && t[0] in GUTTER && t[1] == ' ') unwrap(t.drop(2)) else null

    /**
     * One row of a command in Grok's approval box (2.1.4): its text, without the frame, the gutter
     * or the wrap mark; [cont] for a "┆" row, which carries on the row above; [wraps] when the row
     * ends in "·", Grok's mark that it wrapped the line there.
     */
    data class CmdRow(val text: String, val cont: Boolean, val wraps: Boolean)

    private fun gutterStart(e: String) = e.isNotEmpty() && e[0] in GUTTER && (e.length == 1 || e[1] == ' ')

    /**
     * [row] without the frame Grok draws round its approval box (2.1.4): the heavy "┃" edge on the
     * left, or a light box's "│" on the left and, when there is one, on the right. Second: the light frame.
     */
    private class Unframed(val text: String, val light: Boolean, val lead: Int)

    private fun unframe(row: String): Unframed {
        val s = row.trim()
        if (s.startsWith("┃")) return s.drop(1).let { Unframed(it.trimStart(), false, it.length - it.trimStart().length) }
        if (!s.startsWith("│")) return Unframed(s, false, 0)
        val inner = if (s.length > 2 && s.endsWith("│")) s.substring(1, s.length - 1) else s.drop(1)
        return Unframed(inner.trim(), true, inner.length - inner.trimStart().length)
    }

    private fun cmdRow(text: String, cont: Boolean): CmdRow {
        val t = text.trimEnd()
        return CmdRow(unwrap(t), cont, t.endsWith("·"))
    }

    /**
     * [row] as a row of a command Grok drew in its approval box, or null (2.1.4, Bill: "Herdrdroid
     * still shows vertical bars for grok questions like this"). Grok draws the command's first row
     * after a "│" and each row it wrapped onto after a "┆", with a "·" at the end of each wrapped
     * row; around that sits the box's own frame, the heavy "┃" edge or a light "│ … │" box, or no
     * frame at all. 2.1.3 read the rows only inside the heavy edge, so in the light box the first
     * row was the command and the "┆" rows were text, and the card showed them out of order.
     * Without a frame the first row's "│" looks like any edge, so it counts when it ends in "·"
     * and [below] (the next screen row) is a "┆" row.
     */
    fun commandRow(row: String, below: String?): CmdRow? {
        val u = unframe(row)
        val e = u.text
        return when {
            gutterStart(e) -> cmdRow(e.drop(1).removePrefix(" "), cont = true)
            // In a light frame the command's "│" sits just inside the edge ("│  │ git …"); a row with
            // more "│" in it, or a wider gap, is a table's (an empty first cell), not a command's.
            e.startsWith("│") && !(u.light && (u.lead !in 1..3 || e.drop(1).contains('│'))) -> cmdRow(e.drop(1).removePrefix(" "), cont = false)
            below != null && e.trimEnd().endsWith("·") && gutterStart(unframe(below).text) -> cmdRow(e, cont = false)
            else -> null
        }
    }

    /**
     * A row of Grok's approval drawn with no frame at all (2.1.4): its key hint, a "1 (○) …" choice,
     * or the "›" title right over a command row ([below], then [below2]); else null.
     */
    fun bareBox(l: String, below: String?, below2: String?): Box? {
        val c = l.trim()
        if (BOX_HINT.matches(c)) return Box.Hint
        BOX_CHOICE.matchEntire(c)?.let { return Box.Choice(it.groupValues[1] + ".", AgyMeta.scrub(it.groupValues[3])) }
        if (c.length > 2 && c[0] == '›' && c[1].isWhitespace() && below != null && commandRow(below, below2) != null)
            return Box.Title(AgyMeta.scrub(c.drop(1).trim()))
        return null
    }

    /** [l] (one cleaned screen line) as a box row, or null when it isn't one. */
    fun box(l: String): Box? {
        val s = l.trimStart()
        val heavy = s.startsWith("┃")
        // 2.1.4: the light "│ … │" box too, for its command, title, hint and choice rows only.
        if (!heavy && !s.startsWith("│")) return null
        commandRow(l, null)?.let { return Box.Code(it.text, it.cont, it.wraps) }
        val c = unframe(l).text
        if (!heavy) return when {
            c.isEmpty() -> Box.Blank
            BOX_HINT.matches(c) -> Box.Hint
            c.length > 2 && (c[0] == '›' || c[0] == '?') && c[1].isWhitespace() -> Box.Title(AgyMeta.scrub(c.drop(1).trim()))
            else -> BOX_CHOICE.matchEntire(c)?.let { Box.Choice(it.groupValues[1] + ".", AgyMeta.scrub(it.groupValues[3])) }
        }
        return when {
            c.isEmpty() -> Box.Blank
            BOX_HINT.matches(c) -> Box.Hint
            c.length > 2 && (c[0] == '›' || c[0] == '?') && c[1].isWhitespace() -> Box.Title(AgyMeta.scrub(c.drop(1).trim()))
            else -> BOX_CHOICE.matchEntire(c)?.let { Box.Choice(it.groupValues[1] + ".", AgyMeta.scrub(it.groupValues[3])) }
                ?: Box.Text(AgyMeta.scrub(c))
        }
    }

    /** A one-line summary of a run of Grok's steps, for the chip's title. */
    fun summary(steps: List<AgyMeta.Step>): String {
        val thought = steps.firstOrNull { it.kind == AgyMeta.Kind.THINK }?.title
        val tools = steps.filter { it.kind != AgyMeta.Kind.THINK && !it.running }
        val running = steps.lastOrNull { it.running }?.title
        // Grok's rows are already summaries ("Explored 3 files, ran 3 commands"): show the first.
        val work = running ?: tools.firstOrNull()?.let { t -> t.title + if (tools.size > 1) " (+${tools.size - 1} more)" else "" }
        return listOfNotNull(thought, work).joinToString(" · ").ifEmpty { "Working" }
    }
}
