package com.billmongan.herdrdroid.herdr

/**
 * Muse Code's quota warning (2.1.0, Bill: "when muse pops up warnings like that present them as a
 * question card in herdrdroid ... so that I can dismiss it appropriately"). The strings are Muse
 * Code 1.4.24's own (muse-bin-1.4.24-R9131.1); "--" below stands for the em dash Muse draws:
 *
 *   You're using your quota fast
 *   At this pace your <window> quota runs out in about <n> minute(s) (resets at <time>)
 *   Switch to Spark -- about 5× more turns
 *     Spark becomes your default model. Switch back anytime with /model.
 *   Turn on contributor mode -- about 2.5× more turns
 *     Session content may be used for product improvement.
 *   Both -- over 10× more turns
 *     Spark becomes your default; session content may be used for product improvement. Terms of Service
 *      (https://dev.meta.ai/legal/terms-of-service)
 *   Keep going
 *
 * Options it doesn't offer are left out (a captured dialog had no contributor-mode row). The rows
 * have no numbers, no cursor glyph and no key hints: the highlighted one differs only in colour, so
 * it is known only from a read with styles. On a plain read the card shows, but an answer is sent
 * only after the guarded live read finds the highlight; then the arrows move it and Enter picks.
 */
internal val MUSE_QUOTA_TITLES = listOf("Switch to Spark", "Turn on contributor mode", "Both", "Keep going")

private val MUSE_OPTION = Regex("""^( {1,4})(Switch to Spark|Turn on contributor mode|Both|Keep going)(\s+\u2014\s+(?:about|over) [\d.]+\u00D7 more turns)?\s*$""")
private val MUSE_RULE = Regex("""^\s*[─━═]{8,}""")
private val MUSE_SGR = Regex("""\u001B\[([0-9;]*)m""")
private val MUSE_ANY_ESC = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B\][^\u0007\u001B]*(?:\u0007|\u001B\\)""")

/** How the first letter of a row is drawn: enough to tell the highlighted option from the rest. */
private data class MuseLook(val bold: Boolean, val faint: Boolean, val inverse: Boolean, val fg: String, val bg: String) {
    /** More is more emphasis: the highlight is drawn bolder, brighter or on a background. */
    val emphasis: Int get() = (if (inverse || bg.isNotEmpty()) 3 else 0) + (if (bold) 2 else 0) +
        (if (fg.isNotEmpty() && !greyish(fg)) 1 else 0) - (if (faint || greyish(fg)) 1 else 0)

    private fun greyish(c: String): Boolean = when {
        c == "90" || c == "37" || c == "5;8" || c == "5;7" -> true
        c.startsWith("5;") -> c.removePrefix("5;").toIntOrNull()?.let { it in 232..255 || it == 8 } == true
        c.startsWith("2;") -> c.removePrefix("2;").split(';').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }
            ?.let { (r, g, b) -> maxOf(r, g, b) - minOf(r, g, b) <= 24 && (r + g + b) / 3 in 70..200 } == true
        else -> false
    }
}

/** The look of the first visible letter of [raw], or null without styles. */
private fun museLook(raw: String): MuseLook? {
    if (!raw.contains('\u001B')) return null
    var bold = false; var faint = false; var inverse = false; var fg = ""; var bg = ""
    var i = 0
    while (i < raw.length) {
        val m = if (raw[i] == '\u001B') MUSE_ANY_ESC.find(raw, i)?.takeIf { it.range.first == i } else null
        if (m != null) {
            MUSE_SGR.matchEntire(m.value)?.let { sgr ->
                val ps = sgr.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
                var k = 0
                while (k < ps.size) {
                    when (val c = ps[k]) {
                        0 -> { bold = false; faint = false; inverse = false; fg = ""; bg = "" }
                        1 -> bold = true
                        2 -> faint = true
                        22 -> { bold = false; faint = false }
                        7 -> inverse = true
                        27 -> inverse = false
                        39 -> fg = ""
                        49 -> bg = ""
                        in 30..37, in 90..97 -> fg = c.toString()
                        in 40..47, in 100..107 -> bg = c.toString()
                        38, 48 -> {
                            val n = if (ps.getOrNull(k + 1) == 2) 4 else if (ps.getOrNull(k + 1) == 5) 2 else 0
                            val v = ps.subList(k + 1, minOf(ps.size, k + 1 + n)).joinToString(";")
                            if (c == 38) fg = v else bg = v
                            k += n
                        }
                    }
                    k++
                }
            }
            i = m.range.last + 1
            continue
        }
        if (!raw[i].isWhitespace()) return MuseLook(bold, faint, inverse, fg, bg)
        i++
    }
    return null
}

/** The quota warning on [text] (styled or plain), as a card, or null. */
internal fun detectMuseQuotaDialog(text: String): Prompt? {
    val rawRows = text.lines().map { it.replace("\r", "") }.takeLast(40)
    val rows = rawRows.map { it.replace(MUSE_ANY_ESC, "").trimEnd() }
    val options = rows.indices.mapNotNull { i -> MUSE_OPTION.matchEntire(rows[i])?.let { i to it } }
    if (options.size < 2) return null
    val indent = options.last().second.groupValues[1]
    // One list: every option at the same indent, "Keep going" last, and at least one offer of more turns.
    if (options.any { it.second.groupValues[1] != indent }) return null
    if (options.last().second.groupValues[2] != "Keep going") return null
    if (options.none { it.second.groupValues[3].isNotEmpty() }) return null
    if (options.map { it.second.groupValues[2] }.distinct().size != options.size) return null
    val first = options.first().first
    val last = options.last().first
    // Under "Keep going": nothing but a rule and Muse's footer line.
    if (rows.drop(last + 1).count { it.isNotBlank() } > 3) return null
    if (rows.drop(last + 1).firstOrNull { it.isNotBlank() }?.let { MUSE_RULE.containsMatchIn(it) } == false) return null
    // Between options, only their descriptions, indented further.
    val optionRows = options.map { it.first }.toSet()
    val notes = mutableMapOf<String, MutableList<String>>()
    var owner: String? = null
    for (i in first..last) {
        if (i in optionRows) { owner = MUSE_OPTION.matchEntire(rows[i])!!.groupValues[2]; continue }
        val r = rows[i]
        if (r.isBlank()) continue
        if (!r.startsWith(indent + " ")) return null
        notes.getOrPut(owner!!) { mutableListOf() } += r.trim()
    }
    fun label(m: MatchResult) = m.groupValues[2] + m.groupValues[3].replace(Regex("""\s+"""), " ").trimEnd()
    val choices = options.mapIndexed { n, (_, m) -> Choice(label(m), (n + 1).toString(), false) }
    // The title and the pace line above the list, when they show.
    val above = (first - 1 downTo maxOf(0, first - 6)).map { rows[it].trim() }
        .filter { it.isNotEmpty() && !MUSE_RULE.containsMatchIn(it) }.reversed()
    val title = above.firstOrNull { it.startsWith("You're using your quota fast") || it.startsWith("You’re using your quota fast") }
    val pace = above.firstOrNull { it.startsWith("At this pace your") }
    val heading = title ?: "Muse Code: you're using your quota fast"
    val blocks = mutableListOf(QBlock(QKind.HEADING, heading))
    pace?.let { blocks += QBlock(QKind.TEXT, it) }
    for ((_, m) in options) {
        val name = m.groupValues[2]
        val note = notes[name]?.joinToString(" ")?.replace(Regex("""\s+\("""), " (") ?: continue
        blocks += QBlock(QKind.TEXT, "$name: $note")
    }
    blocks += QBlock(QKind.TEXT, "Keep going closes this and changes nothing.")
    // The highlight, from the styles: the one option drawn with the most emphasis.
    val looks = options.map { museLook(rawRows[it.first]) }
    val selected = if (looks.any { it == null }) null else {
        val scores = looks.map { it!!.emphasis }
        val top = scores.maxOrNull()!!
        val distinctLooks = looks.distinct().size
        if (scores.count { it == top } == 1 && distinctLooks == 2) (scores.indexOf(top) + 1).toString() else null
    }
    return Prompt(heading, choices, selected, parts = QuestionParts(emptyList(), blocks), museQuota = true)
}
