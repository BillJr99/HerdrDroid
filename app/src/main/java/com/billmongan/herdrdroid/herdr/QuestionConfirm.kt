package com.billmongan.herdrdroid.herdr

/*
 * When a question card is trusted (2.2.2, Bill: "sometimes it shows the text from the chat as the
 * question rather than the actual question itself and then it updates to be the actual question
 * ... my messages actually show up as answers in the question card ... it'd be nice if it just
 * rendered properly and ... took its time to render").
 *
 * herdr reports a pane as needing you as soon as the agent asks, often before its dialog has
 * finished drawing. A read taken then has the dialog's options (or the user's own numbered
 * message, echoed above the composer) under chat rows, and the parsers took those rows for the
 * question and even for the options. 2.2.0's settle gate shows any read with a question at once
 * ([urgentRead]), so the half-drawn card showed and then corrected itself on a later read.
 *
 * Now a question card's content shows only once it is confirmed:
 * - structure: the question and options come from a complete dialog: its top edge (a rule or a box
 *   corner the dialog draws, a tab bar, a bare frame row, the agent's own status row) or its header
 *   row, its question rows, its options inside
 *   that region, and its own footer or hint row under them. Re-reading the screen from that top edge
 *   down must give the same card, so nothing came from rows above it; and no echoed prompt row
 *   ("❯ my message") may sit between the edge and the options;
 * - data: two reads in a row give the same dialog ([QuestionConfirm]): the same question, option
 *   labels and tab titles, whatever the highlight, the ticks and the notes under options. The
 *   pattern [SettleGate] uses; no timer decides.
 * A dialog with a proven top but no footer (Claude's review step draws none) takes [FALLBACK_READS]
 * reads in a row. A dialog whose top can't be proven (no edge, or a card that changes when read from
 * its edge down) never shows its question: after [UNREAD_AFTER] reads its card says the app couldn't
 * read the question, with Show terminal ([unreadQuestionCard]). The user's own message, echoed at the
 * left edge ("❯ my message") as the first option of a menu with no footer, is never a card.
 * Once a dialog is confirmed, reads of the same dialog (the same tab titles, or the same question and
 * options) show at once: moving the highlight, ticking a box, a step or a pill tap never brings the
 * neutral card back. Complete reads that keep differing (a counter in the dialog) show the newest
 * after [GIVE_UP_READS].
 * Until then the card is [readingQuestionCard]: no options, no keys, only Show terminal. The
 * needs-you state and the alert are not held; the alert says only that a question is being asked
 * ([alertCard]) and is updated in place once the question is confirmed.
 */

/** The card shown while a question is on screen but not yet confirmed: no options and no keys. */
internal fun readingQuestionCard(agent: String): Prompt = Prompt(
    "$agent is asking a question. Reading it…", emptyList(), terminalReview = true, readingQuestion = true,
)

/** The alert's text until the question is confirmed: no question text from the screen. */
internal fun readingAlertText(agent: String): String = "$agent is asking a question. Open HerdrDroid to see it."

/**
 * Whether [p]'s content must be confirmed before it shows: a card with options, or the fallback card
 * that shows part of the screen with keys. Cards that only lead to the terminal (and say so in the
 * app's own words, or show a command for review only) and Claude's sessions-list card are not.
 */
internal fun needsConfirmation(p: Prompt): Boolean =
    !p.unreadQuestion && !p.readingQuestion && !p.claudeBackground && !p.historyReviewOnly &&
        (p.choices.isNotEmpty() || (!p.terminalReview && p.region.orEmpty().any { it.isNotBlank() } && p.region != listOf(UNREAD_SCREEN)))

/**
 * Parsers that prove their own frame before they return options: Hermes's clarify (its "ask N
 * questions" head and exact footer), OpenCode's native question and permission (an exact frame and a
 * styled live read), Oh My Pi's boxed Ask, Muse's quota dialog (a styled read) and Grok's privacy
 * dialog (its settings box).
 */
private fun selfFramed(p: Prompt): Boolean =
    p.ompAsk != null || p.guardedShortcutApproval || p.hermesClarify || p.openCodeQuestionFrame != null || p.openCodePermission || p.numberKeys || p.museQuota || p == GROK_PRIVACY_PROMPT

/**
 * One read's card: [topped] when its top is proven (an edge from which the same card reads, with no
 * echoed prompt under it), [complete] when its footer shows too, [refused] when the read shows the
 * chat, not a dialog (see the notes above).
 */
internal data class QuestionRead(val prompt: Prompt, val topped: Boolean, val complete: Boolean, val refused: Boolean = false)

/**
 * [p], read from [text], with what its structure proves (see the notes above). [framed]: a card a
 * parser already proved from a verified live read (pi's question).
 */
internal fun questionRead(text: String, p: Prompt, agent: String?, version: String?, blocked: Boolean, framed: Boolean = false): QuestionRead {
    if (!needsConfirmation(p) || selfFramed(p) || framed) return QuestionRead(p, topped = true, complete = true)
    val plain = stripAnsi(text)
    if (p.claudeAsk && claudeGutteredTabAsk(plain) == p)
        return QuestionRead(p, topped = true, complete = true)
    val rows = claudeDialogWindow(plain.lines(), agent)
    return if (p.choices.isEmpty()) regionRead(rows, p) else dialogRead(rows, p, agent, version, blocked)
}

private fun letters(s: String) = s.filter(Char::isLetterOrDigit).lowercase()
private val CHECKBOX_PREFIX = Regex("""^\[[ xX✓✔]\]\s+|^[☐☒✔✓☑◉○●◯]\s+""")
/** A choice's label as it shows on its row: letters and digits only, the start of it. */
private fun headOf(c: Choice) = letters(c.label.replace(CHECKBOX_PREFIX, "")).take(12)
private fun indentOf(row: String) = row.length - row.trimStart().length

/**
 * Where a dialog can start: a rule or a box's top corner drawn at the left of the screen (a dialog's
 * own frame, not a table in the chat, which has "┬" joints and sits indented in Claude's chat), a
 * rule across most of a frame, a bare frame row, a tab bar, the agent's own status row, or a short
 * header row ("Do you want to proceed?").
 */
private fun topEdge(row: String, width: Int): Boolean {
    val t = row.trim()
    if (t.isEmpty()) return false
    val indent = indentOf(row)
    // A rule, alone or inside a frame ("│ ──────── │"), wide enough to be the dialog's own.
    val inner = t.trim('│', '┃', '║', ' ')
    if (inner.length >= 3 && inner.all { it in "─━═╌" }) return indent <= 2 && t.length * 10 >= width * 6
    if (t.first() in "╭┌┏╔") return indent <= 2 && t.none { it in "┬╦┳┼" } && t.length * 10 >= width * 6
    if (t.all { it in "│┃║ " }) return true
    if (t.first() in "│┃║" && t.drop(1).trimStart().firstOrNull()?.let { it in "╭┌" } == true) return t.none { it in "┬╦┳" }
    if (parseTabBar(t.trim('│', '┃', '|', ' ')) != null || isAgentStatusRow(row)) return true
    return isDialogHeaderRow(row) && indent <= 2 && t.length <= 90
}

/**
 * The user's own message echoed above the composer at the screen's left edge ("❯ text"), not an
 * option row. Only Claude's "❯" and Codex's "›" at column 0: a dialog's body is indented or framed,
 * and its "> " lines (a redirection, a quote) are not echoes.
 */
private val ECHO_ROW = Regex("""^[❯›]\s+(?!\d{1,2}[.)\s])\S""")
/** The same, numbered: the user's own numbered message echoed at the left edge ("❯ 1. text"). */
private val NUMBERED_ECHO_ROW = Regex("""^[❯›]\s+\d{1,2}[.)]\s""")

/** How many rows with text may sit between a dialog's last option and its footer. */
private const val FOOTER_WITHIN = 8
/** How far above its first option a dialog's top edge may be, and how many edges are tried. */
private const val TOP_WITHIN = 60
private const val EDGES_TRIED = 24

/** Reads in a row of the same dialog that confirm it when its top is proven but it draws no footer. */
internal const val FALLBACK_READS = 4
/** Complete reads that keep differing (a counter in the dialog) before the newest one shows. */
internal const val GIVE_UP_READS = 6
/** Reads that never prove a dialog before its card says the app couldn't read the question. */
internal const val UNREAD_AFTER = 8

private fun widthOf(rows: List<String>) = rows.maxOfOrNull { it.trimEnd().length } ?: 0

/**
 * What [p]'s dialog proves on this read: its top (the nearest edge above the options, or the title
 * row just over it, as Kiro draws its tool, from which the same card reads, with no echoed prompt
 * between it and the options) and its footer (the dialog's own hint row under its options).
 */
internal fun dialogRead(rows: List<String>, p: Prompt, agent: String?, version: String?, blocked: Boolean): QuestionRead {
    val unproven = QuestionRead(p, topped = false, complete = false)
    // 2.2.8: the card's own "Type to deny and steer" is not a row of the dialog; its hint row is.
    val heads = p.choices.filter { it.keys != OMP_STEER }.map(::headOf)
    if (heads.any { it.isEmpty() }) return unproven
    // Each option's row, from the last one up, each at or above the one after it.
    var at = rows.indexOfLast { letters(it).contains(heads.last()) && !isDialogFooterRow(it) }
    if (at < 0) return unproven
    val last = at
    for (h in heads.dropLast(1).asReversed()) {
        val from = if (p.horizontal) at else at - 1
        at = (from downTo maxOf(0, at - 12)).firstOrNull { letters(rows[it]).contains(h) } ?: return unproven
    }
    val first = at
    val footer = (last + (if (p.horizontal) 0 else 1)..rows.lastIndex).filter { rows[it].isNotBlank() }
        .take(FOOTER_WITHIN).firstOrNull { isDialogFooterRow(rows[it]) }
    // The user's echoed message (numbered or not) as a menu with nothing of a dialog above or under it: the chat, never a card.
    val echoed = footer == null && (ECHO_ROW.containsMatchIn(rows[first]) || NUMBERED_ECHO_ROW.containsMatchIn(rows[first]))
    val width = widthOf(rows)
    val edges = (first - 1 downTo maxOf(0, first - TOP_WITHIN)).filter { topEdge(rows[it], width) }.take(EDGES_TRIED)
        // The title row over an edge (Kiro's tool), but not over a header row, which may be the chat's own prose.
        .flatMap { e -> listOfNotNull(e, (e - 1 downTo maxOf(0, e - 2)).firstOrNull { rows[it].isNotBlank() }
            ?.takeIf { !topEdge(rows[it], width) && !isDialogHeaderRow(rows[e]) }) } +
        // 2.2.4: a Claude dialog taller than the screen, read from its frame: its top is the screen's top row.
        listOfNotNull(0.takeIf { p.topOffScreen && first > 0 }) +
        // 2.2.7: the whole of such a dialog, read back from the pane's history: its top rule is the first row.
        listOfNotNull(0.takeIf { !p.topOffScreen && first > TOP_WITHIN && isRebuiltDialog(rows) && claudeFramedDialog(rows, first)?.top == 1 })
    for (top in edges) {
        if ((top + 1 until first).any { ECHO_ROW.containsMatchIn(rows[it]) || NUMBERED_ECHO_ROW.containsMatchIn(rows[it]) }) break
        // A header row that only starts like one ("Do you want to …") opens a dialog only with the dialog
        // right under it: a blank row between it and the options is the chat's paragraph break. An
        // agent's exact heading (Codex's, with blank rows in its dialog) is the dialog's own.
        if (isDialogHeaderRow(rows[top]) && !isNamedDialogHeading(rows[top]) && parseTabBar(rows[top].trim()) == null &&
            (top + 1 until first).any { rows[it].isBlank() }) continue
        if (detectPrompt(rows.drop(top).joinToString("\n"), blocked, agent, version)?.forCard() == p)
            return QuestionRead(p, topped = true, complete = footer != null)
    }
    return unproven.copy(refused = echoed)
}

/**
 * The screen-and-keys card's structure (2.2.2): its rows are the bottom of the screen, so the
 * dialog they show must have a proven top (an edge with no echoed prompt between it and the
 * bottom) and, to be complete, its footer near the bottom.
 */
private fun regionRead(rows: List<String>, p: Prompt): QuestionRead {
    val text = rows.withIndex().filter { it.value.isNotBlank() }
    val footer = text.takeLast(FOOTER_WITHIN).lastOrNull { isDialogFooterRow(it.value) }?.index
    val bottom = footer ?: text.lastOrNull()?.index ?: return QuestionRead(p, topped = false, complete = false)
    val width = widthOf(rows)
    val top = (bottom - 1 downTo maxOf(0, bottom - 40)).firstOrNull { topEdge(rows[it], width) }
        ?: return QuestionRead(p, topped = false, complete = false)
    if ((top + 1 until bottom).any { ECHO_ROW.containsMatchIn(rows[it]) || NUMBERED_ECHO_ROW.containsMatchIn(rows[it]) }) return QuestionRead(p, topped = false, complete = false)
    return QuestionRead(p, topped = true, complete = footer != null)
}

/** A dialog's identity: its question, its options' labels (ticks aside) and its tab titles; the highlight is not part of it. */
private fun identityOf(p: Prompt): Any = Triple(
    p.question.trim(),
    p.choices.map { it.keys to it.label.replace(CHECKBOX_PREFIX, "").trim() } + listOfNotNull(p.claudeCheckboxAction?.label),
    p.shownParts().tabs.map { it.title } to p.region?.map { r -> r.filterNot { it in MOVING_MARKS }.replace(Regex("""\d+"""), "#").replace(Regex("""\s+"""), " ").trim() },
)

/** The dialog a card belongs to: a multi-question dialog by its tab titles (its steps change the question), else its identity. */
private fun familyOf(p: Prompt): Any = p.shownParts().tabs.map { it.title }.takeIf { it.size >= 2 }?.let { "tabs" to it } ?: identityOf(p)

/** Marks that move while you choose (the highlight, a filled radio, the current tab), left out of the comparison. */
private val MOVING_MARKS = "❯›>▶➤⟩→●◉○◯▸$TAB_CURRENT_MARK"

/**
 * What a pane's card shows after a read (2.2.2): [CARD] the read's own card, [READING] the neutral
 * card, [UNREAD] the card that says the question couldn't be read, [NONE] no card.
 */
internal enum class CardShows { CARD, READING, UNREAD, NONE }
internal data class Verdict(val shows: CardShows, val gaveUp: Boolean = false)

/**
 * Confirms question cards from consecutive reads, per pane (2.2.2); see the notes at the top of
 * this file. Memory only.
 */
internal class QuestionConfirm {
    private class State {
        var lastId: Any? = null
        var run = 0
        var family: Any? = null
        var confirmedId: Any? = null
        var differing = 0
        var waiting = 0
    }
    private val states = HashMap<String, State>()
    private val seen = HashSet<String>()

    /** Whether any read of [key] has been offered. */
    fun seen(key: String): Boolean = key in seen

    fun decide(key: String, read: QuestionRead?): Verdict {
        seen += key
        if (read == null) { forget(key); return Verdict(CardShows.NONE) }
        val p = read.prompt
        if (!needsConfirmation(p)) { forget(key); return Verdict(CardShows.CARD) }
        val s = states.getOrPut(key) { State() }
        if (!read.refused && read.topped) {
            val id = identityOf(p)
            val family = familyOf(p)
            fun shown(gaveUp: Boolean = false): Verdict {
                s.family = family; s.confirmedId = id; s.differing = 0; s.waiting = 0
                return Verdict(CardShows.CARD, gaveUp)
            }
            // A confirmed dialog stays up through its own changes: the highlight, ticks, steps, and a
            // step with no footer of its own (Claude's review step) whose top is proven.
            if (family == s.family) { s.lastId = id; return shown() }
            s.run = if (s.lastId == id) s.run + 1 else 1
            s.lastId = id
            if (s.run >= (if (read.complete) 2 else FALLBACK_READS)) return shown()
            if (read.complete && ++s.differing >= GIVE_UP_READS) return shown(gaveUp = true)
            if (!read.complete) s.differing = 0
        } else {
            s.lastId = null; s.run = 0; s.differing = 0
        }
        s.family = null; s.confirmedId = null
        return Verdict(if (++s.waiting >= UNREAD_AFTER) CardShows.UNREAD else CardShows.READING)
    }

    /** True when [read]'s card may show: confirmed now, or a card that needs no confirming. */
    fun offer(key: String, read: QuestionRead?): Boolean = decide(key, read).shows == CardShows.CARD

    fun forget(key: String) { states.remove(key) }
}

/** The last read's verdict for a pane, with the card it was made for. */
internal data class CardDecision(val parsed: Prompt?, val verdict: Verdict)

/**
 * The card the app shows for [parsed] (2.2.2): [parsed] when it needs no confirming or the last
 * read's verdict for exactly this card says it shows; the neutral or the unread card otherwise.
 */
internal fun shownCard(parsed: Prompt?, decision: CardDecision?, agent: String): Prompt? {
    if (parsed == null || !needsConfirmation(parsed)) return parsed
    val d = decision?.takeIf { it.parsed == parsed } ?: return readingQuestionCard(agent)
    return when (d.verdict.shows) {
        CardShows.CARD -> parsed
        CardShows.UNREAD -> unreadQuestionCard(agent)
        else -> readingQuestionCard(agent)
    }
}

/** The card a needs-you alert is made from (2.2.2): [prompt] once confirmed, else neutral words with no options. */
internal fun alertCard(prompt: Prompt?, confirmed: Boolean, agent: String): Prompt? =
    if (prompt != null && needsConfirmation(prompt) && !confirmed)
        Prompt(readingAlertText(agent), emptyList(), terminalReview = true, readingQuestion = true)
    else if (prompt?.historyReviewOnly == true) prompt.forCard() else prompt

/** How many more reads an alert may take to confirm its question, and the pause between them. */
internal const val ALERT_CONFIRM_READS = 10
internal const val ALERT_CONFIRM_PAUSE_MS = 700L

/**
 * Confirms an alert's question from [first], its first read, and up to [maxReads] more from [read]:
 * null stops (the pane stopped waiting); a blank read or one with no card is skipped. The confirmed
 * card and its screen, or null.
 */
internal suspend fun confirmByReads(first: String, firstPrompt: Prompt, agent: String?, version: String?,
                                    maxReads: Int = ALERT_CONFIRM_READS, read: suspend () -> String?): Pair<Prompt, String>? {
    val gate = QuestionConfirm()
    var text = first
    var p: Prompt? = firstPrompt
    var n = 0
    while (true) {
        val verdict = gate.decide("alert", p?.let { questionRead(text, it, agent, version, true) })
        if (verdict.shows == CardShows.CARD && p != null) return p to text
        if (verdict.shows == CardShows.UNREAD || n++ >= maxReads) return null
        text = read() ?: return null
        p = if (text.isBlank()) null else detectPrompt(text, true, agent, version)
    }
}

private val CLAUDE_SINGLE_TAB = Regex("""^☐\s+\S.{0,24}$""")
private val CLAUDE_PREVIEW_OPTION = Regex("""^\s*[❯›]?\s*\d{1,2}[.)]\s+(\S.*)$""")

internal fun claudeAskFooter(row: String): Boolean = row.trim().let {
    it.startsWith("Enter to select · ") && it.endsWith(" · Esc to cancel") && "to navigate" in it
}

internal fun claudeAskTab(row: String): Boolean = parseTabBar(row) != null || CLAUDE_SINGLE_TAB.matches(row.trim())

private val CLAUDE_TABBED_CHOICE = Regex("""^(❯ |› |  )(\d{1,2})\. (\S.*)$""")
private val CLAUDE_TABBED_CHECKBOX = Regex("""^\s*[❯›]?\s*\d{1,2}\. \[[ xX✓✔]]""")

/** The native tabbed Ask layout with a guttered question and the separate Chat action. */
internal fun claudeGutteredTabAsk(text: String): Prompt? {
    val window = claudeDialogWindow(withoutClaudeChromeUnderDialog(text).lines(), "claude")
        .dropLastWhile(String::isBlank)
    val footer = window.lastIndex
    if (footer < 0 || !claudeAskFooter(window[footer])) return null
    val bar = (footer - 1 downTo 0).firstOrNull { parseTabBar(window[it]) != null } ?: return null
    val (tabs, inlineQuestion) = checkNotNull(parseTabBar(window[bar]))
    if (inlineQuestion.isNotEmpty() || tabs.count { it.submit } != 1 || !tabs.last().submit ||
        !window[bar].trim().startsWith("←") || !window[bar].trim().endsWith("→")) return null
    val rows = withoutClaudeAskPreview(window)
    val body = rows.subList(bar + 1, footer).withIndex().filter { it.value.isNotBlank() }
    val question = body.takeWhile { it.value.startsWith("│ ") }
    if (question.isEmpty()) return null
    val options = body.drop(question.size)
    val rule = options.indexOfLast { CLAUDE_DIALOG_TOP_RULE.matches(it.value) }
    // Other native forms, especially the checkbox dialog and its unnumbered Next button,
    // still use the existing parser.
    if (rule < 0 || rule != options.lastIndex - 1 ||
        options.take(rule).any { CLAUDE_TABBED_CHECKBOX.containsMatchIn(it.value) ||
            CLAUDE_CLIPPED_ACTION.matches(it.value) }) return null

    val lines = question.map { QLine(it.value.removePrefix("│ ").trim()) }
    val title = lines.joinToString(" ") { it.text }.trim()
    val parts = questionParts(lines).copy(tabs = tabs)
    val region = window.subList(bar, footer + 1)
    fun review() = Prompt(title.ifBlank { "Claude question" }, emptyList(), region = region,
        terminalReview = true, parts = parts, claudeAsk = true)

    val menu = options.filterIndexed { i, row -> i != rule && !CLAUDE_SINGLE_DESCRIPTION.matches(row.value) }
    if (menu.size !in 4..6 || title.isBlank() || tabs.none { it.current && !it.submit }) return review()
    val matches = menu.map { CLAUDE_TABBED_CHOICE.matchEntire(it.value) ?: return review() }
    val numbers = matches.map { it.groupValues[2].toInt() }
    val labels = matches.map { it.groupValues[3].trimEnd() }
    if (numbers != (1..menu.size).toList() || labels.takeLast(2) != listOf("Type something.", "Chat about this") ||
        options.getOrNull(rule - 1) != menu[menu.lastIndex - 1]) return review()
    val focus = matches.filter { it.groupValues[1] != "  " }.singleOrNull() ?: return review()
    return Prompt(title, labels.zip(numbers).map { (label, key) -> Choice(label, key.toString(), false) },
        selected = focus.groupValues[2], parts = parts, claudeAsk = true).also {
        it.dialogRows = DialogRows(region.map(::cardRegionRow), menu.map { row -> row.index + 1 })
    }
}

private val CLAUDE_PREVIEW_TOP = Regex("""┌─+┐\s*""")

/** Remove a complete right-hand preview only in a live Claude AskUserQuestion frame. */
internal fun withoutClaudeAskPreview(rows: List<String>): List<String> {
    val footer = rows.indexOfLast { it.isNotBlank() }
    if (footer < 0 || !claudeAskFooter(rows[footer])) return rows
    val tab = (footer - 1 downTo 0).firstOrNull { claudeAskTab(rows[it]) } ?: return rows
    val start = (tab + 1 until footer).firstOrNull { i ->
        val row = rows[i]
        val col = row.indexOf('┌')
        col > 0 && CLAUDE_PREVIEW_OPTION.matches(row.take(col)) && CLAUDE_PREVIEW_TOP.matches(row.substring(col))
    } ?: return rows
    val col = rows[start].indexOf('┌')
    val end = (start + 1 until footer).firstOrNull { rows[it].getOrNull(col) != '│' } ?: return rows
    if (end == start + 1 || rows[end].getOrNull(col) != '└') return rows
    return rows.mapIndexed { i, row -> if (i in start..end) row.take(col).trimEnd() else row }
}

private val CLAUDE_CLIPPED_OPTION = Regex("""^\s*([❯›]?)\s*(\d{1,2})[.)]\s+\S.*$""")

private val CLAUDE_CLIPPED_CHECKBOX = Regex("""^\s*([❯›]?)\s*(\d{1,2})\. \[[ xX✓✔]\] \S.*$""")
private val CLAUDE_CLIPPED_CHAT = Regex("""^\s*([❯›]?)\s*(\d{1,2})\. Chat about this$""")
private val CLAUDE_CLIPPED_ACTION = Regex("""^\s*([❯›]?)\s*(?:Next|Submit)$""")

private val CLAUDE_SINGLE_CLIP_COUNT = Regex("""^↑ [1-9][0-9]* more lines?$""")
private val CLAUDE_CLIPPED_TYPE = Regex("""^  (\d{1,2})\. Type something\.$""")
private val CLAUDE_CLIPPED_CHAT_PLAIN = Regex("""^  (\d{1,2})\. Chat about this$""")
private val CLAUDE_SINGLE_DESCRIPTION = Regex("""^ {5}\S.*$""")

/** A clipped native single-select viewport can start inside its question or an option description. */
private fun claudeClippedSingleRows(frame: List<String>): List<String>? {
    val rows = frame.filter { it.isNotBlank() }.map { it.trimEnd() }
    if (rows.size < 5 || !CLAUDE_SINGLE_CLIP_COUNT.matches(rows.last().trim())) return null
    val chat = CLAUDE_CLIPPED_CHAT_PLAIN.matchEntire(rows[rows.lastIndex - 1]) ?: return null
    if (!CLAUDE_DIALOG_TOP_RULE.matches(rows[rows.lastIndex - 2])) return null
    val typed = CLAUDE_CLIPPED_TYPE.matchEntire(rows[rows.lastIndex - 3]) ?: return null
    val typeNumber = typed.groupValues[1].toInt()
    if (typeNumber !in 3..5 || chat.groupValues[1].toInt() != typeNumber + 1) return null
    val body = rows.dropLast(4)
    val firstOption = body.indexOfFirst {
        !CLAUDE_SINGLE_DESCRIPTION.matches(it) && CLAUDE_CLIPPED_OPTION.matches(it)
    }
    // If all real options have scrolled off, the native footer still identifies the dialog.
    // Keep numbered descriptions readable without handing them to the generic option parser.
    if (firstOption < 0 && body.all(CLAUDE_SINGLE_DESCRIPTION::matches)) {
        return body + rows[rows.lastIndex - 3] + rows[rows.lastIndex - 1]
    }
    if (firstOption <= 0) return null
    val leadingRows = body.take(firstOption)
    val options = body.drop(firstOption).mapNotNull { row ->
        if (CLAUDE_SINGLE_DESCRIPTION.matches(row)) null
        else CLAUDE_CLIPPED_OPTION.matchEntire(row) ?: return null
    }
    val numbers = options.map { it.groupValues[2].toInt() }
    val firstNumber = numbers.firstOrNull() ?: return null
    if (numbers != (firstNumber until typeNumber).toList()) return null
    if (firstNumber == 1) {
        // A wrapped question starts above the viewport, in Claude's question gutter.
        if (leadingRows.any { !it.startsWith("│ ") } || options.count { it.groupValues[1].isNotEmpty() } != 1) return null
    } else if (firstNumber < 2 || leadingRows.any { !CLAUDE_SINGLE_DESCRIPTION.matches(it) }) return null
    val visibleQuestion = leadingRows.map { it.removePrefix("│ ") }
    val visibleOptions = body.drop(firstOption).map { row ->
        if (!CLAUDE_SINGLE_DESCRIPTION.matches(row) && CLAUDE_CLIPPED_OPTION.matches(row)) {
            row.trimStart().trimStart('❯', '›', ' ')
        } else row
    }
    return visibleQuestion + visibleOptions + rows[rows.lastIndex - 3] + rows[rows.lastIndex - 1]
}

/** Checkbox dialogs end with their own Next or Submit, rule, and separately numbered chat action. */
internal fun claudeClippedCheckboxRows(frame: List<String>): List<String>? {
    val rows = frame.filter { it.isNotBlank() }.map { it.trimEnd() }
    if (rows.size < 6 || !claudeAskFooter(rows.last())) return null
    if (rows.dropLast(1).any { claudeAskTab(it) || claudeAskFooter(it) }) return null
    val chat = CLAUDE_CLIPPED_CHAT.matchEntire(rows[rows.lastIndex - 1]) ?: return null
    if (!CLAUDE_DIALOG_TOP_RULE.matches(rows[rows.lastIndex - 2])) return null
    val action = CLAUDE_CLIPPED_ACTION.matchEntire(rows[rows.lastIndex - 3]) ?: return null
    val body = rows.dropLast(4)
    val first = body.indexOfFirst { CLAUDE_CLIPPED_CHECKBOX.matches(it) }
    if (first < 0 || body.take(first).any { it.isNotBlank() && !it.startsWith("│ ") }) return null
    val options = body.drop(first).mapNotNull(CLAUDE_CLIPPED_CHECKBOX::matchEntire)
    val focused = (options + action + chat).count { it.groupValues[1].isNotEmpty() }
    if (options.size < 2 || focused != 1) return null
    val firstNumber = options.first().groupValues[2].toInt()
    val nextNumber = firstNumber + options.size
    val typed = CLAUDE_CLIPPED_TYPE.matchEntire(body.last())
    if (typed != null && typed.groupValues[1].toInt() != nextNumber) return null
    if (firstNumber < 1 || options.map { it.groupValues[2].toInt() } != (firstNumber until nextNumber).toList() ||
        chat.groupValues[2].toInt() != nextNumber + if (typed == null) 0 else 1) return null
    return body.drop(first)
}

/** Prove a focused native Next or Submit in a complete Ask whose generic parse has no selection. */
internal fun claudeAskHasFocusedAction(rows: List<String>): Boolean {
    val visible = rows.filter { it.isNotBlank() }
    if (visible.size < 4) return false
    val action = CLAUDE_CLIPPED_ACTION.matchEntire(visible[visible.lastIndex - 3]) ?: return false
    if (action.groupValues[1].isEmpty()) return false
    val tab = visible.indexOfLast(::claudeAskTab)
    return tab >= 0 && claudeClippedCheckboxRows(visible.drop(tab + 1)) != null
}

/** A viewport can start partway through the option list, after its question and tab have scrolled off. */
internal fun claudeClippedAskReview(text: String): Prompt? {
    val rows = withoutClaudeChromeUnderDialog(stripAnsi(text)).lines()
    val footer = rows.indexOfLast { it.isNotBlank() }
    if (footer < 0 || !claudeAskFooter(rows[footer])) return null
    val above = rows.take(footer).dropWhile { it.isBlank() }
    val body = claudeClippedCheckboxRows(above + rows[footer]) ?: claudeClippedSingleRows(above) ?: run {
        val first = CLAUDE_CLIPPED_OPTION.matchEntire(above.firstOrNull() ?: return null) ?: return null
        // An ordinary numbered message starts at one. A real tab or an older Ask footer
        // proves this is a mixed viewport, so leave it to the regular dialog parser.
        val firstNumber = first.groupValues[2].toInt()
        if (firstNumber < 2 || above.any { claudeAskTab(it) || claudeAskFooter(it) }) return null
        val options = above.mapNotNull(CLAUDE_CLIPPED_OPTION::matchEntire)
        if (options.size < 2 || options.count { it.groupValues[1].isNotEmpty() } != 1) return null
        if (options.map { it.groupValues[2].toInt() } != (firstNumber until firstNumber + options.size).toList()) return null
        above
    }
    val heading = "Claude question (top off screen)"
    val parts = questionParts(listOf(QLine(heading)))
    val details = body.filter { it.isNotBlank() }.map { QBlock(QKind.TEXT, it.trim()) }
    return Prompt(heading, emptyList(), region = body + rows[footer],
        terminalReview = true, topOffScreen = true, parts = parts.copy(blocks = details))
}

/** Keep an identifiable Claude dialog readable without making unconfirmed choices actionable. */
internal fun claudeReviewPreview(text: String, parsed: Prompt?, maxRows: Int = 80): Prompt? {
    val rows = withoutClaudeChromeUnderDialog(stripAnsi(text)).lines().takeLast(maxRows)
    val footer = rows.indexOfLast { it.isNotBlank() }
    if (footer < 0) return null
    val ask = claudeAskFooter(rows[footer])
    val top = if (ask) {
        (footer - 1 downTo 0).firstOrNull { claudeAskTab(rows[it]) }
    } else if (claudePermissionFooterAtBottom(text)) {
        (footer - 1 downTo 0).firstOrNull { isDialogHeaderRow(rows[it]) }
    } else null
    if (top == null) return if (ask) claudeClippedAskReview(text) else null
    val body = rows.subList(if (ask) top + 1 else top, footer)
    val visible = if (ask) withoutClaudeAskPreview(rows).subList(top + 1, footer) else body
    val labels = visible.mapNotNull { row ->
        CLAUDE_PREVIEW_OPTION.matchEntire(row)?.groupValues?.get(1)?.trim()
    }
    val heading = visible.firstOrNull { it.isNotBlank() }?.trim() ?: return null
    val shownLabels = (parsed?.choices?.map { it.label }.orEmpty() + labels).distinct()
    val parts = parsed?.shownParts() ?: questionParts(listOf(QLine(heading)))
    val knownDetails = parts.blocks.map { it.text }.toSet()
    val blocks = parts.blocks + visible.asSequence()
        .map { row -> CLAUDE_PREVIEW_OPTION.matchEntire(row)?.groupValues?.get(1)?.trim() ?: row.trim() }
        .filter { it.isNotBlank() && it !in knownDetails && it !in shownLabels }
        .map { QBlock(QKind.TEXT, it) }.toList() +
        shownLabels.filterNot(knownDetails::contains).map { QBlock(QKind.TEXT, it) }
    return Prompt(parsed?.question ?: heading, emptyList(), region = body,
        terminalReview = true, parts = parts.copy(blocks = blocks))
}
