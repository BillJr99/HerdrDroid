package com.billmongan.herdrdroid.herdr

/**
 * 2.2.7 (Bill: "fix herdrdroid 2.2.7 omp question parsing to not get stuck and to show the whole
 * question"): Oh My Pi's (and pi's) multi-question Ask dialog, a box headed "Ask · N of M answered".
 * Under a lead paragraph each question is numbered. The one being answered is drawn in full, its
 * options as "◉"/"○" rows ending with "Other:  type your own", the highlight "❯" on one of them;
 * an answered one ("✓ N.") is folded to one line of shortened options, its choice marked "✓". A
 * "Submit" row ends the box. On the card: the lead and every question in full as far as the
 * dialog shows it, the open question's options in full, "Other" as a typed answer (the highlight
 * moved there, the text typed, Enter), and, once every question is answered, Submit; Esc cancels.
 */
object OmpAsk {
    /** What identifies the dialog across reads, and where its highlight is. */
    data class State(val questions: List<String>, val answered: Int, val total: Int, val onSubmit: Boolean,
        /** Each question's chosen answer as the dialog shows it (a typed Other with its text); Submit is bound to these. */
        val answers: List<String?> = emptyList(),
        /** Where each chosen answer sits among its question's options (0-based), when the dialog shows it. */
        val answerAt: List<Int?> = emptyList()) {
        val allAnswered: Boolean get() = total > 0 && answered >= total
        /**
         * Codex's review: every answer can be told apart on screen: each question shows a chosen
         * option at a known place, and none is a typed Other (its text can change past the point
         * where the dialog cuts it off, unseen). Only then is Submit offered from the card.
         */
        val verifiable: Boolean get() = answers.size == questions.size && answerAt.size == questions.size &&
            answers.all { it != null && it.removeSuffix("…").isNotBlank() && !isTypedAnswerChoice(it) } && answerAt.all { it != null }
    }

    data class Question(val number: Int, val text: String, val answered: Boolean, val options: List<String>,
        val chosen: String?, val expanded: Boolean, val cursor: Int?, val chosenAt: Int? = null)

    data class Dialog(val lead: List<String>, val questions: List<Question>, val answered: Int, val total: Int, val onSubmit: Boolean)

    /**
     * The same answers in two reads (Grok's review: strictly). Each question's choice must sit at
     * the same place among its options, and read the same: equal, both shortened the same way, or
     * one shortened ("Leave as CHECK-F…") from exactly the other's full text. Two different answers
     * that only share the shortened part's start never match when they sit at different places. A
     * typed Other that differs only after the point where the dialog cuts it can't be seen on the
     * screen; the place check is the most the screen can prove there.
     */
    fun sameAnswers(a: State, b: State): Boolean = a.verifiable && b.verifiable && a.answers.size == b.answers.size &&
        a.answers.indices.all { i ->
            val x = a.answers[i]!!; val y = b.answers[i]!!
            fun fits(short: String, full: String) = short.endsWith("…") && !full.endsWith("…") &&
                short.removeSuffix("…").trimEnd().isNotEmpty() && full.startsWith(short.removeSuffix("…").trimEnd())
            a.answerAt[i] == b.answerAt[i] && (x == y || fits(x, y) || fits(y, x))
        }

    /** The card's action that moves the highlight to Submit, checking each step, and presses Enter there. */
    const val SUBMIT = "omp-ask:submit"
    const val SUBMIT_LABEL = "Submit all answers"
    const val CANCEL_LABEL = "Cancel the questions (Esc)"
    /** A typed answer (or one the app can't place) is never submitted from the card. */
    const val TERMINAL_SUBMIT = "Submit these answers in the terminal: one of them is typed text, or can't be checked in full from here."

    private val HEADER = Regex("""^\s*╭─+\s*Ask\s*·\s*(\d+)\s+of\s+(\d+)\s+answered\s*─*╮\s*$""")
    private val QUESTION = Regex("""^(✓\s+)?(\d+)\.\s+(.+)$""")
    private val OPTION = Regex("""^(❯\s+)?([◉○])\s+(.+)$""")
    private val SUBMIT_ROW = Regex("""^(❯\s+)?Submit$""")

    private fun clean(s: String) = s.replace(Regex("""\s{2,}"""), " ").trim()

    /**
     * The rows Oh My Pi and pi draw under a live dialog, by their exact shapes (Grok's review):
     * the dialog's own "⎋ <what it asks>" row; Oh My Pi's status row (a glyph, then " > " segments
     * with the "📁 <folder>" one, then the "▶───" gauge to the row's end); its "╰─" editor corner;
     * and pi's pair of a folder row directly over its token row ("0.0%/128k …"). Anything else
     * under the box (a lone path, other text with a gauge in it) makes the box history.
     */
    private val DIALOG_ROW = Regex("""^⎋ \S.*$""")
    private val OMP_STATUS = Regex("""^\S{1,2}( \S+)? > .* > 📁 \S+ ▶─[─0-9%K╎┃]*$""")
    private val OMP_CORNER = Regex("""^╰─$""")
    // pi's token row, with its optional ↑/↓/R/CH stats first: "↑2.5M ↓94k R10M CH0.0% 36.4%/128k (auto) …".
    private val PI_TOKENS = Regex("""^(?:(?:↑|↓|R|CH)[0-9.]+[kKMG%]?\s+)*\d+(\.\d+)?%/\d+k\b.*$""")
    private val PI_FOLDER = Regex("""^~?/\S*$""")

    private fun onlyStatusRows(rows: List<String>): Boolean {
        val r = rows.map { it.trim() }.filter { it.isNotEmpty() }
        return r.indices.all { i ->
            val t = r[i]
            DIALOG_ROW.matches(t) || OMP_STATUS.matches(t) || OMP_CORNER.matches(t) || PI_TOKENS.matches(t) ||
                (PI_FOLDER.matches(t) && r.getOrNull(i + 1)?.let(PI_TOKENS::matches) == true)
        }
    }

    fun parse(plain: String): Dialog? {
        val lines = plain.lines()
        val top = lines.indexOfLast { HEADER.matches(it) }
        if (top < 0) return null
        val h = HEADER.matchEntire(lines[top])!!
        val answeredCount = h.groupValues[1].toIntOrNull() ?: return null
        val total = h.groupValues[2].toIntOrNull() ?: return null
        val bottom = (top + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("╰") } ?: return null
        // Grok's review: only a live dialog, the bottom-most thing on screen: under it only blank
        // rows and the agent's own status rows. An old box above newer output is history.
        if (!onlyStatusRows(lines.drop(bottom + 1))) return null
        val rows = lines.subList(top + 1, bottom).map { r ->
            val t = r.trim()
            if (!t.startsWith("│") || !t.endsWith("│") || t.length < 2) return null
            t.removePrefix("│").removeSuffix("│").removePrefix(" ").trimEnd()
        }
        val lead = mutableListOf<String>()
        var para = StringBuilder()
        val questions = mutableListOf<Question>()
        var onSubmit = false
        var submitSeen = false
        // The question being read: its text, and what follows it.
        var num = 0; var text = StringBuilder(); var answered = false
        var options = mutableListOf<String>(); var chosen: String? = null; var cursor: Int? = null; var chosenAt: Int? = null
        var radios = false; var folded = false; var open = false
        fun close() {
            if (!open) return
            val chosenText = chosenAt?.let(options::getOrNull) ?: chosen
            questions += Question(num, clean(text.toString()), answered, options.map(::clean), chosenText?.let(::clean), radios, cursor, chosenAt)
            open = false
        }
        // 2.2.8 (Bill: "make sure all the question text is visible and well formatted especially
        // code"): a fenced block in the lead keeps its rows, indentation and blank rows, joined by
        // newlines, so the card shows it as code instead of one line of words.
        var fence: StringBuilder? = null
        var fenceCol = 0
        // 2.2.8: rows indented four or more columns past the lead's margin right after a blank row
        // (code drawn as Markdown, without fences) as a fenced block of their own.
        var code: StringBuilder? = null
        var codeCol = 0
        var lastBlank = false
        for (row in rows) {
            val t = row.trim()
            val pad = row.takeWhile { it == ' ' }.length
            if (code != null) {
                if (t.isNotEmpty() && pad >= 4) { code.append('\n').append(row.drop(minOf(codeCol, pad)).trimEnd()); continue }
                lead += "```\n$code\n```"; code = null
            }
            if (questions.isEmpty() && !open && fence == null && lastBlank && t.isNotEmpty() && pad >= 4 && para.isEmpty() &&
                lead.isNotEmpty() && !isFenceRow(t)) {
                code = StringBuilder(row.drop(pad).trimEnd()); codeCol = pad; lastBlank = false; continue
            }
            lastBlank = t.isEmpty()
            // An unclosed fence ends at the first question, which is never code.
            if (fence != null && !row.startsWith(" ") && QUESTION.matchEntire(t)?.groupValues?.get(2) == "1") { lead += fence.toString(); fence = null }
            if (questions.isEmpty() && !open && (fence != null || isFenceRow(t))) {
                val f = fence
                if (f == null) {
                    if (para.isNotEmpty()) { lead += clean(para.toString()); para = StringBuilder() }
                    fenceCol = row.indexOfFirst { it != ' ' }
                    fence = StringBuilder(t)
                } else {
                    f.append('\n').append(row.drop(minOf(fenceCol, row.takeWhile { it == ' ' }.length)).trimEnd())
                    if (isFenceRow(t)) { lead += f.toString(); fence = null }
                }
                continue
            }
            if (t.isEmpty()) {
                if (questions.isEmpty() && !open && para.isNotEmpty()) { lead += clean(para.toString()); para = StringBuilder() }
                continue
            }
            val submit = SUBMIT_ROW.matchEntire(t)
            if (submit != null) { close(); submitSeen = true; onSubmit = submit.groupValues[1].isNotEmpty(); continue }
            val q = if (!row.startsWith(" ")) QUESTION.matchEntire(t) else null
            val number = q?.let { it.groupValues[2].toIntOrNull() ?: return null }
            if (q != null && number == (if (open) num else questions.lastOrNull()?.number ?: 0) + 1) {
                close()
                if (para.isNotEmpty()) { lead += clean(para.toString()); para = StringBuilder() }
                num = q.groupValues[2].toInt(); text = StringBuilder(q.groupValues[3]); answered = q.groupValues[1].isNotEmpty()
                options = mutableListOf(); chosen = null; cursor = null; chosenAt = null; radios = false; folded = false; open = true
                continue
            }
            if (!open) {
                if (questions.isNotEmpty() || submitSeen) return null
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
                continue
            }
            val o = OPTION.matchEntire(t)
            if (o != null && !folded) {
                if (o.groupValues[1].isNotEmpty()) cursor = options.size
                if (o.groupValues[2] == "◉") { chosen = o.groupValues[3]; chosenAt = options.size }
                options += o.groupValues[3]
                radios = true
                continue
            }
            val parts = t.split(Regex("""\s{3,}"""))
            if (answered && !radios && options.isEmpty() && (parts.size >= 2 || t.startsWith("✓ ") || t.contains('…'))) {
                // A folded answered question: its shortened options on one line, the chosen one marked.
                parts.forEach { p ->
                    val c = p.removePrefix("✓").trim()
                    if (p.startsWith("✓")) { chosen = c; chosenAt = options.size }
                    options += c
                }
                folded = true
                continue
            }
            // A wrapped line: the question's text, or the last option's.
            if (options.isEmpty()) text.append(' ').append(t)
            else if (radios) options[options.size - 1] = options.last() + " " + t
            else return null
        }
        close()
        fence?.let { lead += it.toString() }
        code?.let { lead += "```\n$it\n```" }
        if (questions.isEmpty() || !submitSeen) return null
        if (questions.map { it.number } != (1..questions.size).toList()) return null
        return Dialog(lead, questions, answeredCount, total, onSubmit)
    }

    /** The card for the dialog on [plain], or null when there is none (or it isn't all on screen). */
    fun prompt(plain: String): Prompt? {
        val d = parse(plain) ?: return null
        // The open question, when the highlight is on one of its options (only then can its keys be worked out).
        val current = d.questions.firstOrNull { it.expanded && it.cursor != null }
        val state = State(d.questions.map { "${it.number}. ${it.text}" }, d.answered, d.total, d.onSubmit,
            d.questions.map { it.chosen }, d.questions.map { it.chosenAt })
        val tabs = d.questions.map { QTab(it.text, it.answered, current == it && !d.onSubmit) } +
            QTab("Submit", false, d.onSubmit, submit = true)
        val others = d.questions.filter { it != current }.map { q ->
            "${if (q.answered) "✓ " else ""}${q.number}. ${q.text}" + when {
                q.answered && q.chosen != null -> ": ${q.chosen}"
                q.expanded -> " (" + q.options.joinToString(" / ") + ")"
                else -> ""
            }
        }
        val extra = buildList {
            if (state.allAnswered && state.verifiable) add(Choice(SUBMIT_LABEL, SUBMIT, false))
            add(Choice(CANCEL_LABEL, "esc", false))
        }
        if (current == null || d.onSubmit) {
            val heading = when {
                state.allAnswered && state.verifiable -> "Submit all answers?"
                state.allAnswered -> TERMINAL_SUBMIT
                else -> "Answer the remaining questions in the terminal, or cancel them."
            }
            return Prompt(heading, extra, parts = QuestionParts(tabs, listOf(QBlock(QKind.HEADING, heading)) +
                d.lead.map { QBlock(QKind.TEXT, it) } + others.map { QBlock(QKind.TEXT, it) }), ompAsk = state)
        }
        val heading = "${current.number}. ${current.text}"
        val choices = current.options.mapIndexed { i, label -> Choice(label, (i + 1).toString(), false) } + extra
        return Prompt(heading, choices, selected = (current.cursor!! + 1).toString(),
            parts = QuestionParts(tabs, listOf(QBlock(QKind.HEADING, heading)) + d.lead.map { QBlock(QKind.TEXT, it) } +
                others.map { QBlock(QKind.TEXT, it) }), ompAsk = state)
    }
}
