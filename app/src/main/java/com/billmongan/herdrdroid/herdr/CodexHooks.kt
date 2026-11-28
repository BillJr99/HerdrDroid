package com.billmongan.herdrdroid.herdr

private const val HOOKS_TITLE = "Hooks need review"
private const val HOOKS_BROWSER_TITLE = "Hooks"
private const val HOOKS_BROWSER_SUBTITLE = "Lifecycle hooks from config and enabled plugins."
private const val HOOKS_TRUST_HINT = "t trust all · enter review · esc close"
private const val HOOKS_DETAILS_HINT = "enter details · esc close"
private const val HOOKS_STARTUP_HINT = "enter confirm · esc skip"
private val HOOKS_PENDING = Regex("""⚠ (?:1 hook needs review before it can run\.|[1-9]\d* hooks need review before they can run\.)""")
private val HOOKS_NEW = Regex("""(?:1 hook is new or changed\.|[1-9]\d* hooks are new or changed\.)""")
private val HOOKS_HEADER = Regex("""Event\s+(?:Installed\s+Active(?:\s+Review)?\s+Description|Active/Installed)""")
private val HOOKS_EVENT_ROW = Regex("""(?:› )?[A-Z][A-Za-z]+\s+(?:\d+\s+\d+\s+\S.*|\d+/\d+(?: !)?|\d+\s+\d+)""")
private val HOOKS_HANDLER_HEAD = Regex("""[A-Z][A-Za-z]+ hooks""")
private val HOOKS_HANDLER_HINT = Regex("""(?:(?:t trust|space(?:/enter)? toggle|Managed \(always on\)) · )?esc back""")
private val HOOKS_STARTUP_OPTION = Regex("""(› )?([123])\. (\S.*)""")

internal const val CODEX_HOOKS_REVIEW = "Review"

private fun hookRows(text: String) = stripAnsi(text).lines().takeLast(48).map(String::trim).filter(String::isNotBlank)

private fun hooksEventTable(rows: List<String>, footer: String, review: Boolean): List<String>? {
    if (rows.lastOrNull() != footer) return null
    val start = rows.indexOfLast { it == HOOKS_BROWSER_TITLE }
    if (start < 0 || rows.getOrNull(start + 1) != HOOKS_BROWSER_SUBTITLE) return null
    val header = (start + 2 until rows.lastIndex).lastOrNull { HOOKS_HEADER.matches(rows[it]) } ?: return null
    val notice = rows.subList(start + 2, header)
    if (notice.any(HOOKS_PENDING::matches) != review) return null
    val events = rows.subList(header + 1, rows.lastIndex).filterNot { it in setOf("↑", "↓") }
    if (events.isEmpty() || events.any { !HOOKS_EVENT_ROW.matches(it) }) return null
    return rows.subList(start + 2, rows.lastIndex)
}

private fun startupHooksReview(rows: List<String>): Prompt? {
    if (rows.lastOrNull() != HOOKS_STARTUP_HINT) return null
    val start = rows.indexOfLast { it == HOOKS_TITLE }
    if (start < 0 || rows.getOrNull(start + 1)?.let(HOOKS_NEW::matches) != true) return null
    val options = (start + 2 until rows.lastIndex).mapNotNull { i ->
        HOOKS_STARTUP_OPTION.matchEntire(rows[i])?.let { i to it }
    }
    if (options.map { it.second.groupValues[2] } != listOf("1", "2", "3")) return null
    val first = options[0].first
    val second = options[1].first
    val last = options[2].first
    if (second != first + 1 || last != second + 1 ||
        options[0].second.groupValues[3] != "Review hooks" ||
        options[1].second.groupValues[3] != "Trust all and continue" ||
        rows.subList(last, rows.lastIndex).joinToString(" ").removePrefix("› ").removePrefix("3. ") !=
        "Continue without trusting (hooks won't run)" ||
        rows.subList(start + 2, first).joinToString(" ") != "Hooks can run outside the sandbox after you trust them.") return null
    val focused = options.map { it.second }.singleOrNull { it.groupValues[1].isNotEmpty() } ?: return null
    return Prompt(HOOKS_TITLE, listOf(Choice(CODEX_HOOKS_REVIEW, "1", false),
        Choice("Trust all and continue", "2", false), Choice("Continue without trusting", "3", false)),
        selected = focused.groupValues[2], parts = questionParts(rows.subList(start, first).map { QLine(it) }),
        approvalIdentity = rows.subList(start, rows.size).joinToString("\n"), codexHooksReview = true)
}

/** Match Codex's exact startup menu or its events table before offering hook-review actions. */
internal fun codexHooksPrompt(text: String): Prompt? {
    val rows = hookRows(text)
    val table = hooksEventTable(rows, HOOKS_TRUST_HINT, review = true) ?: return startupHooksReview(rows)
    val details = table.filterNot { it in setOf("↑", "↓") }.joinToString("\n") { it.removePrefix("› ") }
    return Prompt(HOOKS_TITLE, listOf(Choice("Trust all", "t", false),
        Choice(CODEX_HOOKS_REVIEW, "enter", false), Choice("Close", "esc", false)), letterKeys = true,
        parts = QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, HOOKS_TITLE), QBlock(QKind.TEXT, details))),
        approvalIdentity = rows.joinToString("\n"), codexHooksReview = true)
}

/** Informational pages reached after a guarded answer; they still own the pane's keys. */
internal fun codexHooksBrowserPassive(text: String): Boolean {
    val rows = hookRows(text)
    if (hooksEventTable(rows, HOOKS_DETAILS_HINT, review = false) != null) return true
    if (rows.lastOrNull()?.let(HOOKS_HANDLER_HINT::matches) != true) return false
    val head = rows.indexOfLast { HOOKS_HANDLER_HEAD.matches(it) }
    return head >= 0 && rows.getOrNull(head + 1)?.let { it == "Turn hooks on or off. Your changes are saved automatically." ||
        HOOKS_PENDING.matches("⚠ $it") } == true
}
