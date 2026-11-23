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
    !p.unreadQuestion && !p.readingQuestion && !p.claudeBackground &&
        (p.choices.isNotEmpty() || (!p.terminalReview && p.region.orEmpty().any { it.isNotBlank() } && p.region != listOf(UNREAD_SCREEN)))

/**
 * Parsers that prove their own frame before they return options: Hermes's clarify (its "ask N
 * questions" head and exact footer), OpenCode's native question and permission (an exact frame and a
 * styled live read), Muse's quota dialog (a styled read) and Grok's privacy dialog (its settings box).
 */
private fun selfFramed(p: Prompt): Boolean =
    p.hermesClarify || p.openCodeQuestionFrame != null || p.openCodePermission || p.numberKeys || p.museQuota || p == GROK_PRIVACY_PROMPT

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
    val rows = stripAnsi(text).lines().takeLast(80)
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
    val heads = p.choices.map(::headOf)
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
            ?.takeIf { !topEdge(rows[it], width) && !isDialogHeaderRow(rows[e]) }) }
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
            // A confirmed dialog stays up through its own changes: the highlight, ticks, steps.
            if (family == s.family && (read.complete || id == s.confirmedId)) { s.lastId = id; return shown() }
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
    else prompt

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
