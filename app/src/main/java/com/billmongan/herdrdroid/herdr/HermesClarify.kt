package com.billmongan.herdrdroid.herdr

private val liveHead = Regex("""ask ([1-9][0-9]*) questions?""")
private val historyHead = Regex("""ask \([1-9][0-9]* questions?\)""")
private val expiredNotice = Regex("""\(clarify timed out after [0-9]+s(?: [^)]*)?\)""")
private val footerStart = Regex("""[0-9]+/[1-9][0-9]* answered · (?:Space toggle · |↑/↓ select|Enter ).*""")
private const val SINGLE_FOOTER = "0/1 answered · ↑/↓ select · Enter confirm and continue · Tab/Shift+Tab switch question · Esc/Ctrl+C cancel"
private val optionRow = Regex("""(▸ )?([1-9][0-9]*)\. (\S.*)""")
private val statusRule = Regex("""─ .* │ .*─+.*""")
private const val OTHER = "Other (type your answer)"

internal data class HermesClarifyFrame(val card: Prompt? = null, val remainder: String = "")

/**
 * Ink's ClarifyPrompt (prompts.tsx at Hermes 3b421578) binds a digit directly to a preset
 * choice in single-select mode. The multi-select, custom editor and batch handlers differ.
 * Keep those and any incomplete native shape out of the generic numbered-menu parser.
 */
internal fun hermesClarifyFrame(plain: String): HermesClarifyFrame? {
    val lines = plain.lines().takeLast(80).map { it.trimEnd() }
    val rows = lines.map { it.trimStart() }
    var settled = -1
    var abandoned = false
    rows.forEachIndexed { i, row ->
        when {
            historyHead.matches(row) -> abandoned = true
            liveHead.matches(row) -> abandoned = false
            expiredNotice.matches(row) || (abandoned && row in setOf("(timed out)", "(cancelled)")) -> {
                settled = i
                abandoned = false
            }
        }
    }
    val head = rows.indexOfLast { liveHead.matches(it) }.takeIf { it > settled } ?: -1
    val footer = rows.indexOfLast { footerStart.matches(it) }.takeIf { it > maxOf(settled, head) } ?: -1
    if (head < 0 && footer < 0) {
        return if (settled < 0) null else HermesClarifyFrame(remainder = lines.drop(settled + 1).joinToString("\n"))
    }

    // PromptZone is above the status rule and the composer is suppressed while it owns input.
    // Accept a wrapped hint, but not a newer composer, approval or other content after it.
    val tail = if (footer < 0) emptyList() else rows.drop(footer + 1).filter { it.isNotBlank() }
    val hintRows = tail.takeWhile { it != "Esc/Ctrl+C cancel" && !it.endsWith(" Esc/Ctrl+C cancel") }
    val hintCount = when {
        footer < 0 || rows[footer].endsWith(" cancel") || rows[footer].endsWith(" Esc back") -> 0
        hintRows.size < tail.size -> hintRows.size + 1
        else -> 0
    }
    val after = tail.drop(hintCount)
    val chrome = after.size <= 1 && after.all { statusRule.matches(it) }
    if (footer >= 0 && !chrome && after.firstOrNull()?.let {
            it.startsWith('❯') || it.startsWith('›') || it.startsWith('╭') || it.all { c -> c in "─━═" }
        } == true) {
        // The old native footer is no longer the bottom-most UI. Parse only the newer rows.
        val end = if (hintCount == 0) footer else (footer + 1..rows.lastIndex)
            .filter { rows[it].isNotBlank() }[hintCount - 1]
        return HermesClarifyFrame(remainder = lines.drop(end + 1).joinToString("\n"))
    }

    val question = rows.getOrNull(head + 1)?.takeIf { head >= 0 && it.startsWith("▸ ") }?.substring(2)
    fun terminal() = HermesClarifyFrame(card = Prompt(question ?: "Hermes needs clarification", emptyList(),
        region = listOf("Open Terminal to answer this Hermes question."), terminalReview = true, hermesClarify = true))
    if (head < 0 || footer <= head + 2 || !chrome || rows[head] != "ask 1 question" || question.isNullOrBlank() ||
        (listOf(rows[footer]) + tail.take(hintCount)).joinToString(" ") != SINGLE_FOOTER) return terminal()

    // A strict contiguous run, including the automatic Other row, proves none are clipped.
    // Refuse if wrapping, checkbox markers, missing focus, or larger indices make it ambiguous.
    val options = rows.subList(head + 2, footer).map { optionRow.matchEntire(it) ?: return terminal() }
    if (options.size !in 2..10 || options.withIndex().any { (i, row) -> row.groupValues[2] != "${i + 1}" } ||
        options.last().groupValues[3] != OTHER || options.dropLast(1).any { it.groupValues[3] == OTHER }) return terminal()
    val focus = options.singleOrNull { it.groupValues[1].isNotEmpty() } ?: return terminal()
    val choices = options.dropLast(1).map { Choice(it.groupValues[3], it.groupValues[2], pressEnter = false) }
    return HermesClarifyFrame(card = Prompt(question, choices, selected = focus.groupValues[2],
        numberKeys = true, hermesClarify = true))
}
