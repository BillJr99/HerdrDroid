package com.billmongan.herdrdroid.herdr

/**
 * 2.2.2 (Bill, 2026-11-22: "make sure that I can switch sessions for any agent that supports it"): the
 * input boxes of agents that draw no prompt glyph, found by their shape from live captures, so a menu
 * write (and a normal send) can see that nothing is typed there. Each reader returns "" only for a box
 * it positively finds empty, the text when it finds the box with text in it, and null when the bottom
 * of the screen isn't that box (a list, a dialog, a layout it doesn't know), which counts as not empty.
 */
internal object AgentComposers {
    /** What is typed in [agent]'s box, "" when it is empty, or null when this reader doesn't know the agent or can't find the box. */
    fun line(screen: String, agent: String?): String? = when (agent?.lowercase()) {
        "pi" -> pi(screen)
        "omp" -> omp(screen)
        "copilot" -> copilot(screen)
        else -> null
    }

    private fun rows(screen: String): List<String> =
        screen.lines().map { stripAnsi(it.replace("\r", "")).trimEnd() }.dropLastWhile { it.isBlank() }.takeLast(16)

    private val PLAIN_RULE = Regex("""^[─━]{20,}$""")

    /**
     * pi 1.6.0: its editor sits between two full-width rules, with its folder and its token line under
     * the bottom rule; the empty editor is one row holding only the cursor cell. Only the last rule pair
     * on screen with at most four rows under it counts; an autocomplete list under it doesn't.
     */
    internal fun pi(screen: String): String? {
        val r = rows(screen)
        val bottom = r.indices.lastOrNull { PLAIN_RULE.matches(r[it]) } ?: return null
        val under = r.drop(bottom + 1)
        if (under.count { it.isNotBlank() } !in 1..4 || under.any { PLAIN_RULE.matches(it.trim()) }) return null
        val top = (bottom - 1 downTo maxOf(0, bottom - 31)).firstOrNull { PLAIN_RULE.matches(r[it]) } ?: return null
        if (kotlin.math.abs(r[top].length - r[bottom].length) > 2 || bottom - top < 2) return null
        return r.subList(top + 1, bottom).joinToString("\n") { it.trim() }.trim()
    }

    private val COPILOT_TOP = Regex("""^╻▄{20,}$""")
    private val COPILOT_BOTTOM = Regex("""^╹▀{20,}$""")

    /**
     * GitHub Copilot CLI: its input box is a half-block frame, "╻▄▄▄" over "┃" rows over "╹▀▀▀", with
     * one status row ("← open sidebar · Interactive · Manual Approval · …") under it. Empty when every
     * row inside is blank after the "┃".
     */
    internal fun copilot(screen: String): String? {
        val r = rows(screen)
        val bottom = r.indices.lastOrNull { COPILOT_BOTTOM.matches(r[it].trim()) } ?: return null
        if (r.drop(bottom + 1).count { it.isNotBlank() } !in 1..3) return null
        val top = (bottom - 1 downTo maxOf(0, bottom - 31)).firstOrNull { COPILOT_TOP.matches(r[it].trim()) } ?: return null
        val inside = r.subList(top + 1, bottom)
        if (inside.isEmpty() || inside.any { !it.trimStart().startsWith("┃") }) return null
        return inside.joinToString("\n") { it.trimStart().removePrefix("┃").trim() }.trim()
    }

    /** Oh My Pi's hints right-aligned in an empty box (its "learned" tips): "shift+tab to change thinking effort", "← to see 2 running agents". */
    private val OMP_HINT = Regex("""^\s{2,}(?:\S+ to change thinking effort|\S+ to see \d+ running agents?)$""")

    /**
     * Oh My Pi 19.0.1's "band" composer: its status row (" π > ⬢ model > 📁 dir ▶───…") right above a
     * prompt row that starts "╰─ ", the text after the gutter, more rows indented under it, and nothing
     * under that. Empty when nothing follows the gutter but, on a plain read, one of its own hints far
     * to the right; on a styled read the hint is judged by its faint or italic style.
     */
    internal fun omp(screen: String): String? {
        val raw = screen.lines().map { it.replace("\r", "") }
        val r = raw.map { stripAnsi(it).trimEnd() }
        val last = r.indexOfLast { it.isNotBlank() }
        if (last < 0) return null
        val prompt = (last downTo maxOf(0, last - 30)).firstOrNull { r[it].startsWith("╰─") } ?: return null
        if (prompt < 1 || !r[prompt - 1].contains(" > ") || !r[prompt - 1].contains("⬢")) return null
        val more = r.subList(prompt + 1, last + 1)
        if (more.any { it.isNotBlank() && !it.startsWith("   ") }) return null
        val first = r[prompt].removePrefix("╰─")
        val styledHint = raw[prompt].contains('\u001B') && first.isNotBlank() && hintStyled(raw[prompt])
        val text = if (more.all { it.isBlank() } && (OMP_HINT.matches(first) && !raw[prompt].contains('\u001B') || styledHint)) ""
            else (listOf(first.trim()) + more.map { it.trim() }).joinToString("\n").trim()
        return text
    }

    /** Every visible character after the gutter is drawn faint, italic, or as the accent-coloured key of the hint followed by faint text. */
    private fun hintStyled(row: String): Boolean {
        val plain = stripAnsi(row).removePrefix("╰─").trim()
        if (!OMP_HINT.matches("  $plain")) return false
        // The label after the key is drawn dim and italic ("\u001B[2m\u001B[3m…"); typed text never is.
        return Regex("""\u001B\[[23]m[^\u001B]*to (?:change thinking effort|see)""").containsMatchIn(row)
    }
}
