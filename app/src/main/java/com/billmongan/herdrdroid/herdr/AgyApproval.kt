package com.billmongan.herdrdroid.herdr

private const val MANAGE_HINT = "ctrl+k approve · alt+j manage"
private val GUTTER = Regex("""^( {0,2})┃ +(.+)$""")
private val HEADER = Regex("""^([A-Za-z0-9][A-Za-z0-9._-]{0,79}) needs approval for Bash$""")
private val COMMAND = Regex("""^● Bash\((.+)\)$""")
private val RULE = Regex("""^─{3,}$""")

/** An AGY subagent can request approval underneath its parent's composer while it is working. */
internal fun agySubagentApproval(screen: String): Prompt? {
    val rows = stripAnsi(screen).lines().dropLastWhile { it.isBlank() }.takeLast(4).map(String::trimEnd)
    val footer = rows.lastOrNull()?.let(GUTTER::matchEntire) ?: return null
    if (footer.groupValues[2] != MANAGE_HINT) return null
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
