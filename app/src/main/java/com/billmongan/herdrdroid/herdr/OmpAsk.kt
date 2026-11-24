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
        val answers: List<String?> = emptyList()) {
        val allAnswered: Boolean get() = total > 0 && answered >= total
    }

    data class Question(val number: Int, val text: String, val answered: Boolean, val options: List<String>,
        val chosen: String?, val expanded: Boolean, val cursor: Int?)

    data class Dialog(val lead: List<String>, val questions: List<Question>, val answered: Int, val total: Int, val onSubmit: Boolean)

    /**
     * The same answers in two reads: each question's choice equal, or the same where the dialog
     * shortened one of them ("Leave as CHECK-F…" for the open question's "Leave as CHECK-FIRST").
     */
    fun sameAnswers(a: List<String?>, b: List<String?>): Boolean = a.size == b.size && a.indices.all { i ->
        val x = a[i]; val y = b[i]
        fun fits(short: String, full: String) = short.endsWith("…") && full.startsWith(short.removeSuffix("…").trimEnd())
        x == y || (x != null && y != null && (fits(x, y) || fits(y, x)))
    }

    /** The card's action that moves the highlight to Submit, checking each step, and presses Enter there. */
    const val SUBMIT = "omp-ask:submit"
    const val SUBMIT_LABEL = "Submit all answers"
    const val CANCEL_LABEL = "Cancel the questions (Esc)"

    private val HEADER = Regex("""^\s*╭─+\s*Ask\s*·\s*(\d+)\s+of\s+(\d+)\s+answered\s*─*╮\s*$""")
    private val QUESTION = Regex("""^(✓\s+)?(\d+)\.\s+(.+)$""")
    private val OPTION = Regex("""^(❯\s+)?([◉○])\s+(.+)$""")
    private val SUBMIT_ROW = Regex("""^(❯\s+)?Submit$""")

    private fun clean(s: String) = s.replace(Regex("""\s{2,}"""), " ").trim()

    fun parse(plain: String): Dialog? {
        val lines = plain.lines()
        val top = lines.indexOfLast { HEADER.matches(it) }
        if (top < 0) return null
        val h = HEADER.matchEntire(lines[top])!!
        val bottom = (top + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("╰") } ?: return null
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
        var options = mutableListOf<String>(); var chosen: String? = null; var cursor: Int? = null
        var radios = false; var folded = false; var open = false
        fun close() {
            if (!open) return
            questions += Question(num, clean(text.toString()), answered, options.map(::clean), chosen?.let(::clean), radios, cursor)
            open = false
        }
        for (row in rows) {
            val t = row.trim()
            if (t.isEmpty()) {
                if (questions.isEmpty() && !open && para.isNotEmpty()) { lead += clean(para.toString()); para = StringBuilder() }
                continue
            }
            val submit = SUBMIT_ROW.matchEntire(t)
            if (submit != null) { close(); submitSeen = true; onSubmit = submit.groupValues[1].isNotEmpty(); continue }
            val q = if (!row.startsWith(" ")) QUESTION.matchEntire(t) else null
            if (q != null && q.groupValues[2].toInt() == (if (open) num else questions.lastOrNull()?.number ?: 0) + 1) {
                close()
                if (para.isNotEmpty()) { lead += clean(para.toString()); para = StringBuilder() }
                num = q.groupValues[2].toInt(); text = StringBuilder(q.groupValues[3]); answered = q.groupValues[1].isNotEmpty()
                options = mutableListOf(); chosen = null; cursor = null; radios = false; folded = false; open = true
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
                if (o.groupValues[2] == "◉") chosen = o.groupValues[3]
                options += o.groupValues[3]
                radios = true
                continue
            }
            val parts = t.split(Regex("""\s{3,}"""))
            if (answered && !radios && options.isEmpty() && (parts.size >= 2 || t.startsWith("✓ ") || t.contains('…'))) {
                // A folded answered question: its shortened options on one line, the chosen one marked.
                parts.forEach { p ->
                    val c = p.removePrefix("✓").trim()
                    if (p.startsWith("✓")) chosen = c
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
        if (questions.isEmpty() || !submitSeen) return null
        if (questions.map { it.number } != (1..questions.size).toList()) return null
        return Dialog(lead, questions, h.groupValues[1].toInt(), h.groupValues[2].toInt(), onSubmit)
    }

    /** The card for the dialog on [plain], or null when there is none (or it isn't all on screen). */
    fun prompt(plain: String): Prompt? {
        val d = parse(plain) ?: return null
        // The open question, when the highlight is on one of its options (only then can its keys be worked out).
        val current = d.questions.firstOrNull { it.expanded && it.cursor != null }
        val state = State(d.questions.map { "${it.number}. ${it.text}" }, d.answered, d.total, d.onSubmit,
            d.questions.map { it.chosen })
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
            if (state.allAnswered) add(Choice(SUBMIT_LABEL, SUBMIT, false))
            add(Choice(CANCEL_LABEL, "esc", false))
        }
        if (current == null || d.onSubmit) {
            val heading = if (state.allAnswered) "Submit all answers?" else "Answer the remaining questions in the terminal, or cancel them."
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
