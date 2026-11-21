package com.billmongan.herdrdroid.herdr

/**
 * Grok's meta output on its screen (2.1.2, Bill: "touch up the rendering view for grok, it's not
 * bad but the occasional glyph"). Grok draws its tool groups ("◈ Explored 3 files, ran 3
 * commands"), a running tool ("◆ Running scan recordings"), its status row ("◆ Scan… 4m28s
 * 14m29s ⇣84.1k [↓][stop]"), the "Worked for 7m19s" turn marker, a header bar with the folder and
 * context use, the data-sharing banner and an approval box with a "┃" edge between its replies.
 * Chat showed them as prose or code, glyphs and all. This sorts them the way [AgyMeta] does for
 * Antigravity, and reuses its steps, so Chat folds them into the same activity chip.
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

    // The header bar: "~/agents/grok          174k / 256k │ [Dashboard]".
    private val HEADER = Regex("""^([~/]\S*)\s{3,}(\d+(?:\.\d+)?[kKmM]?\s*/\s*\d+(?:\.\d+)?[kKmM]?)(?:\s*│\s*\[Dashboard])?$""")

    // The data-sharing banner (the same rows Herdr.kt keeps out of a card's question).
    private val BANNER = Regex("""Help improve Grok|\[Opt out]\s*\[Opt in]|Opt-in to allow SpaceXAI|Read Terms and Privacy Policy|Change anytime via settings""")
    private val BUTTONS = Regex("""\s*\[Opt out]\s*\[Opt in]\s*$""")

    // A key row under an approval or question box: "1/4:select  │  Tab:next option  │  Esc:scrollback".
    private val KEYS = Regex("""^\S+:\S+(?: \S+)*(?:\s+│\s+\S+:\S+(?: \S+)*)+$""")

    // Box rows that are only a key hint: "... Ctrl-F to expand", "← → narrow scope".
    private val BOX_HINT = Regex("""^(?:(?:\.\.\.|…)\s*Ctrl[-+]\w+ to \w+|←\s*→\s+.*)$""")
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
        if (WORKED.matches(t) || COMPACTING.matches(t)) return t
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
        data class Code(val line: String) : Box
        data class Choice(val n: String, val text: String) : Box
        data class Text(val text: String) : Box
    }

    /** [l] (one cleaned screen line) as a box row, or null when it isn't one. */
    fun box(l: String): Box? {
        val s = l.trimStart()
        if (!s.startsWith("┃")) return null
        val inner = s.removePrefix("┃")
        val c = inner.trim()
        return when {
            c.isEmpty() -> Box.Blank
            // The command or diff, every character kept: only the box edge and "│ " go.
            c.startsWith("│") -> Box.Code(inner.trimStart().removePrefix("│").removePrefix(" "))
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
