package com.billmongan.herdrdroid.herdr

private const val KIRO_ONCE_HINT = "y allow once · n deny once · t create allow rule · d create deny rule"
private val KIRO_RULE = Regex("""^[─━═]{3,}$""")

/** The direct one-time keys on Kiro's modern shell approval page. */
internal fun kiroApprovalShortcuts(screen: String): Prompt? {
    val rows = stripAnsi(screen).lines().dropLastWhile { it.isBlank() }
    val footer = rows.lastIndex
    if (footer < 7 || rows[footer].trim() != KIRO_ONCE_HINT ||
        !KIRO_RULE.matches(rows[footer - 1].trim())) return null
    val head = (footer - 2 downTo maxOf(1, footer - 12)).firstOrNull { i ->
        rows[i].trim() == "shell requires approval" && KIRO_RULE.matches(rows[i - 1].trim())
    } ?: return null
    val prompt = detectUnnumberedMenu(rows.joinToString("\n")) ?: return null
    val labels = prompt.choices.map { it.label }
    val command = prompt.parts?.command ?: return null
    if (labels.size != 4 || labels[0] != "Allow once" || labels[2] != "Deny once" ||
        !labels[1].startsWith("Create allow rule") || !labels[3].startsWith("Create deny rule") ||
        command.isBlank() || command.contains('…') || command.contains("...")) return null
    return prompt.copy(approvalIdentity = rows.subList(head - 1, footer + 1).joinToString("\n"),
        kiroOnceShortcuts = true)
}
