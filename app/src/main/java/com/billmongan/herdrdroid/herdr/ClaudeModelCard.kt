package com.billmongan.herdrdroid.herdr

private val PICKER_OPTION = Regex("""^\s*(❯?)\s*([1-9]\d?)\.\s{2,}(\S.*)$""")
private val PICKER_FOOTER = setOf(
    "s to use this session only · Enter to save · Esc to cancel",
    "Enter to set as default · s to use this session only · Esc to cancel",
)
private val PICKER_RULE = Regex("""^─{20,}\s*$""")
private const val MORE_FOOTER = "Enter to expand · Esc to cancel"
private const val MORE_ROW = "▸   More models"
private const val PICKER_INTRO = "Your pick is the default for new sessions. For other models, use --model."
private val EFFORT_CONTROL = Regex("""^[◐●]\s+(?:Low|Medium|High|XHigh|Max) effort(?: \(default\))? ←/→ to adjust$""", RegexOption.IGNORE_CASE)
private val FAST_HINT = Regex("""^Use /fast to turn o(?:n|ff) fast mode(?: \([^()]+\))?\.$""")
private const val EFFORT_DOWN = "claude-model-effort-down"
private const val EFFORT_UP = "claude-model-effort-up"

private fun pickerLabel(body: String) = body.split(Regex("""\s{2,}"""), limit = 2)
    .first().trim().trimEnd('✔').trimEnd()

/** Claude's complete /model picker at the bottom of the current screen. */
internal fun claudeModelCard(screen: String): Prompt? {
    val rows = screen.lines().dropLastWhile(String::isBlank).map(String::trimEnd)
    val footer = rows.lastOrNull()?.trim() ?: return null
    val expanding = footer == MORE_FOOTER
    if (!expanding && footer !in PICKER_FOOTER) return null
    val top = rows.indexOfLast { it.trim() == "Select model" }
    if (top == -1) return null
    val frame = rows.subList(top, rows.size)
    val body = frame.drop(1).dropLast(1).filterNot(String::isBlank)
    val firstOption = body.indexOfFirst(PICKER_OPTION::matches)
    val options = body.drop(firstOption.coerceAtLeast(0)).takeWhile(PICKER_OPTION::matches)
        .map { checkNotNull(PICKER_OPTION.matchEntire(it)) }
    val context = body.drop(firstOption.coerceAtLeast(0) + options.size).map(String::trim)
    val effort = context.singleOrNull(EFFORT_CONTROL::matches)
    val parts = QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, "Select model")) +
        body.map { QBlock(QKind.TEXT, it.trim()) })
    val review = Prompt("Select model", emptyList(), region = frame, terminalReview = true,
        parts = parts, claudeModelControl = true)
    if (top == 0 || !PICKER_RULE.matches(rows[top - 1]) ||
        firstOption < 0 || body.take(firstOption).any { it.trim() != PICKER_INTRO } ||
        options.size !in 2..20 || options.map { it.groupValues[2].toInt() } != (1..options.size).toList() ||
        context.any { it != MORE_ROW && !(expanding && it == "❯ $MORE_ROW") &&
            !EFFORT_CONTROL.matches(it) && !FAST_HINT.matches(it) } ||
        context.count(EFFORT_CONTROL::matches) > 1) return review
    val cancel = Choice("Cancel", "esc", false)
    if (expanding) {
        if (context.count { it == "❯ $MORE_ROW" } != 1 || options.any { it.groupValues[1].isNotEmpty() }) return review
        return review.copy(terminalReview = false, choices = listOf(Choice("Expand more models", "enter", false), cancel))
    }
    val focused = options.singleOrNull { it.groupValues[1] == "❯" } ?: return review
    val modelNames = options.map { pickerLabel(it.groupValues[3]) }
    if (modelNames.any(String::isBlank) || modelNames.toSet().size != modelNames.size) return review
    val choices = modelNames.mapIndexed { i, name -> Choice("Use $name this session", (i + 1).toString(), false) }
    val selected = focused.groupValues[2].toInt()
    val save = Choice("Save ${modelNames[selected - 1]} as default", "enter", false)
        .takeIf { footer.startsWith("Enter to set as default") || body.any { it.trim() == PICKER_INTRO } }
    val effortChoices = if (effort == null) emptyList() else listOf(
        Choice("Lower effort", EFFORT_DOWN, false),
        Choice("Raise effort", EFFORT_UP, false),
    )
    return review.copy(terminalReview = false, choices = choices + effortChoices + listOfNotNull(save, cancel),
        selected = selected.toString())
}

internal fun claudeModelKeys(prompt: Prompt, choice: Choice): List<String>? {
    if (choice !in prompt.choices) return null
    return when (choice.keys) {
        EFFORT_DOWN -> listOf("left")
        EFFORT_UP -> listOf("right")
        "esc", "enter" -> listOf(choice.keys)
        else -> {
            val from = prompt.selected?.toIntOrNull() ?: return null
            val to = choice.keys.toIntOrNull() ?: return null
            List(kotlin.math.abs(to - from)) { if (to > from) "down" else "up" } + "s"
        }
    }
}
