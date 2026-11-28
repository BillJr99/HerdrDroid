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

private const val KIRO_V3_STARTUP_TITLE = "Kiro CLI 3.0 is here"
internal const val KIRO_SKIP_STARTUP_REMINDER = "Stay on V2, don't ask again"
private val KIRO_V3_STARTUP_OPTIONS = listOf(
    "Continue with V3", "Stay on V2, remind me later", KIRO_SKIP_STARTUP_REMINDER,
)

/** The V3 startup picker ends at its three unnumbered options and shows its focus with ❯. */
internal fun kiroV3Startup(screen: String): Prompt? {
    val rows = screen.lines().dropLastWhile(String::isBlank)
    val start = rows.size - KIRO_V3_STARTUP_OPTIONS.size
    if (start <= 0) return null
    val options = rows.drop(start).map(String::trim)
    if (options.map { it.removePrefix("❯ ").trimStart() } != KIRO_V3_STARTUP_OPTIONS) return null
    val title = (start - 1 downTo maxOf(0, start - 16)).firstOrNull {
        val line = rows[it].trim()
        line == KIRO_V3_STARTUP_TITLE || line.startsWith("$KIRO_V3_STARTUP_TITLE.")
    } ?: return null
    val body = rows.subList(title, start)
    if (agentWentOn(body.drop(1))) return null
    val selected = options.indices.filter { options[it].startsWith("❯ ") }.singleOrNull()
    val parts = questionParts(body.map { QLine(it) })
    if (selected == null) return Prompt(KIRO_V3_STARTUP_TITLE, emptyList(), region = rows.drop(title),
        terminalReview = true, parts = parts)
    return Prompt(KIRO_V3_STARTUP_TITLE, KIRO_V3_STARTUP_OPTIONS.mapIndexed { index, label ->
        Choice(label, (index + 1).toString(), false)
    }, selected = (selected + 1).toString(), parts = parts, kiroStartupMigration = true)
}
