package com.billmongan.herdrdroid.herdr

private val CODEX_WARNING_FOOTER = Regex(
    """← for agents · \? for shortcuts[ \t]+⚠ ([1-9][0-9]*) (warning|warnings) · f2 to view"""
)

internal fun paneWarningCount(agent: String?, viewport: String): Int? {
    if (agent != "codex") return null
    val bottom = stripAnsi(viewport).trimEnd().substringAfterLast('\n').trimStart()
    val match = CODEX_WARNING_FOOTER.matchEntire(bottom) ?: return null
    val count = match.groupValues[1].toIntOrNull() ?: return null
    return count.takeIf { (it == 1) == (match.groupValues[2] == "warning") }
}
