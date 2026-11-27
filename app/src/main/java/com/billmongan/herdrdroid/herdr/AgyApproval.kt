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
    val footer = rows.lastOrNull()?.let(GUTTER::matchEntire)
    if (footer == null || !MANAGE_HINT.matches(footer.groupValues[2])) return agyManagedApprovalReview(screen)
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

private val QUESTION_ANSWER_HINT = Regex("""(?i:ctrl[+-]k|\^k|c-k) answer · alt\+j manage""")
private val QUESTION_PICKER_HINT = "↑/↓ Navigate · enter Select · esc Skip"
private val QUESTION_PICKER_HEADER = Regex("""Question (\d+)/([1-9]\d*): (.+)""")
private val QUESTION_PICKER_ROW = Regex("""(> )?([1-9]\d*)\. (.+)""")
private val ORCHESTRATOR_FOOTER = Regex("""Orchestrator · .+ · \d+ subagent\(s\)""")
private val ORCHESTRATOR_SHORTCUTS = Regex("""\? for shortcuts\s{2,}Orchestrator · .+ · \d+ subagent\(s\)""")
private val BLOCKED_AGENT = Regex("""● Agent\(($AGENT_NAME)(?:: [^)]+)?\) · .+\s+Blocked · (AskQuestion|Bash) · .+""")
private const val MANAGED_APPROVAL_HINT = "↑/↓ navigate · / select · enter confirm · esc cancel"

private fun blockedAgyBanner(rows: List<String>, tool: String): Pair<String, List<String>>? {
    if (rows.size < 10 || !ORCHESTRATOR_SHORTCUTS.matches(rows.last().trim())) return null
    val tail = rows.filter { it.trim() != "┃" }.takeLast(10)
    if (tail.size != 10) return null
    if (listOf(4, 6, 8).any { !RULE.matches(tail[it].trim()) } || tail[5].trim() != ">") return null
    val blocked = BLOCKED_AGENT.matchEntire(tail[7].trim()) ?: return null
    if (blocked.groupValues[2] != tool) return null
    val banner = tail.take(4).map { GUTTER.matchEntire(it) ?: return null }
    val gutter = banner.first().groupValues[1]
    if (banner.any { it.groupValues[1] != gutter } || !RULE.matches(banner[1].groupValues[2])) return null
    return blocked.groupValues[1] to banner.map { it.groupValues[2] }
}

private fun agyManagedApprovalReview(screen: String): Prompt? {
    val rows = stripAnsi(screen).lines().takeLast(80).map(String::trimEnd).filter(String::isNotBlank)
    fun review(question: String, command: String, frame: List<String>) =
        Prompt(question, emptyList(), region = frame, terminalReview = true,
            parts = QuestionParts(emptyList(), listOf(QBlock(QKind.COMMAND, command))), agyApproval = true)

    blockedAgyBanner(rows, "Bash")?.let { (agent, banner) ->
        val heading = "$agent needs approval for Bash"
        val command = COMMAND.matchEntire(banner[2])?.groupValues?.get(1)
        if (banner[0] == heading && MANAGE_HINT.matches(banner[3]) && !command.isNullOrBlank()) {
            return review(heading, command, banner)
        }
    }
    val last = rows.lastOrNull() ?: return null
    val footerIndex = rows.lastIndex - if (ORCHESTRATOR_FOOTER.matches(last.trim())) 1 else 0
    if (rows.getOrNull(footerIndex)?.trim() != MANAGED_APPROVAL_HINT) return null
    val heading = rows.take(footerIndex).indexOfLast { it.trim() == "⚠ Approval Required" }
    if (heading < 0 || heading + 7 >= footerIndex || rows[heading + 1].trim() != "> Bash" ||
        rows[heading + 3].trim().removePrefix("> ") != "1. Yes, approve" ||
        rows[heading + 4].trim().removePrefix("> ") != "2. No, deny" ||
        !RULE.matches(rows[heading + 5].trim()) ||
        !rows[heading + 6].trim().startsWith("Trajectory ") || heading + 8 != footerIndex) {
        return Prompt("Review the AGY approval", emptyList(),
            region = listOf(MANAGED_APPROVAL_HINT), terminalReview = true, agyApproval = true)
    }
    return review("Review the AGY approval", rows[heading + 2].trim(), rows.subList(heading, footerIndex + 1))
}

/** A native question may sit above the default parent composer, or inside an open subagent picker. */
internal fun agySubagentQuestion(screen: String): Prompt? {
    val rows = stripAnsi(screen).lines().takeLast(80).map(String::trimEnd).filter(String::isNotBlank)
    val last = rows.lastOrNull() ?: return null
    fun review(question: String, details: List<String>, frame: List<String>) =
        Prompt(question, emptyList(), region = frame, terminalReview = true,
            parts = QuestionParts(emptyList(), details.map { QBlock(QKind.TEXT, it) }))

    val footerIndex = rows.lastIndex - if (ORCHESTRATOR_FOOTER.matches(last.trim())) 1 else 0
    if (rows.getOrNull(footerIndex)?.trim() == QUESTION_PICKER_HINT) {
        val options = rows.take(footerIndex).asReversed()
            .takeWhile { QUESTION_PICKER_ROW.matches(it.trim()) }.asReversed()
            .map { QUESTION_PICKER_ROW.matchEntire(it.trim())!! }
        val headerIndex = footerIndex - options.size - 1
        val header = rows.getOrNull(headerIndex)?.trim()?.let(QUESTION_PICKER_HEADER::matchEntire)
        if (header != null && options.size >= 2 &&
            options.map { it.groupValues[2].toIntOrNull() } == (1..options.size).toList() &&
            options.count { it.groupValues[1].isNotEmpty() } == 1 &&
            rows.getOrNull(headerIndex - 1)?.trim()?.let(RULE::matches) == true &&
            rows.getOrNull(headerIndex - 2)?.trim() == "Question") {
            val current = header.groupValues[1].toIntOrNull()
            val total = header.groupValues[2].toIntOrNull()
            if (current != null && total != null && current in 1..total) {
                return review(header.groupValues[3], options.map { it.groupValues[3] },
                    rows.subList(headerIndex - 2, footerIndex + 1))
            }
        }
        return review("Review the AGY question", emptyList(), listOf(QUESTION_PICKER_HINT))
    }

    val row = GUTTER.matchEntire(last)?.groupValues?.get(2)
    if (row != null && PICKER_HEADER.matches(row)) return review(row, emptyList(), listOf(row))

    // Only accept an earlier banner when the complete native footer binds it to a blocked agent.
    val (agent, banner) = blockedAgyBanner(rows, "AskQuestion") ?: return null
    if (banner[0] != "$agent has a question" || !QUESTION_ANSWER_HINT.matches(banner[3])) return null
    val question = banner[2].takeIf { it.startsWith("? ") }?.drop(2)?.takeIf { it.isNotBlank() }
        ?: return null
    return review(question, listOf("$agent has a question"), banner)
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
