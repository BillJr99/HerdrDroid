package com.billmongan.herdrdroid.herdr

/**
 * 2.2.7 (Bill: "the top of this command is off screen that's what the card question says when it
 * can't render the whole thing ... I guess it could scroll or something to read the entire relevant
 * text"). A Claude permission dialog taller than the screen ([claudeFramedDialog] with its top off)
 * is completed from the pane's history: the app reads herdr's recent output, [TALL_DIALOG_READS]
 * lines deep in turn, and when the dialog's top rule and title are there, the card is built from
 * the whole dialog, with no note. The history is used only when it ends with exactly the screen
 * the card came from (so the visible part is the bottom of the dialog) and the rows above the screen
 * read as the same dialog's frame (title, description, dashed rule, "│" command rows): chat text
 * above the dialog, a different screen, or a stale copy of the screen's own top rows is refused, and
 * the card keeps the 2.2.4 note. The answer keys still come from the live dialog's options.
 */
internal val TALL_DIALOG_READS = listOf(200, 600, 2000)
internal const val TALL_DIALOG_MAX_LINES = 2000

/**
 * The first row of a dialog rebuilt from history (a word joiner alone, which draws as nothing): the
 * text is marked as rebuilt, so no ordinary read (one that happens to start on the input box's own
 * rule) is ever taken for one (2.2.7 review).
 */
internal const val TALL_DIALOG_MARK = "\u2060"
private const val TALL_ASK_MARK = "\u2060\u2060"

internal fun rebuiltClaudeAsk(text: String): Prompt? =
    if (text.substringBefore('\n') == TALL_ASK_MARK)
        claudeReviewPreview(text.substringAfter('\n'), null, TALL_DIALOG_MAX_LINES)?.copy(rebuiltTallDialog = true)
    else null

/** A rebuilt dialog: the mark, then the dialog's top rule, then its title row. */
internal fun isRebuiltDialog(rows: List<String>): Boolean =
    rows.size >= 3 && rows[0] == TALL_DIALOG_MARK && CLAUDE_DIALOG_TOP_RULE.matches(rows[1]) &&
        rows[2].isNotBlank() && !rows[2].trimStart().startsWith("│")

/**
 * The rows a Claude dialog parser looks at: the last 80, or (2.2.7) the whole of a dialog rebuilt
 * from history ([isRebuiltDialog]), up to [TALL_DIALOG_MAX_LINES].
 */
internal fun claudeDialogWindow(rows: List<String>, agent: String?): List<String> =
    if (agent.equals("claude", true) && rows.size > 80 && isRebuiltDialog(rows)) rows.takeLast(TALL_DIALOG_MAX_LINES + 1)
    else rows.takeLast(80)

private val CLAUDE_FIRST_ASK_OPTION = Regex("""^\s*[❯›]?\s*1[.)]\s+\S.*$""")

private fun screenRows(s: String) = stripAnsi(s).lines().map { it.replace("\r", "").trimEnd() }.dropLastWhile { it.isBlank() }

/**
 * [screen] (a read whose Claude dialog has its top off) completed from [history] (a deeper recent
 * read of the same pane): [TALL_DIALOG_MARK], then the dialog from its top rule to the bottom of the
 * screen, or null when the history does not prove it.
 */
internal fun extendFramedDialog(screen: String, history: String, blocked: Boolean, version: String?): String? {
    val base = detectPrompt(screen, blocked, "claude", version)?.takeIf { it.topOffScreen } ?: return null
    val b = screenRows(screen)
    val d = screenRows(history)
    if (b.isEmpty() || d.size <= b.size || d.takeLast(b.size) != b) return null
    val start = d.size - b.size
    // The screen's own top rows again right above it are a redraw left in the history.
    val k = minOf(3, b.size, start)
    if (k >= 2 && d.subList(start - k, start) == b.subList(0, k)) return null
    if (base.terminalReview) {
        val startTab = (start - 1 downTo 0).firstOrNull { claudeAskTab(d[it]) || claudeAskFooter(d[it]) }
            ?.takeIf { claudeAskTab(d[it]) } ?: return null
        val body = d.drop(startTab)
        if (body.size > TALL_DIALOG_MAX_LINES || body.dropLast(1).any { claudeAskFooter(it) }) return null
        // The earlier rows must contain the first option of this dialog, not a stray tab label.
        val leadingOptions = if (claudeClippedCheckboxRows(b) != null) body.dropLast(1)
            else d.subList(startTab + 1, start)
        if (leadingOptions.none { CLAUDE_FIRST_ASK_OPTION.matches(it) }) return null
        val candidate = (listOf(TALL_ASK_MARK) + body).joinToString("\n")
        return candidate.takeIf { rebuiltClaudeAsk(it) != null }
    }
    if (base.choices.isEmpty()) return null
    // The nearest top rule above the screen; the parser then proves every row from it down.
    val top = (start - 1 downTo 0).firstOrNull { CLAUDE_DIALOG_TOP_RULE.matches(d[it]) } ?: return null
    val candidate = (listOf(TALL_DIALOG_MARK) + d.drop(top)).joinToString("\n")
    val whole = detectPrompt(candidate, blocked, "claude", version) ?: return null
    if (whole.topOffScreen || !tallDialogFits(whole, base)) return null
    return candidate
}

/**
 * [screen] with its tall Claude dialog completed from history when that can be proven, else
 * [screen] unchanged (its card keeps [CLAUDE_TOP_OFF_NOTE]). [read] returns the pane's recent output
 * of that many lines, or null when it can't be read.
 */
internal suspend fun completeTallDialog(screen: String, blocked: Boolean, version: String?, read: suspend (Int) -> String?): String {
    val base = detectPrompt(screen, blocked, "claude", version) ?: return screen
    if (!base.topOffScreen) return screen
    for (n in TALL_DIALOG_READS) {
        val history = read(n) ?: return screen
        if (history.isBlank()) return screen
        extendFramedDialog(screen, history, blocked, version)?.let { return it }
        // The pane has no more history than this.
        if (screenRows(history).size < n - 1) return screen
    }
    return screen
}

/**
 * 2.2.7: [shown], a whole tall dialog, and [fresh], a read of the same dialog with its top off: the
 * same options and question, the same text under the command, and [fresh]'s command the bottom
 * lines of [shown]'s. Used where a card is checked against the live screen before an answer goes in.
 */
internal fun tallDialogFits(shown: Prompt, fresh: Prompt): Boolean {
    if (shown.topOffScreen || !fresh.topOffScreen || shown.choices.isEmpty()) return false
    val a = shown.shownParts()
    val b = fresh.shownParts()
    val whole = a.command ?: return false
    val seen = b.command ?: return false
    // Row by row the screen is the history's bottom; a top row that continues a wrapped line reads as
    // a line of its own on the screen alone, so the command is compared without its line breaks.
    if (!whole.replace("\n", "").endsWith(seen.replace("\n", ""))) return false
    if (a.heading != b.heading || a.tabs != b.tabs) return false
    // The screen's text (without its note) in the same order in the whole dialog, which adds at most its title and description.
    val shownText = a.details
    val seenText = b.details.filter { it != CLAUDE_TOP_OFF_NOTE && it != CLAUDE_TITLE_OFF_NOTE }
    if (shownText.size - seenText.size !in 0..3) return false
    var at = 0
    for (t in shownText) if (at < seenText.size && t == seenText[at]) at++
    if (at != seenText.size) return false
    fun bare(p: Prompt) = p.copy(selected = null, question = "", parts = null, region = null,
        topOffScreen = false, rebuiltTallDialog = false)
    return bare(shown) == bare(fresh)
}

private const val TALL_CLIPPED_MARK = "\u2060\u2060\u2060"

internal fun isClippedDialogViewport(screen: String, agent: String?): Boolean =
    clippedDialogFooter(screen, agent) && screenRows(screen).none { clippedDialogHeading(it, agent) }

/** Extra scrollback must have a proven native start before the live viewport. */
internal suspend fun completeClippedDialog(
    screen: String, blocked: Boolean, agent: String?, version: String?, read: suspend (Int) -> String?,
): String {
    val visible = screenRows(screen)
    if (visible.isEmpty() || !isClippedDialogViewport(screen, agent)) return screen
    val parsed = detectPrompt(screen, blocked, agent, version)
    if (parsed?.rebuiltTallDialog == true) return screen
    for (n in TALL_DIALOG_READS) {
        val history = read(n) ?: return screen
        val rows = screenRows(history)
        val start = rows.size - visible.size
        if (start > 0 && rows.takeLast(visible.size) == visible) {
            // An earlier copy of the visible prefix is a redraw, not an extension.
            val k = minOf(3, start, visible.size)
            val duplicate = k >= 2 && rows.subList(start - k, start) == visible.take(k)
            val head = (start - 1 downTo 0).firstOrNull { clippedDialogHeading(rows[it], agent) }
            if (!duplicate && head != null) {
                val before = rows.subList(head, start)
                val hasPriorFooter = before.indices.any { i ->
                    clippedDialogFooter(before.subList(maxOf(0, i - 24), i + 1).joinToString("\n"), agent)
                }
                if (!hasPriorFooter && !agentWentOn(before.drop(1))) {
                    val candidate = (listOf(TALL_CLIPPED_MARK) + rows.drop(head)).joinToString("\n")
                    if (rebuiltClippedDialog(candidate, blocked, agent, version) != null) return candidate
                }
            }
        }
        if (rows.size < n - 1) return screen
    }
    return screen
}

/** Recovered text is display-only. A marker can never make an old question answerable. */
internal fun rebuiltClippedDialog(text: String, blocked: Boolean, agent: String?, version: String?): Prompt? {
    if (text.substringBefore('\n') != TALL_CLIPPED_MARK) return null
    val body = text.substringAfter('\n')
    val rows = screenRows(body)
    if (rows.size !in 2..TALL_DIALOG_MAX_LINES || !clippedDialogHeading(rows.first(), agent) ||
        !clippedDialogFooter(body, agent)) return null
    val parsed = detectPrompt(body, blocked, agent, version)
    val labels = parsed?.choices.orEmpty().map { it.label }
    val heading = rowBody(rows.first()).second
    val parts = parsed?.parts?.takeIf { !parsed.terminalReview && !parsed.topOffScreen }
        ?: QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, heading),
            QBlock(QKind.TEXT, rows.drop(1).joinToString("\n") { rowBody(it).second })))
    return Prompt(heading, emptyList(), region = rows, terminalReview = true,
        rebuiltTallDialog = true, parts = if (labels.isEmpty()) parts else parts.copy(
            blocks = parts.blocks + labels.map { QBlock(QKind.TEXT, it) }))
}
