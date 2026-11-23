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
internal val TALL_DIALOG_READS = listOf(200, 400)
internal const val TALL_DIALOG_MAX_LINES = 400

/**
 * The rows a Claude dialog parser looks at: the last 80, or (2.2.7) the whole of a dialog read
 * back from history, which starts at its top rule, up to [TALL_DIALOG_MAX_LINES].
 */
internal fun claudeDialogWindow(rows: List<String>, agent: String?): List<String> =
    if (agent.equals("claude", true) && rows.size > 80 && CLAUDE_DIALOG_TOP_RULE.matches(rows.first())) rows.takeLast(TALL_DIALOG_MAX_LINES)
    else rows.takeLast(80)

private fun screenRows(s: String) = stripAnsi(s).lines().map { it.replace("\r", "").trimEnd() }.dropLastWhile { it.isBlank() }

/**
 * [screen] (a read whose Claude dialog has its top off) completed from [history] (a deeper recent
 * read of the same pane): the dialog from its top rule to the bottom of the screen, or null when the
 * history does not prove it.
 */
internal fun extendFramedDialog(screen: String, history: String, blocked: Boolean, version: String?): String? {
    val base = detectPrompt(screen, blocked, "claude", version)?.takeIf { it.topOffScreen && it.choices.isNotEmpty() } ?: return null
    val b = screenRows(screen)
    val d = screenRows(history)
    if (b.isEmpty() || d.size <= b.size) return null
    // The history must end with exactly this screen: the visible part is the dialog's bottom.
    if (d.takeLast(b.size) != b) return null
    val start = d.size - b.size
    // The screen's own top rows again right above it are a redraw left in the history, not more of the command.
    val k = minOf(3, b.size, start)
    if (k >= 2 && d.subList(start - k, start) == b.subList(0, k)) return null
    // The nearest top rule above the screen; the parser then proves every row from it down.
    val top = (start - 1 downTo 0).firstOrNull { CLAUDE_DIALOG_TOP_RULE.matches(d[it]) } ?: return null
    val candidate = d.drop(top).joinToString("\n")
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
    if (!base.topOffScreen || base.choices.isEmpty()) return screen
    for (n in TALL_DIALOG_READS) {
        val history = read(n) ?: return screen
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
    if (whole != seen && !whole.endsWith("\n$seen")) return false
    fun under(p: QuestionParts) = p.blocks.dropWhile { it.kind != QKind.COMMAND }.drop(1)
    if (under(a) != under(b) || a.tabs != b.tabs) return false
    fun bare(p: Prompt) = p.copy(selected = null, question = "", parts = null, topOffScreen = false).forCard()
    return bare(shown) == bare(fresh)
}
