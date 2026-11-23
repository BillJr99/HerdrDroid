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
 * - structure: the question and options come from a complete dialog: its top edge (a rule, a box
 *   corner, a tab bar, a bare frame row) or its header row, its question rows, its options inside
 *   that region, and its own footer or hint row under them. Re-reading the screen from that top edge
 *   down must give the same card, so nothing came from rows above it; and no echoed prompt row
 *   ("❯ my message") may sit between the edge and the options;
 * - data: two reads in a row give the same card from the same dialog rows ([QuestionConfirm]),
 *   the pattern [SettleGate] uses. No timer decides.
 * A dialog whose structure can't be shown complete (one an agent draws with no footer, such as
 * Claude's review step, or with no edge above its question) is confirmed by [FALLBACK_READS] reads
 * in a row instead: a half-drawn screen lasts a read or two, so it never gets that far. One sign of
 * the chat is never confirmed: the user's own message, echoed at the left edge ("❯ my message"), as
 * the first option of a menu with no footer under it.
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
 * One read's card: [region], the dialog's rows when its structure is complete (null otherwise), and
 * [refused] when the read shows the chat, not a dialog (see the notes above).
 */
internal data class QuestionRead(val prompt: Prompt, val region: List<String>?, val refused: Boolean = false) {
    val complete: Boolean get() = region != null
}

/** [p], read from [text], with the rows of its dialog when the dialog is complete (see the notes above). */
internal fun questionRead(text: String, p: Prompt, agent: String?, version: String?, blocked: Boolean): QuestionRead = when {
    !needsConfirmation(p) || selfFramed(p) -> QuestionRead(p, emptyList())
    p.choices.isEmpty() -> QuestionRead(p, p.region)
    else -> dialogRead(stripAnsi(text).lines().takeLast(80), p, agent, version, blocked)
}

private fun letters(s: String) = s.filter(Char::isLetterOrDigit).lowercase()
private val CHECKBOX_PREFIX = Regex("""^\[[ xX✓✔]\]\s+""")
/** A choice's label as it shows on its row: letters and digits only, the start of it. */
private fun headOf(c: Choice) = letters(c.label.replace(CHECKBOX_PREFIX, "")).take(12)

/** A rule, a box's top corner, a bare frame row, a tab bar or a header row: where a dialog can start. */
private fun topEdge(row: String): Boolean {
    val t = row.trim()
    if (t.isEmpty()) return false
    // A rule inside a frame ("│ ──────── │").
    val inner = t.trim('│', '┃', '║', ' ')
    if (inner.length >= 3 && inner.all { it in "─━═╌" }) return true
    return t.startsWith("───") || t.startsWith("━━━") || t.startsWith("═══") || t.startsWith("╌╌╌") ||
        Regex("""^[-_]{3,}$""").matches(t) || t.first() in "╭┌┏╔" || t.all { it in "│┃║ " } ||
        (t.first() in "│┃║" && t.drop(1).trimStart().firstOrNull()?.let { it in "╭┌" } == true) ||
        parseTabBar(t.trim('│', '┃', '|', ' ')) != null || isDialogHeaderRow(row)
}

/** The user's own message echoed above the composer, or the composer itself ("❯ text"), not an option row. */
private val ECHO_ROW = Regex("""^[❯›>]\s+(?!\d{1,2}[.)\s])\S""")

/** How many rows with text may sit between a dialog's last option and its footer. */
private const val FOOTER_WITHIN = 8
/** How far above its first option a dialog's top edge may be, and how many edges are tried. */
private const val TOP_WITHIN = 60
private const val EDGES_TRIED = 24

/** Consecutive identical reads that confirm a card whose dialog structure couldn't be shown complete. */
internal const val FALLBACK_READS = 4

/**
 * The rows of [p]'s dialog, its top edge down to its footer, or null when the dialog is not
 * complete on this read: an option row or the footer not drawn yet, no edge or header above the
 * question, a card that changes when read from the edge down (part of it came from above), or the
 * user's echoed message inside the question.
 */
internal fun dialogRead(rows: List<String>, p: Prompt, agent: String?, version: String?, blocked: Boolean): QuestionRead {
    val incomplete = QuestionRead(p, null)
    val heads = p.choices.map(::headOf)
    if (heads.any { it.isEmpty() }) return incomplete
    // Each option's row, from the last one up, each at or above the one after it.
    var at = rows.indexOfLast { letters(it).contains(heads.last()) }
    if (at < 0) return incomplete
    val last = at
    for (h in heads.dropLast(1).asReversed()) {
        val from = if (p.horizontal) at else at - 1
        at = (from downTo maxOf(0, at - 12)).firstOrNull { letters(rows[it]).contains(h) } ?: return incomplete
    }
    val first = at
    // The dialog's own footer or hint row under its options.
    val footer = (last + (if (p.horizontal) 0 else 1)..rows.lastIndex).filter { rows[it].isNotBlank() }
        .take(FOOTER_WITHIN).firstOrNull { isDialogFooterRow(rows[it]) }
        // The user's echoed message as a menu with nothing of a dialog under it: the chat, never a card.
        ?: return QuestionRead(p, null, refused = ECHO_ROW.containsMatchIn(rows[first]))
    // Its top: the nearest edge above the options (or the title row just over it, as Kiro draws its tool) from which the same card reads.
    val edges = (first - 1 downTo maxOf(0, first - TOP_WITHIN)).filter { topEdge(rows[it]) }.take(EDGES_TRIED)
        .flatMap { e -> listOfNotNull(e, (e - 1 downTo maxOf(0, e - 2)).firstOrNull { rows[it].isNotBlank() }?.takeIf { !topEdge(rows[it]) }) }
    for (top in edges) {
        if ((top + 1 until first).any { ECHO_ROW.containsMatchIn(rows[it].trimStart()) }) return incomplete
        if (detectPrompt(rows.drop(top).joinToString("\n"), blocked, agent, version) == p) return QuestionRead(p, rows.subList(top, footer + 1))
    }
    return incomplete
}

/** Marks that move while you choose (the highlight, a filled radio, the current tab), left out of the comparison. */
private val MOVING_MARKS = "❯›>▶➤⟩→●◉○◯▸$TAB_CURRENT_MARK"

/**
 * Confirms question cards from consecutive reads, per pane (2.2.2): a card is confirmed when two
 * reads in a row give the same card (the highlight aside) from the same dialog rows, both
 * structurally complete, or [FALLBACK_READS] reads in a row give the same card when its structure
 * couldn't be shown complete. It stays confirmed while reads keep giving it; a changed card, a
 * refused read or no card starts over. Memory only.
 */
internal class QuestionConfirm {
    private val last = HashMap<String, Any>()
    private val runs = HashMap<String, Int>()
    private val confirmed = HashMap<String, Any>()

    /** True when [read]'s card may show: confirmed now, or a card that needs no confirming. */
    fun offer(key: String, read: QuestionRead?): Boolean {
        if (read == null || !needsConfirmation(read.prompt)) { forget(key); return read != null }
        if (read.refused) { forget(key); return false }
        val rows = read.region?.map { r -> r.filterNot { it in MOVING_MARKS }.replace(Regex("""\s+"""), " ").trim() }
        val sig = Triple(read.prompt.copy(selected = null), read.complete, rows)
        if (confirmed[key] == sig) return true
        confirmed.remove(key)
        val run = if (last[key] == sig) (runs[key] ?: 1) + 1 else 1
        last[key] = sig
        runs[key] = run
        if (run >= (if (read.complete) 2 else FALLBACK_READS)) { confirmed[key] = sig; return true }
        return false
    }

    fun forget(key: String) { last.remove(key); runs.remove(key); confirmed.remove(key) }
}

/**
 * The card the app shows for [parsed] (2.2.2): [parsed] when it needs no confirming or matches the
 * card the reads confirmed for this pane ([confirmed], the highlight aside); otherwise
 * [readingQuestionCard].
 */
internal fun shownCard(parsed: Prompt?, confirmed: Prompt?, agent: String): Prompt? = when {
    parsed == null || !needsConfirmation(parsed) -> parsed
    confirmed != null && confirmed.copy(selected = null).forCard() == parsed.copy(selected = null).forCard() -> parsed
    else -> readingQuestionCard(agent)
}

/** The card a needs-you alert is made from (2.2.2): [prompt] once confirmed, else neutral words with no options. */
internal fun alertCard(prompt: Prompt?, confirmed: Boolean, agent: String): Prompt? =
    if (prompt != null && needsConfirmation(prompt) && !confirmed)
        Prompt(readingAlertText(agent), emptyList(), terminalReview = true, readingQuestion = true)
    else prompt

/** How many more reads an alert waits for its question to be confirmed, and the pause between them. */
internal const val ALERT_CONFIRM_READS = 6
internal const val ALERT_CONFIRM_PAUSE_MS = 700L

/**
 * Confirms an alert's question from [first], its first read, and up to [maxReads] more from [read]
 * (null stops: the pane stopped waiting or could not be read). The confirmed card and its screen, or null.
 */
internal suspend fun confirmByReads(first: String, firstPrompt: Prompt, agent: String?, version: String?,
                                    maxReads: Int = ALERT_CONFIRM_READS, read: suspend () -> String?): Pair<Prompt, String>? {
    val gate = QuestionConfirm()
    var text = first
    var p: Prompt? = firstPrompt
    var n = 0
    while (true) {
        val prompt = p ?: return null
        if (gate.offer("alert", questionRead(text, prompt, agent, version, true))) return prompt to text
        if (n++ >= maxReads) return null
        text = read() ?: return null
        p = detectPrompt(text, true, agent, version)
    }
}
