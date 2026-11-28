package com.billmongan.herdrdroid.herdr

private const val VIBE_RELEASE_TITLE = "A new Vibe release is available"
private val VERSION_CHANGE = Regex("""\d+(?:\.\d+){2}\s+→\s+\d+(?:\.\d+){2}""")
private val BOX_TOP = Regex(""" *╭─{10,}╮""")
private val KEY_HINT = Regex("""←→\s+navigate\s+Enter\s+select""", RegexOption.IGNORE_CASE)
private val BUTTON_GAP = Regex("""\s{3,}""")
private val VIBE_RELEASE_BUTTONS = listOf("Update now", "Continue with current version")

/** A complete live modal may sit lower than ordinary transcript content and have no prompt glyph. */
internal fun detectVibeRelease(screen: String, allowGenericReview: Boolean = false): Prompt? {
    val styled = screen.lines()
    val plain = styled.map { stripAnsi(it).trimEnd() }
    val bottom = plain.indexOfLast { it.isNotBlank() }
    if (bottom < 2) return null
    val border = plain[bottom]
    val column = border.indexOf('╰')
    if (column < 0 || !border.endsWith('╯')) return null
    val top = (bottom - 1 downTo maxOf(0, bottom - 20)).firstOrNull {
        BOX_TOP.matches(plain[it]) && plain[it].replace('╭', '╰').replace('╮', '╯') == border
    } ?: return null
    val end = border.lastIndex
    val content = (top + 1 until bottom).map { i ->
        val row = plain[i]
        if (row.length != border.length || row.getOrNull(column) != '│' || row.last() != '│' ||
            row.take(column).any { it != ' ' }) return null
        i to row.substring(column + 1, end).trim()
    }.filter { it.second.isNotBlank() }
    if (content.size < 3 || !KEY_HINT.matches(content.last().second)) return null
    val labels = content[content.lastIndex - 1].second.split(BUTTON_GAP)
    if (labels.size !in 2..5) return null
    val (title, version) = content.map { it.second }.let { it.first() to it[1] }
    val region = plain.subList(top, bottom + 1)
    if (title != VIBE_RELEASE_TITLE || content.size != 4 || !VERSION_CHANGE.matches(version) ||
        labels != VIBE_RELEASE_BUTTONS) {
        return if (title == VIBE_RELEASE_TITLE || allowGenericReview)
            Prompt(title, emptyList(), region = region, terminalReview = true)
        else null
    }
    val buttonIndex = content[content.lastIndex - 1].first
    val buttons = styledChars(styled[buttonIndex])
    val row = plain[buttonIndex]
    val fills = labels.map { label ->
        val from = row.indexOf(label)
        buttons.subList(from, from + label.length).map { it.background to it.reverse }.distinct().singleOrNull()
    }
    // The capture shows a filled button and a regular button. A plain snapshot can render the
    // labels, but only the colored live viewport establishes focus for sending.
    val selected = fills.mapIndexedNotNull { index, style ->
        (index + 1).toString().takeIf { style != null && (style.first.isNotEmpty() || style.second) }
    }.singleOrNull()?.takeIf { fills.all { it != null } }
    val versionLabel = version.replace(Regex("""\s+→\s+"""), " to ")
    return Prompt("$title\n$versionLabel", labels.mapIndexed { i, label -> Choice(label, (i + 1).toString(), false) },
        selected = selected, horizontal = true, vibeRelease = true, approvalIdentity = region.joinToString("\n"))
}
