package com.billmongan.herdrdroid.herdr

private const val COPILOT_PERMISSION_TITLE = "Select Permission Mode"
private val COPILOT_PERMISSION_CHOICES = listOf("Manual Approval", "Assisted Permissions", "Allow All")
private val COPILOT_PERMISSION_HINT = Regex(
    """^1[-–]3 to select\s*·\s*↑/↓ to navigate\s*·\s*enter to confirm\s*·\s*esc to cancel$""",
    RegexOption.IGNORE_CASE,
)
private val COPILOT_PERMISSION_OPTION = Regex("""^(❯\s+)?([1-3])\.\s+(.+?)(\s+✓)?$""")

/** The check mark names the current setting; only the caret proves which row Enter selects. */
internal fun copilotPermissionMode(screen: String): Prompt? {
    val tail = screen.lines().takeLast(14)
    val rows = tail.filterNot(String::isBlank).map(String::trim)
    if (rows.size < 5 || rows[rows.size - 5] != COPILOT_PERMISSION_TITLE ||
        !COPILOT_PERMISSION_HINT.matches(rows.last())) return null
    val options = rows.takeLast(4).dropLast(1).map { COPILOT_PERMISSION_OPTION.matchEntire(it) }
    if (options.map { it?.groupValues?.get(3) } != COPILOT_PERMISSION_CHOICES ||
        options.map { it?.groupValues?.get(2) } != listOf("1", "2", "3")) return null
    val focused = options.filterNotNull().singleOrNull { it.groupValues[1].isNotEmpty() }
    if (focused == null) return Prompt(COPILOT_PERMISSION_TITLE, emptyList(), region = rows.takeLast(5),
        terminalReview = true)
    return Prompt(COPILOT_PERMISSION_TITLE, COPILOT_PERMISSION_CHOICES.mapIndexed { index, label ->
        Choice(label, (index + 1).toString(), false)
    }, selected = focused.groupValues[2], approvalIdentity = rows.takeLast(5).joinToString("\n") {
        it.removePrefix("❯").trimStart()
    }, copilotPermissionControl = true)
}

/** A complete earlier picker cannot lend its options to subsequent transcript output. */
internal fun copilotAfterPermissionMode(screen: String): String? {
    val rows = screen.lines().takeLast(80)
    val footer = rows.indexOfLast { COPILOT_PERMISSION_HINT.matches(it.trim()) }
    if (footer < 0 || rows.drop(footer + 1).all(String::isBlank)) return null
    if (copilotPermissionMode(rows.take(footer + 1).joinToString("\n")) == null) return null
    return rows.drop(footer + 1).joinToString("\n")
}
