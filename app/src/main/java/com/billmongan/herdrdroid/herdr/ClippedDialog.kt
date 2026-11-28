package com.billmongan.herdrdroid.herdr

private val nativeNumber = Regex("""^[❯›>]?\s*(?:\[[ xX✓✔]]\s*)?(\d{1,2})(?:[.)]|\s+\([○●◯◉]\))\s+\S""")
private val bottomBorder = Regex("""^[╰└┗╚][─━═]+[╯┘┛╝]$""")
private val boxedGutter = Regex("""^[│┃║]\s*(.*?)\s*[│┃║]?$""")
private val openCodeButtons = Regex("""Allow once\s{3,}(?:Always allow\s{3,})?Reject""")
private val openCodeHint = Regex("""(?:⇆|←→)\s+select\s+enter\s+confirm$""")
private val openCodeQuestionHint = Regex("""(?:⇆\s+tab\s+)?(?:↑↓\s+select\s+)?enter\s+(?:submit|confirm|toggle)\s+esc\s+dismiss""")
private val copilotModeHint = Regex("""1[-–]3 to select\s*·\s*↑/↓ to navigate\s*·\s*enter to confirm\s*·\s*esc to cancel""", RegexOption.IGNORE_CASE)
private val agyShellHint = Regex("""↑/↓ Navigate · tab Amend · ctrl\+g edit/expand command""", RegexOption.IGNORE_CASE)
private val agyCancel = Regex("""esc to cancel(?:\s{2,}\S.*)?""", RegexOption.IGNORE_CASE)
private val hermesMenu = Regex("""[0-9]+/[1-9][0-9]* answered · .+ cancel""", RegexOption.IGNORE_CASE)
private val hermesClassic = Regex("""↑/↓ to select, Enter to (?:lock, Tab next question|confirm)(?:\s+\(\d+s\))?""")
private val hermesInput = Regex("""[?✎] ❯.*""")
private val kiroApprovalHint = Regex("""y allow once · n deny once · t create allow rule · d create deny rule""")

private fun clippedBody(row: String): String = row.trim().let {
    boxedGutter.matchEntire(it)?.groupValues?.get(1)?.trim() ?: it
}

/** Only native headings that identify the pane's active dialog can start a history recovery. */
internal fun clippedDialogHeading(row: String, agent: String?): Boolean {
    val body = clippedBody(stripAnsi(row))
    return when (ruleAgent(agent)) {
        "claude" -> claudeAskTab(body) || isClaudeDialogTitle(body)
        "codex" -> body in setOf("Hooks", "Hooks need review", "Would you like to run the following command?",
            "Do you want to make these changes?") || body.startsWith("Update available · ")
        "copilot" -> body == "Select Permission Mode" || isDialogHeaderRow(body)
        "kiro", "kiro-cli" -> body == "shell requires approval" || body.startsWith("Kiro CLI 3.0 is here")
        "vibe" -> body.startsWith("A new Vibe release is available") || body.startsWith("Permission for the ")
        "opencode" -> body in setOf("△ Permission required", "Permission required", "Reject permission") ||
            body == "Question"
        "agy" -> body in setOf("File access", "Requesting permission for:", "⚠ Approval Required", "Question")
        "grok" -> body == "Run this command?" || body.startsWith("Run this command:") ||
            body.startsWith("Approve this action") || isDialogHeaderRow(body)
        "hermes" -> body == "Hermes needs your input" || body.startsWith("ask ") && body.endsWith(" question") ||
            body.startsWith("Dangerous command") || body.startsWith("⚠ Approval")
        "omp", "pi" -> body.startsWith("Ask · ") || body.startsWith("Allow tool:")
        else -> false
    }
}

private fun isClaudeDialogTitle(body: String): Boolean =
    body in setOf("Bash command", "Edit file", "Create file", "WebFetch", "Exit plan mode") || isDialogHeaderRow(body)

/** A terminal-tail witness for a question whose header may be outside the captured rows. */
internal fun clippedDialogFooter(screen: String, agent: String?): Boolean {
    val plain = stripAnsi(screen)
    val harness = ruleAgent(agent)
    val relevant = if (harness == "claude") withoutClaudeChromeUnderDialog(plain) else plain
    val rows = relevant.lines().takeLast(32).filterNot(String::isBlank)
    val bottom = rows.lastOrNull()?.trim() ?: return false
    val raw = if (bottomBorder.matches(bottom)) rows.dropLast(1) else rows
    val tail = raw.map(::clippedBody)
    val footer = tail.lastOrNull() ?: return false
    val numbered = tail.dropLast(1).takeLast(10).mapNotNull { nativeNumber.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val hasOptions = numbered.size >= 2 && numbered.zipWithNext().all { (a, b) -> b == a + 1 }
    return when (harness) {
        "claude" -> hasOptions && (claudeAskFooter(footer) || claudePermissionFooterAtBottom(plain))
        "codex" -> hasOptions && footer.lowercase() in setOf("press enter to confirm or esc to cancel",
            "press enter to confirm or esc to go back", "enter continue · esc skip", "enter confirm · esc skip")
        "copilot" -> hasOptions && (copilotModeHint.matches(footer) ||
            footer.contains("enter", true) && isDialogFooterRow(footer))
        "kiro", "kiro-cli" -> (hasOptions || tail.takeLast(8).any { it.startsWith("❯ ") }) &&
            (kiroApprovalHint.matches(footer) || footer.contains("enter", true) && isDialogFooterRow(footer))
        "vibe" -> hasOptions && footer.contains("navigate", true) && isDialogFooterRow(footer) ||
            raw.size >= 2 && bottomBorder.matches(bottom) && boxedGutter.matches(raw.last().trim()) &&
            footer.matches(Regex("""←→\s+navigate\s+Enter\s+select""", RegexOption.IGNORE_CASE))
        "opencode" -> {
            // A narrow panel can put its controls on the preceding row. Chrome below the dialog is
            // admitted only when it is the normal command footer and is outside its gutter.
            val controls = if (!raw.last().trim().startsWith("┃") && footer.contains("ctrl+p commands", true))
                tail.dropLast(1) else tail
            val hint = controls.lastOrNull().orEmpty()
            openCodeHint.containsMatchIn(hint) && controls.takeLast(3).any(openCodeButtons::containsMatchIn) ||
                hasOptions && openCodeQuestionHint.matches(hint)
        }
        "agy" -> hasOptions && agyCancel.matches(footer) &&
            tail.getOrNull(tail.lastIndex - 1)?.let { it == "↑/↓ Navigate" || agyShellHint.matches(it) } == true ||
            hasOptions && footer in setOf("↑/↓ Navigate · enter Select · esc Skip",
                "↑/↓ navigate · / select · enter confirm · esc cancel")
        "grok" -> hasOptions && isDialogFooterRow(footer) || GROK_PLAN_FOOTER_ROW.matches(raw.last())
        "hermes" -> hermesMenu.matches(footer) && hasOptions ||
            hermesInput.matches(footer) && tail.getOrNull(tail.lastIndex - 1)?.let(hermesClassic::matches) == true
        "omp", "pi" -> hasOptions && (OMP_APPROVAL_FOOTER.containsMatchIn(footer) || isDialogFooterRow(footer))
        else -> false
    }
}

/** A clipped dialog has no actionable choices until its own beginning can be verified. */
internal fun clippedDialogReview(screen: String, agent: String?): Prompt? {
    if (!clippedDialogFooter(screen, agent)) return null
    val rows = stripAnsi(screen).lines().dropLastWhile(String::isBlank).takeLast(80)
    val title = "Question or approval"
    return Prompt(title, emptyList(), region = rows, terminalReview = true, topOffScreen = true,
        parts = QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, title)) +
            rows.mapNotNull { row -> clippedBody(row).takeIf(String::isNotBlank)?.let { QBlock(QKind.TEXT, it) } }))
}
