package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.QKind
import com.billmongan.herdrdroid.herdr.QuestionParts

/**
 * 2.1.1 (Bill: "text in question cards could be formatted in a more friendly way it's kind of a
 * wall of text and code right now"): how a question card's body is laid out on the phone.
 *
 * This is display only. The parsers' [QuestionParts] are not changed, so option detection, the
 * card's identity and the guarded answer path are exactly as before. The formatter reads each
 * block's text and, when it can find that same text on the pane's screen, the screen lines it was
 * joined from, so a diff, a plan or a script keeps its lines instead of running together. Nothing
 * is reworded: every block shows its own text, and Show raw and Show terminal show the rest.
 */
sealed interface CardBlock {
    /** The question itself, bold and first. */
    data class Headline(val text: String) : CardBlock
    /** A short lead-in: "Host:", "Question 1/2  Database", "Here is Claude's plan:". */
    data class Caption(val text: String) : CardBlock
    /** A plan's or section's title ("# Add greeting endpoint" shows as "Add greeting endpoint"). */
    data class Subhead(val text: String) : CardBlock
    /** A paragraph, its terminal-wrapped lines joined so it wraps at the phone's width. */
    data class Prose(val text: String) : CardBlock
    /** "Reason: …", "Environment: local": the label bold, the value after it. */
    data class Labelled(val label: String, val value: String) : CardBlock
    /** A bulleted or numbered list. */
    data class Items(val items: List<CardItem>) : CardBlock
    /**
     * A command, script, path or diff in monospace. [diff] colours its +/- lines; [wrap] is for one
     * line (a long path or command wraps instead of scrolling sideways).
     */
    data class Code(val lines: List<String>, val diff: Boolean = false, val wrap: Boolean = false) : CardBlock
}

/** One list entry: its own marker ("·", "-", "1.") and its text. */
data class CardItem(val mark: String, val text: String)

object CardFormat {
    /** Leading characters questionParts drops from a line (its MARKS), so the two agree on text. */
    private const val MARKS = "›❯◆◈●○•▸➤▶↓→│┃╭╰╮╯─ "
    private const val FRAME = " │┃"
    private val LABEL = Regex("""^([A-Z][A-Za-z ]{0,24}):\s+(\S.*)$""")
    private val BULLET = Regex("""^([·•◦*\-]|\d{1,3}[.)])\s+(\S.*)$""")
    private val DIFF_NUMBERED = Regex("""^\d+\s+[+-](\s|$)""")
    private val DIFF_LINE = Regex("""^(\d+\s+)?[+-](\s|$)|^@@""")
    /** A numbered context line of an edit ("12      def greet(name):"). */
    private val NUMBERED_CONTEXT = Regex("""^\d+\s{2,}\S""")
    private val HEADER = Regex("""^#{1,3}\s+(\S.*)$""")
    /** One token that reads as a path, address or file name. */
    private val PATH = Regex("""^[~/.\w-]*[/.][\w./~:@%+=-]*$""")
    private const val SHORT = 40
    private val URL = Regex("""^[a-z][a-z0-9+.-]*://\S+$""")
    /** A dot inside a name ("app.py", "docs.example.com"), not a full stop at the end of a word. */
    private val FILE_DOT = Regex("""\w\.\w""")
    /** One line that reads as a shell command: a lower-case program, then flags or paths ("rm -rf /x/build"). */
    private val SHELL = Regex("""^[a-z][\w.-]*(\s+(-{1,2}[\w-]+|\S*/\S*))+(\s+\S+)*$""")

    /** A screen line: [norm] as questionParts sees it, [text] without the box around it, [col] where it starts. */
    private class Line(val norm: String, val text: String, val col: Int)

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    private fun screenLines(screen: String): List<Line> = screen.lines().map { raw ->
        val l = raw.trimEnd().trimEnd { it in FRAME || it in "╮╯" }
        var norm = l.trim().trimStart { it in MARKS }.trimEnd { it in "│┃╮╯─ " }.trim()
        norm = norm.removePrefix("$ ").trim()
        val lead = l.indexOfFirst { it !in FRAME }.let { if (it < 0) l.length else it }
        var text = l.substring(lead)
        var col = lead
        if (text.startsWith("$ ")) { text = text.substring(2); col += 2 }
        Line(norm, text.trimEnd(), col)
    }

    /**
     * The screen lines a block's text was joined from (the last place it appears, nearest the
     * options), skipping lines with no letters or digits, as questionParts does. Null when the
     * text is not a whole run of lines.
     */
    private fun locate(text: String, lines: List<Line>): IntRange? {
        val target = squash(text)
        if (target.isEmpty()) return null
        val content = lines.indices.filter { i -> lines[i].norm.any(Char::isLetterOrDigit) }
        for (s in content.indices.reversed()) {
            var acc = ""
            for (k in s until content.size) {
                val norm = lines[content[k]].norm
                // The first line may carry a glyph the parser left out ("△ Permission required").
                acc += squash(if (k == s && !target.startsWith(squash(norm))) norm.trimStart { !it.isLetterOrDigit() } else norm)
                if (acc == target) return content[s]..content[k]
                if (!target.startsWith(acc)) break
            }
        }
        return null
    }

    private fun contentOf(range: IntRange, lines: List<Line>): List<Line> =
        range.map { lines[it] }.filter { it.norm.any(Char::isLetterOrDigit) }

    /** Lines with their relative indentation kept. */
    private fun dedented(ls: List<Line>): List<String> {
        val min = ls.minOfOrNull { it.col } ?: 0
        return ls.map { " ".repeat((it.col - min).coerceAtMost(16)) + it.text }
    }

    fun isDiff(lines: List<String>): Boolean {
        val t = lines.map { it.trim() }
        return t.any { DIFF_NUMBERED.containsMatchIn(it) || it.startsWith("@@") } ||
            (t.any { it.startsWith("+") && !it.startsWith("+++") } && t.any { it.startsWith("-") && !it.startsWith("---") })
    }

    private fun isPath(t: String) = t.length >= 3 && !t.any(Char::isWhitespace) && t.any(Char::isLetter) &&
        (URL.matches(t) || (PATH.matches(t) && (t.contains('/') || FILE_DOT.containsMatchIn(t))))

    /** One block of context with no lines to go on. */
    private fun single(t: String): CardBlock {
        val s = t.trim()
        return when {
            NUMBERED_CONTEXT.containsMatchIn(s) || DIFF_NUMBERED.containsMatchIn(s) -> CardBlock.Code(listOf(s), diff = DIFF_NUMBERED.containsMatchIn(s))
            s.endsWith(":") && s.length <= SHORT -> CardBlock.Caption(s)
            LABEL.matches(s) -> LABEL.find(s)!!.let { CardBlock.Labelled(it.groupValues[1], it.groupValues[2]) }
            isPath(s) || SHELL.matches(s) -> CardBlock.Code(listOf(s), wrap = true)
            else -> CardBlock.Prose(s)
        }
    }

    /** Several screen lines of context: paragraphs, lists, diffs, paths and section titles. */
    private fun body(ls: List<Line>): List<CardBlock> {
        val texts = ls.map { it.text.trim() }
        val diff = isDiff(texts)
        val out = mutableListOf<CardBlock>()
        val prose = mutableListOf<String>()
        val items = mutableListOf<CardItem>()
        var itemCol = -1
        val code = mutableListOf<Line>()
        fun flushProse() { if (prose.isNotEmpty()) out += single(prose.joinToString(" ")).let { if (it is CardBlock.Code) CardBlock.Prose(prose.joinToString(" ")) else it }; prose.clear() }
        fun flushItems() { if (items.isNotEmpty()) out += CardBlock.Items(items.toList()); items.clear(); itemCol = -1 }
        fun flushCode() { if (code.isNotEmpty()) out += CardBlock.Code(dedented(code), diff = true); code.clear() }
        for ((i, l) in ls.withIndex()) {
            val t = texts[i]
            if (diff && (DIFF_LINE.containsMatchIn(t) || NUMBERED_CONTEXT.containsMatchIn(t) || (code.isNotEmpty() && l.col > code.first().col))) {
                flushProse(); flushItems(); code += l; continue
            }
            flushCode()
            val b = BULLET.find(t)
            if (b != null) {
                flushProse()
                items += CardItem(b.groupValues[1], b.groupValues[2]); itemCol = l.col; continue
            }
            if (items.isNotEmpty() && l.col > itemCol) {
                val last = items.removeAt(items.size - 1); items += last.copy(text = last.text + " " + t); continue
            }
            flushItems()
            val h = HEADER.find(t)
            if (h != null) { flushProse(); out += CardBlock.Subhead(h.groupValues[1]); continue }
            // A short line over a list is the list's title, not the start of a paragraph.
            if (t.length <= SHORT && texts.getOrNull(i + 1)?.let { BULLET.matches(it) } == true) {
                flushProse(); out += if (t.endsWith(":")) single(t) else CardBlock.Caption(t); continue
            }
            if (isPath(t) || LABEL.matches(t) || (t.endsWith(":") && t.length <= SHORT)) { flushProse(); out += single(t); continue }
            // A new sentence after a finished one starts a new paragraph only when the line before
            // was short (a title over its text); wrapped prose goes on.
            if (prose.isNotEmpty() && prose.last().length <= SHORT && t.first().isUpperCase()) flushProse()
            prose += t
        }
        flushProse(); flushItems(); flushCode()
        return out
    }

    /** A command block: its lines, or a plan ("# Title" over a list) shown as a title and a list. */
    private fun command(ls: List<String>, located: List<Line>?): List<CardBlock> {
        val lines = if (located != null && located.size >= 2) dedented(located) else ls
        val trimmed = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.size >= 2 && HEADER.matches(trimmed.first()) && trimmed.drop(1).all { BULLET.matches(it) }) {
            return listOf(CardBlock.Subhead(HEADER.find(trimmed.first())!!.groupValues[1]),
                CardBlock.Items(trimmed.drop(1).map { BULLET.find(it)!!.let { m -> CardItem(m.groupValues[1], m.groupValues[2]) } }))
        }
        return listOf(CardBlock.Code(lines, diff = lines.size >= 2 && isDiff(lines), wrap = lines.size == 1))
    }

    /** The heading: a question, with any short lead-in above it as a caption and the rest as context. */
    private fun heading(text: String, located: List<Line>?): List<CardBlock> {
        if (located == null || located.size < 2) return listOf(CardBlock.Headline(text))
        // A glyph the parser left out of the heading ("△") stays out.
        val norms = located.map { n -> if (text.startsWith(n.norm.take(1))) n.norm else n.norm.trimStart { !it.isLetterOrDigit() } }
        val q = norms.indexOfLast { it.endsWith("?") }
        // Lead-in lines are short and each starts its own phrase; a long one is the question wrapped.
        val leadIn = if (q > 0) (0 until q).all { norms[it].length <= SHORT && norms[it + 1].first().isUpperCase() } else false
        return when {
            q == 0 -> listOf(CardBlock.Headline(norms[0])) + body(located.drop(1))
            q > 0 && leadIn -> norms.take(q).map { CardBlock.Caption(it) } + CardBlock.Headline(norms[q]) + body(located.drop(q + 1))
            q < 0 && norms[0].length <= SHORT && !norms[0].endsWith(":") -> listOf(CardBlock.Headline(norms[0])) + body(located.drop(1))
            else -> listOf(CardBlock.Headline(text))
        }
    }

    /**
     * The card body for [parts]. [screen] is the pane screen the card was read from, when there is
     * one; without it (or when a block isn't found there) each block is laid out from its own text.
     */
    fun format(parts: QuestionParts, screen: String? = null): List<CardBlock> {
        val lines = screen?.takeIf { it.isNotBlank() }?.let(::screenLines).orEmpty()
        class Placed(val start: Int?, val end: Int?, val caption: Boolean, val blocks: List<CardBlock>)
        val placed = parts.blocks.map { b ->
            val range = if (b.text.contains('\n') || lines.isEmpty()) null else locate(b.text, lines)
            val located = range?.let { contentOf(it, lines) }
            val blocks = when (b.kind) {
                QKind.HEADING -> heading(b.text, located)
                QKind.COMMAND -> command(if (b.text.contains('\n')) b.text.lines() else listOf(b.text), located)
                QKind.TEXT -> if (located != null && located.size >= 2) body(located) else listOf(single(b.text))
            }
            Placed(range?.first, range?.last, b.kind == QKind.TEXT && b.text.trim().endsWith(":") && b.text.length <= SHORT, blocks)
        }.toMutableList()
        // A lead-in ("Here is Claude's plan:") goes back above what it introduces on the screen,
        // which the parser placed after the command; the heading stays first.
        for (c in placed.toList()) {
            if (!c.caption || c.end == null) continue
            val at = placed.indexOf(c)
            val next = placed.withIndex().filter { it.index > 0 && it.value.start != null && it.value.start!! > c.end }
                .minByOrNull { it.value.start!! } ?: continue
            if (lines.subList(c.end + 1, next.value.start!!).any { it.norm.any(Char::isLetterOrDigit) }) continue
            if (next.index != at + 1) {
                placed.removeAt(at)
                placed.add(placed.indexOf(next.value), c)
            }
        }
        // An edit's numbered lines read as one diff, even when the parser split them into blocks.
        val out = mutableListOf<CardBlock>()
        for (b in placed.flatMap { it.blocks }) {
            val last = out.lastOrNull()
            if (b is CardBlock.Code && last is CardBlock.Code && !b.wrap && !last.wrap && (b.diff || last.diff) &&
                (b.lines + last.lines).all { DIFF_LINE.containsMatchIn(it.trim()) || NUMBERED_CONTEXT.containsMatchIn(it.trim()) }) {
                out[out.size - 1] = CardBlock.Code(last.lines + b.lines, diff = true)
            } else out += b
        }
        return out
    }

    /** The plain text of a block, for Show raw and tests. */
    fun plain(b: CardBlock): String = when (b) {
        is CardBlock.Headline -> b.text
        is CardBlock.Caption -> b.text
        is CardBlock.Subhead -> b.text
        is CardBlock.Prose -> b.text
        is CardBlock.Labelled -> "${b.label}: ${b.value}"
        is CardBlock.Items -> b.items.joinToString("\n") { "${it.mark} ${it.text}" }
        is CardBlock.Code -> b.lines.joinToString("\n")
    }

    // ---- Options --------------------------------------------------------------------------------

    private val OPTION_ROW = Regex("""^[\s│┃|]*(?:[❯›>▶➤⟩→]\s*)?(?:\[[ xX✓✔]\]\s+)?(?:(\d{1,2})[.)]|(\d{1,2})\s+\([○●◯◉]\))\s+(\S.*?)[\s│┃|]*$""")

    /**
     * The line of text an agent draws under each numbered option ("1. Close it" over "Mark 537
     * resolved now."), keyed by the option's keys. Read from the screen beside the parser, which keeps
     * only the labels; a line already joined into the label (a wrap) is not repeated.
     */
    fun optionNotes(screen: String?, choices: List<Choice>): Map<String, String> {
        if (screen.isNullOrBlank() || choices.isEmpty()) return emptyMap()
        val raw = screen.lines()
        val rows = raw.indices.mapNotNull { i ->
            OPTION_ROW.find(raw[i])?.let { m -> Triple(i, m.groupValues[1].ifEmpty { m.groupValues[2] }, m.groups[3]!!.range.first) }
        }
        // The last run of consecutive numbers, as the parser reads the menu.
        val run = mutableListOf<Triple<Int, String, Int>>()
        for (r in rows) {
            if (run.isNotEmpty() && r.second.toInt() != run.last().second.toInt() + 1) run.clear()
            run += r
        }
        val out = mutableMapOf<String, String>()
        for ((n, r) in run.withIndex()) {
            val choice = choices.firstOrNull { it.keys == r.second } ?: continue
            val stop = run.getOrNull(n + 1)?.first ?: raw.size
            val note = mutableListOf<String>()
            for (j in r.first + 1 until stop) {
                val l = raw[j].trimEnd().trimEnd { it in FRAME }
                val col = l.indexOfFirst { it !in FRAME }
                if (col < 0) break
                if (col < r.third) break
                val t = l.substring(col).trim()
                if (squash(choice.label).contains(squash(t))) continue
                note += t
            }
            if (note.isNotEmpty() && note.any { s -> s.any(Char::isLetter) }) out[choice.keys] = note.joinToString(" ")
        }
        return out
    }

    /** A key hint at the end of a label ("(y)", "(esc)", "(Esc to stop)"), shown quieter. */
    private val KEY_HINT = Regex("""\s\((?:[a-z]|esc|tab|Esc to stop|Tab to edit)\)$""")

    /**
     * The label split for display without changing its text: the main words, then a quieter tail
     * (a key hint, or what follows "  -  " in Hermes's "Approve Once  -  reload now").
     */
    fun labelParts(label: String): Pair<String, String> {
        KEY_HINT.find(label)?.let { return label.substring(0, it.range.first) to label.substring(it.range.first) }
        val dash = label.indexOf("  -  ")
        if (dash > 0) return label.substring(0, dash) to label.substring(dash)
        return label to ""
    }
}
