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
    val tailIndices = if (footer < 0) emptyList() else (footer + 1..rows.lastIndex).filter { rows[it].isNotBlank() }
    val tail = tailIndices.map { rows[it] }
    val hintRows = tail.takeWhile { it != "Esc/Ctrl+C cancel" && !it.endsWith(" Esc/Ctrl+C cancel") }
    val hintCount = when {
        footer < 0 || rows[footer].endsWith(" cancel") || rows[footer].endsWith(" Esc back") -> 0
        hintRows.size < tail.size -> hintRows.size + 1
        else -> 0
    }
    val after = tail.drop(hintCount)
    val statusCount = if (after.firstOrNull()?.let(statusRule::matches) == true) 1 else 0
    val newer = after.drop(statusCount)
    val chrome = newer.isEmpty()
    if (footer >= 0 && newer.firstOrNull()?.let {
            it.startsWith('❯') || it.startsWith('›') || it.startsWith('╭') || it.all { c -> c in "─━═" }
        } == true) {
        // New UI follows the old native footer and its optional status rule.
        return HermesClarifyFrame(remainder = lines.drop(tailIndices[hintCount + statusCount]).joinToString("\n"))
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

/*
 * 2.2.8 (Bill, 2026-11-24: "Hermes will ask a question occasionally that does not render in chat for
 * you, so please fix that"): the clarify panel of Hermes's classic CLI, the one its pane runs. Read
 * from hermes_cli/cli_tui_mixin.py (_get_clarify_display_fragments, _tui_hint_text,
 * _tui_make_clarify_number_handler), cli_modal_mixin.py (_clarify_callback, _clarify_batch_lock) and
 * locales/en.yaml. The parser above reads the Ink TUI's "ask N questions" frame only; the classic
 * panel has no such head, so its question fell through to the generic menu parser, which never
 * proved the frame (its hint row and "? ❯" input row under the box read as newer chat).
 *
 *     ╭─ Hermes needs your input ────────╮
 *     │ 2 questions                      │    header: how many questions
 *     │ ✓ Which branch?                  │    answered (its answer on the row under it)
 *     │     main                         │
 *     │ ▸ Run the tests first?           │    the active question (· marks one still to come)
 *     │   ❯ 1. Yes                       │    its choices: a digit locks one at once
 *     │     2. No                        │
 *     │     3. Other (type your answer)  │    a digit here opens typing in the composer
 *     ╰──────────────────────────────────╯
 *       ↑/↓ to select, Enter to lock, Tab next question  (88s)    hint row, countdown optional
 *     ? ❯                                                          input row ("✎ ❯" while typing)
 *
 * Only single-select choices are answered from the card, by their digit, as the Ink card is. A
 * question with no choices, the "Other" text box, multi-select checkboxes (digits only toggle them)
 * or a panel the parser can't prove whole (clipped, wrapped choices out of order) make a card that
 * shows the question and leaves the answer to Terminal: in those states a digit would be typed into
 * the composer or toggle a box rather than answer.
 */
private val CLASSIC_CHOICE_HINT = Regex("""^↑/↓ to select, Enter to lock, Tab next question(?:\s+\([0-9]+s\))?$""")
private val CLASSIC_TYPING_HINT = Regex("""^type your answer and press Enter(?:\s+\([0-9]+s\))?$""")
private val CLASSIC_INPUT = Regex("""^[?✎]\s*[❯›](?:\s.*)?$""")
/** The only input row under which a digit picks a choice: the waiting "? ❯", with nothing typed. */
private val CLASSIC_CHOOSING = Regex("""^\?\s*[❯›]$""")
private val CLASSIC_TOP = Regex("""^╭─ (.+ needs your input) ─+╮$""")
private val CLASSIC_HEADER = Regex("""^([1-9][0-9]*) questions?$""")
private val CLASSIC_MARKER = Regex("""^([✓▸·]) (\S.*)$""")
private val CLASSIC_CHOICE = Regex("""^  ([❯ ]) (\[[ x]\] )?([0-9])\. (\S.*)$""")
private const val CLASSIC_OTHER = "Other (type your answer)"
private const val CLASSIC_OTHER_TYPING = "Other (type below)"
private const val CLASSIC_GUIDANCE = "Type your answer in the prompt below, then press Enter."

/**
 * Hermes's classic clarify panel at the bottom of [plain] (the screen already cut at the composer by
 * [hermesWaitingScreen], so only its input row is under the hint), or null when it isn't there.
 */
internal fun hermesClassicClarify(plain: String): Prompt? {
    val lines = plain.lines().takeLast(80).map { it.trimEnd() }
    val input = lines.indexOfLast { it.isNotBlank() }
    if (input < 2 || !CLASSIC_INPUT.matches(lines[input].trim())) return null
    val hint = (input - 1 downTo 0).firstOrNull { lines[it].isNotBlank() } ?: return null
    val hintRow = lines[hint].trim()
    // Review 3 (Codex): the input row, not the hint alone, decides. The editing marker "✎", or any text in
    // the input row, means the composer takes text (a frame drawn mid-change can pair it with the older
    // choice hint), so a digit would be typed, not picked: that card is Terminal only.
    // Review 4 (Codex): Hermes's own hint sentence must sit right above the input row, whatever the input
    // row shows; any other row there (a stale panel above other output) is no card at all.
    val typingHint = CLASSIC_TYPING_HINT.matches(hintRow)
    if (!typingHint && !CLASSIC_CHOICE_HINT.matches(hintRow)) return null
    val typing = typingHint || !CLASSIC_CHOOSING.matches(lines[input].trim())
    val bottom = (hint - 1 downTo 0).firstOrNull { lines[it].isNotBlank() } ?: return null
    if (!lines[bottom].trim().let { it.startsWith("╰") && it.endsWith("╯") && it.drop(1).dropLast(1).all { c -> c == '─' } }) return null
    val top = (bottom - 1 downTo 0).firstOrNull { lines[it].trimStart().startsWith("╭") } ?: return null
    if (!CLASSIC_TOP.matches(lines[top].trim())) return null
    val framed = lines.subList(top + 1, bottom).map { it.trim() }
    if (framed.any { !it.startsWith("│") || !it.endsWith("│") }) return null
    // "│ " + text + padding + " │": the text keeps its own indent.
    val body = framed.map { it.removePrefix("│").removeSuffix("│").removePrefix(" ").trimEnd() }
    val count = body.firstOrNull()?.let(CLASSIC_HEADER::matchEntire)?.groupValues?.get(1)?.toIntOrNull() ?: return null

    data class Q(val marker: String, val text: MutableList<String>, val rows: MutableList<String> = mutableListOf())
    val questions = mutableListOf<Q>()
    for (row in body.drop(1)) {
        val m = CLASSIC_MARKER.matchEntire(row)
        when {
            m != null -> questions += Q(m.groupValues[1], mutableListOf(m.groupValues[2]))
            questions.isEmpty() -> return terminalClarify(null)
            // A wrapped question row ("  rest of it"), before any choice row.
            questions.last().rows.isEmpty() && row.startsWith("  ") && !row.startsWith("   ") && !CLASSIC_CHOICE.matches(row) &&
                row.trim() != CLASSIC_GUIDANCE ->
                questions.last().text += row.trim()
            else -> questions.last().rows += row
        }
    }
    val active = questions.singleOrNull { it.marker == "▸" }
    val question = active?.text?.joinToString(" ")
    // A clipped panel (the body scrolled to keep the choices in view) can't prove which question is live.
    if (active == null || questions.size != count || question.isNullOrBlank()) return terminalClarify(question)
    if (typing || active.rows.isEmpty()) return terminalClarify(question)

    // The choices: a strict run 1, 2, 3 … ending in Other, each on one row (a wrapped one refuses).
    val options = active.rows.map { CLASSIC_CHOICE.matchEntire(it) ?: return terminalClarify(question) }
    if (options.size !in 2..10 || options.withIndex().any { (i, m) -> m.groupValues[3] != "${(i + 1) % 10}" }) return terminalClarify(question)
    if (options.any { it.groupValues[2].isNotEmpty() }) return terminalClarify(question) // multi-select checkboxes
    if (options.last().groupValues[4] != CLASSIC_OTHER || options.dropLast(1).any { it.groupValues[4] in setOf(CLASSIC_OTHER, CLASSIC_OTHER_TYPING) }) {
        return terminalClarify(question)
    }
    val focus = options.singleOrNull { it.groupValues[1] == "❯" } ?: return terminalClarify(question)
    val choices = options.dropLast(1).map { Choice(it.groupValues[4], it.groupValues[3], pressEnter = false) }
    return Prompt(question, choices, selected = focus.groupValues[3], numberKeys = true, hermesClarify = true)
}

private fun terminalClarify(question: String?) = Prompt(question ?: "Hermes needs your input", emptyList(),
    region = listOf("Open Terminal to answer this Hermes question."), terminalReview = true, hermesClarify = true)

/**
 * Review 4 (Codex): the classic clarify panel is the newest box on [plain] and Hermes's input row ends
 * the screen, but [hermesClassicClarify] could not prove the panel live (other output sits between the
 * panel and the input row, so it is an old panel). No parser may read its rows as a menu then: no card.
 */
internal fun hermesStaleClassicPanel(plain: String): Boolean {
    val lines = plain.lines().takeLast(80).map { it.trim() }
    val input = lines.indexOfLast { it.isNotBlank() }
    if (input < 0 || !CLASSIC_INPUT.matches(lines[input])) return false
    val top = lines.indexOfLast { CLASSIC_TOP.matches(it) }
    return top >= 0 && (top + 1 until input).none { lines[it].startsWith("╭") } && hermesClassicClarify(plain) == null
}
