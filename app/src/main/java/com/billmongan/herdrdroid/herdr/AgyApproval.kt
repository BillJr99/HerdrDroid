package com.billmongan.herdrdroid.herdr

private val MANAGE_HINT = Regex("""(?i:ctrl[+-]k|\^k|c-k) approve · alt\+j manage""")
private val GUTTER = Regex("""^( {0,2})┃ +(.+)$""")
private const val AGENT_NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,79}"
private val HEADER = Regex("""^($AGENT_NAME) needs approval for Bash$""")
private val PICKER_HEADER = Regex("""^($AGENT_NAME) has a question$""")
private val COMMAND = Regex("""^● Bash\((.+)\)$""")
private val RULE = Regex("""^─{3,}$""")

/** An AGY subagent can request approval underneath its parent's composer while it is working. */
internal fun agySubagentApproval(screen: String): Prompt? {
    val rows = stripAnsi(screen).lines().dropLastWhile { it.isBlank() }.takeLast(4).map(String::trimEnd)
    val footer = rows.lastOrNull()?.let(GUTTER::matchEntire) ?: return null
    if (!MANAGE_HINT.matches(footer.groupValues[2])) return null
    val review = Prompt("Review the AGY approval", emptyList(), region = rows, terminalReview = true,
        agyApproval = true)
    if (rows.size != 4) return review
    val gutters = rows.map { GUTTER.matchEntire(it) ?: return review }
    if (gutters.any { it.groupValues[1] != footer.groupValues[1] }) return review
    val heading = gutters[0].groupValues[2]
    if (!HEADER.matches(heading) || !RULE.matches(gutters[1].groupValues[2])) return review
    val command = COMMAND.matchEntire(gutters[2].groupValues[2])?.groupValues?.get(1) ?: return review
    if (command.isBlank() || command.contains('…') || command.contains("...") ||
        command.any { it.isISOControl() || it == '\uFFFD' }) return review
    return Prompt(heading, listOf(Choice("Approve once (Ctrl+K)", "ctrl+k", false)),
        parts = QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, heading), QBlock(QKind.COMMAND, command))),
        approvalIdentity = rows.joinToString("\n"), agyApproval = true)
}

/** The pending question row is a cue to open a different picker, not an approval. */
internal fun agySubagentQuestion(screen: String): Prompt? {
    val last = stripAnsi(screen).lines().lastOrNull { it.isNotBlank() }?.trimEnd() ?: return null
    val row = GUTTER.matchEntire(last)?.groupValues?.get(2) ?: return null
    if (!PICKER_HEADER.matches(row)) return null
    return Prompt(row, emptyList(), region = listOf(row), terminalReview = true)
}

private val SHELL_APPROVAL_HINT = Regex(
    """↑/↓ Navigate · tab Amend · ctrl\+g edit/expand command""", RegexOption.IGNORE_CASE,
)
private val SHELL_APPROVAL_FOOTER = Regex("""esc to cancel(?:\s{2,}\S.*)?""", RegexOption.IGNORE_CASE)

/** A native shell approval can omit the command's tail from both the viewport and recent history. */
internal fun agyClippedShellCommand(screen: String): String? {
    val rows = stripAnsi(screen).lines().takeLast(80).map(String::trim)
        .filter(String::isNotEmpty)
    if (rows.size < 8 || !SHELL_APPROVAL_FOOTER.matches(rows.last()) ||
        !SHELL_APPROVAL_HINT.matches(rows[rows.lastIndex - 1])) return null
    val question = rows.indexOfLast { it == "Run this command?" }
    if (question < 2 || question >= rows.size - 4 ||
        rows[question + 1].removePrefix("> ").trim() != "1. Yes, run command" ||
        rows[rows.lastIndex - 2].removePrefix("> ").trim() != "4. No, cancel") return null
    val intro = rows.subList(0, question).indexOfLast { it == "Requesting permission for:" }
    if (intro < 0 || intro + 1 >= question) return null
    val lastCommandRow = rows[question - 1]
    if (!lastCommandRow.endsWith("...") && !lastCommandRow.endsWith("…")) return null
    return rows.subList(intro + 1, question).joinToString("\n")
}
