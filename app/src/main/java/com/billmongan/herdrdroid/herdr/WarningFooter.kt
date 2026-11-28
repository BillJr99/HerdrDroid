package com.billmongan.herdrdroid.herdr

private val CODEX_WARNING_FOOTER = Regex(
    """← for agents · \? for shortcuts[ \t]+⚠ ([1-9][0-9]*) (warning|warnings) · f2 to view"""
)

internal fun paneWarningCount(agent: String?, viewport: String): Int? {
    if (agent != "codex") return null
    val bottom = stripAnsi(viewport).trimEnd().substringAfterLast('\n').trimStart()
    val match = CODEX_WARNING_FOOTER.matchEntire(bottom) ?: return null
    val count = match.groupValues[1].toIntOrNull() ?: return null
    return count.takeIf { (it == 1) == (match.groupValues[2] == "warning") }
}

internal sealed interface PaneWarningResult {
    data class Ready(val warnings: List<String>) : PaneWarningResult
    data class Unavailable(val reason: String) : PaneWarningResult
}

private val CODEX_WARNING_TITLE = Regex("""Warnings · ([1-9][0-9]*) of ([1-9][0-9]*) · \S.*""")
private val CODEX_WARNING_CONTROLS = Regex(
    """k keep & next · \S+ dismiss & close(?: · [^·]+)*"""
)

internal data class CodexWarningPage(val number: Int, val count: Int, val text: String, val clipped: Boolean)

/** Codex 0.172.0 draws a single diagnostic above its warning viewer controls. */
internal fun codexWarningPage(viewport: String): CodexWarningPage? {
    val rows = stripAnsi(viewport).trimEnd().lines()
    val footer = rows.last().trimStart()
    if (!CODEX_WARNING_CONTROLS.matches(footer)) return null
    val title = rows.indices.reversed().firstOrNull { CODEX_WARNING_TITLE.matches(rows[it].trim()) } ?: return null
    val heading = CODEX_WARNING_TITLE.matchEntire(rows[title].trim()) ?: return null
    val number = heading.groupValues[1].toIntOrNull() ?: return null
    val count = heading.groupValues[2].toIntOrNull() ?: return null
    if (number > count || rows.getOrNull(title + 1)?.isNotBlank() != false) return null
    val body = rows.subList(title + 2, rows.lastIndex)
    val details = body.dropLastWhile(String::isBlank).dropWhile(String::isBlank)
        .joinToString("\n") { it.removePrefix("  ").trimEnd() }
    if (details.isBlank()) return null
    val clipped = body.lastOrNull()?.isNotBlank() != false || footer.endsWith(" scroll")
    return CodexWarningPage(number, count, details, clipped)
}

private fun warningComposerProblem(viewport: String): String? = when {
    infoPanelOn("codex", viewport) != null || dismissibleUsageFrame("codex", viewport) != null ||
        detectPrompt(forPhone(viewport), false, "codex") != null || showsQuestionDialog(stripAnsi(viewport)) ->
        "The pane has an open question or panel."
    composerFooterSaysWorking(viewport) || hasCodexInputActivity(viewport) -> "The pane is busy. Try again when it is idle."
    inputLineOf(viewport, "codex") != "" || composerDraftOf(viewport, "codex") != null ->
        "The pane needs a verified empty composer."
    else -> null
}

internal data class WarningPaneFrame(val pane: Pane, val occupant: String, val viewport: String)

/**
 * Codex's Esc and F2 close the viewer by dismissing viewed warnings on the host. Its literal
 * `k` keeps the warning, advances a page, and closes on the final page.
 */
internal suspend fun readCodexWarningPages(
    expected: Pane,
    allowed: () -> Boolean,
    read: suspend () -> WarningPaneFrame?,
    send: suspend (WarningPaneFrame, String) -> Boolean,
    pause: suspend () -> Unit,
): PaneWarningResult {
    fun unavailable(why: String) = PaneWarningResult.Unavailable(why)
    if (expected.agent != "codex") return unavailable("Open in terminal to read.")
    if (!allowed() || !hasStableOccupant(expected)) return unavailable("The pane is no longer current.")
    val initial = read() ?: return unavailable("The pane screen could not be verified.")
    if (!allowed() || !sameOccupant(expected, initial.pane) || initial.pane.scrollOffset != 0)
        return unavailable("The pane changed or is scrolled back.")
    if (initial.pane.status !in setOf(Status.IDLE, Status.DONE))
        return unavailable("The pane is busy. Try again when it is idle.")
    warningComposerProblem(initial.viewport)?.let { return unavailable(it) }
    val count = paneWarningCount("codex", initial.viewport)
        ?: return unavailable("The warning footer changed. Open in terminal to read.")
    if (count > 8) return unavailable("Open in terminal to read all warnings.")
    if (!allowed() || !send(initial, "f2")) return unavailable("The pane changed before the warning panel opened.")

    suspend fun nextFrame(accept: (WarningPaneFrame) -> Boolean): WarningPaneFrame? {
        repeat(4) {
            if (!allowed()) return null
            val frame = read() ?: return null
            if (!allowed() || frame.occupant != initial.occupant || !sameOccupant(initial.pane, frame.pane) ||
                frame.pane.scrollOffset != 0 || frame.pane.status !in setOf(Status.IDLE, Status.DONE)) return null
            if (accept(frame)) return frame
            pause()
        }
        return null
    }

    val warnings = mutableListOf<String>()
    var clipped = false
    for (number in 1..count) {
        val frame = nextFrame {
            val page = codexWarningPage(it.viewport)
            page?.number == number && page.count == count
        } ?: return unavailable("The warning panel could not be verified. Open Terminal to check it.")
        val page = checkNotNull(codexWarningPage(frame.viewport))
        warnings.add(page.text)
        clipped = clipped || page.clipped
        if (!allowed() || !send(frame, "k"))
            return unavailable("The warning panel changed before it could be closed. Open Terminal to check it.")
    }
    val restored = nextFrame { stripAnsi(it.viewport).trimEnd() == stripAnsi(initial.viewport).trimEnd() }
    if (restored == null) return unavailable("Could not verify that the pane returned to its original screen.")
    if (clipped) return unavailable("A warning is longer than the visible panel. Open in terminal to read.")
    return PaneWarningResult.Ready(warnings)
}
