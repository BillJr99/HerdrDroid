package com.billmongan.herdrdroid.herdr

/**
 * Claude Code's /btw side question (2.1.4, Bill: "if I do something like /btw in an agent pane that
 * supports it like Claude I think it opens the answer in some kind of pain so for her to Droid I'd
 * like it to close that pane and go back to regular view but also to read and render the BTW
 * response as well as the BTW query in chat in a nice native way").
 *
 * Layout, rendered by Claude Code 2.1.329 itself against a mock API in a throwaway container
 * (screens/claude/captured-2.1.329-btw): a row of "▔" across the screen, then "  /btw <question>",
 * a blank row, the answer indented four spaces, a blank row, and the hint row last. While the
 * answer is coming the body is one spinner row ("    ✢ Answering…") over "  Esc to close"; once
 * it is there the hint is "  c to copy · f to fork · Esc to close", with "↑/↓ to scroll · " in
 * front when the answer is taller than the panel, which then marks more above or below with a "↑"
 * or "↓" at the right edge. Esc closes it. The exchange is not written to Claude's log.
 */
data class BtwOverlay(
    val question: String,
    /** The answer rows in view, without the indent, the scroll marks or the blank rows around them. */
    val body: List<String>,
    /** The spinner row is showing: the answer hasn't come yet. */
    val answering: Boolean,
    /** A "↓" mark: more of the answer is below the panel. */
    val more: Boolean,
    /** A "↑" mark: the panel is scrolled down from the top of the answer. */
    val scrolled: Boolean,
) {
    val complete: Boolean get() = !answering
}

private val BTW_HINT = Regex("""^ {2}(?:↑/↓ to scroll · )?(?:c to copy · f to fork · )?Esc to close$""")
private val BTW_QUERY = Regex("""^ {2}/btw (\S.*)$""")
private val BTW_RULE = Regex("""^▔{20,}$""")
private val BTW_SPINNER = Regex("""^ {4}\S Answering…$""")
private val BTW_MARK = Regex("""\s+([↑↓])$""")

/** Claude's /btw panel on [screen], or null when the screen doesn't end with one. */
fun claudeBtwOverlay(screen: String): BtwOverlay? {
    val rows = stripAnsi(screen).lines().map { it.trimEnd() }.takeLast(120)
    val hintAt = rows.indexOfLast { it.isNotBlank() }
    if (hintAt < 2 || !BTW_HINT.matches(rows[hintAt])) return null
    val queryAt = (hintAt - 1 downTo 1).firstOrNull { BTW_QUERY.matches(rows[it]) } ?: return null
    if (!BTW_RULE.matches(rows[queryAt - 1].trim())) return null
    val question = BTW_QUERY.find(rows[queryAt])!!.groupValues[1].trim()
    val inner = rows.subList(queryAt + 1, hintAt)
    // Nothing that belongs to the screen below the panel: Claude's composer and footer are hidden.
    if (inner.any { it.trimStart().startsWith("❯") || BTW_RULE.matches(it.trim()) || it.trim().startsWith("─────") }) return null
    var more = false
    var scrolled = false
    val body = inner.mapNotNull { row ->
        val t = row.trim()
        if (t == "↓") { more = true; return@mapNotNull null }
        if (t == "↑") { scrolled = true; return@mapNotNull null }
        val m = BTW_MARK.find(row)
        val text = if (m != null && row.length - m.range.first > 2) {
            if (m.groupValues[1] == "↓") more = true else scrolled = true
            row.substring(0, m.range.first)
        } else row
        text.removePrefix("    ").trimEnd()
    }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
    val answering = inner.any { BTW_SPINNER.matches(it) }
    if (answering && !rows[hintAt].trim().equals("Esc to close")) return null
    return BtwOverlay(question, if (answering) emptyList() else body, answering, more, scrolled)
}

/** The /btw command [text] sent to [agent], with its question; null for anything else. */
fun btwQuestion(agent: String?, text: String): String? {
    if (agent?.let(::canonicalAgent) != "claude") return null
    val m = Regex("""^/btw\s+(\S[\s\S]*)$""").find(text.trim()) ?: return null
    return m.groupValues[1].trim()
}

/**
 * The answer rows of a scrolled panel joined into one answer (2.1.4): [next] (a later view, scrolled
 * down) is added after [acc] from the first row the two views don't share. The longest run of rows
 * that ends [acc] and starts [next] is taken as the overlap, and blank rows alone never count as one.
 */
fun mergeBtwRows(acc: List<String>, next: List<String>): List<String> {
    if (acc.isEmpty()) return next
    for (k in minOf(acc.size, next.size) downTo 1) {
        val tail = acc.subList(acc.size - k, acc.size)
        if (tail == next.subList(0, k) && tail.any { it.isNotBlank() }) return acc + next.drop(k)
    }
    return acc + listOf("") + next
}

/**
 * The answer as Markdown (2.1.4): Claude wraps each paragraph to the terminal's width, so the rows of
 * one paragraph are joined into one line; list items, headings and code fences start lines of their
 * own, and blank rows stay paragraph breaks.
 */
fun btwAnswerMarkdown(rows: List<String>): String {
    val out = mutableListOf<String>()
    var inCode = false
    val starts = Regex("""^(?:[-*•] |\d+[.)] |#{1,6} |> |\|)""")
    for (raw in rows) {
        val row = raw.trimEnd()
        val t = row.trim()
        if (t.startsWith("```")) { inCode = !inCode; out += t; continue }
        if (inCode) { out += row; continue }
        when {
            t.isEmpty() -> if (out.lastOrNull()?.isNotEmpty() == true) out += ""
            out.isEmpty() || out.last().isEmpty() || starts.containsMatchIn(t) || out.last().startsWith("```") -> out += t.replaceFirst(Regex("""^• """), "- ")
            else -> out[out.size - 1] = out.last() + " " + t
        }
    }
    return out.dropLastWhile { it.isEmpty() }.joinToString("\n")
}

/**
 * Reading a /btw panel to the end (2.1.4). It waits until the answer has come and two reads in a row
 * show the same panel ([stable]); then, when more is below and [scrollDown] is given (only for a
 * panel the app opened), it scrolls one step at a time and joins the views until nothing is below.
 * Bounded by [maxReads] and [maxScrolls]. Null when no panel was found at all.
 */
data class BtwRead(val question: String, val rows: List<String>, val complete: Boolean, val more: Boolean)

suspend fun readBtwToEnd(
    read: suspend () -> BtwOverlay?,
    settle: suspend () -> Unit,
    scrollDown: (suspend (BtwOverlay) -> Boolean)? = null,
    maxReads: Int = 120,
    maxScrolls: Int = 80,
): BtwRead? {
    var last: BtwOverlay? = null
    var seen: BtwOverlay? = null
    var reads = 0
    while (reads < maxReads) {
        reads++
        val now = read()
        if (now == null) {
            // Not drawn yet: give it a few reads to appear.
            if (seen == null && reads < 8) { settle(); continue }
            return seen?.let { BtwRead(it.question, it.body, false, it.more) }
        }
        seen = now
        if (now.complete && now == last) break
        last = now
        settle()
    }
    val first = seen ?: return null
    if (!first.complete || first != last) return BtwRead(first.question, first.body, false, first.more)
    var rows = first.body
    var view = first
    var scrolls = 0
    while (view.more && scrollDown != null && scrolls < maxScrolls) {
        scrolls++
        if (!scrollDown(view)) break
        settle()
        val next = read() ?: break
        if (next.question != first.question || !next.complete) break
        rows = mergeBtwRows(rows, next.body)
        view = next
    }
    return BtwRead(first.question, rows, true, view.more)
}
