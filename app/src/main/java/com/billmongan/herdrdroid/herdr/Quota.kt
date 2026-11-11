package com.billmongan.herdrdroid.herdr

/** One usage limit, normalized to the percent used. */
data class QuotaItem(val label: String, val usedPercent: Double, val resets: String?)

/** What one agent reported, and when. */
data class QuotaReading(val at: Long, val items: List<QuotaItem>) {
    /** The limit closest to running out, which is the one worth showing on a card. */
    val tightest: QuotaItem? get() = items.maxByOrNull { it.usedPercent }
}

/**
 * How one kind of agent reports its usage limits without a model call: a slash command its own
 * program answers, and the keys that close the answer afterward. Profiles are chosen by the agent
 * herdr reports for a pane, never by pane id, so a pane that changes agents gets the right one.
 */
data class QuotaProfile(
    val agents: Set<String>,
    val command: String,
    val closeKeys: List<String>,
    val waitMillis: Long,
    /** A percentage with no "used" or "left" beside it means the amount left (Antigravity's bars). */
    val bareMeansLeft: Boolean = false,
    /**
     * The agent opens its usage screen even while it is working, without interrupting the work
     * (checked for Claude Code). Other agents are asked only when idle or done.
     */
    val whileWorking: Boolean = false,
    /**
     * Text the agent's usage screen shows while it is open. The close keys are sent only when it
     * is on screen, so an Esc can never reach the agent itself and interrupt it.
     */
    val openMarker: Regex? = null,
    /** A command that shows the active model when the agent's footer does not (Claude's /status). */
    val modelCommand: String? = null,
    val modelPattern: Regex? = null,
    /**
     * How often to check. Agents whose usage screen closes without a trace are checked more often;
     * agents that print their usage into the conversation stay at 30 minutes to keep it uncluttered.
     */
    val everyMinutes: Int = 30,
)

object QuotaProfiles {
    /**
     * Agents checked on the computer this was built against. pi and OpenCode have no usage limit
     * to read (OpenCode's /stats is a token history), so they have no profile and are skipped.
     */
    val builtIn = listOf(
        QuotaProfile(
            setOf("claude"), "/usage", listOf("esc"), 3_000,
            whileWorking = true,
            openMarker = Regex("Esc to cancel", RegexOption.IGNORE_CASE),
            modelCommand = "/status",
            modelPattern = Regex("""^\s*Model:\s+(.+?)\s*$"""),
            everyMinutes = 10,
        ),
        QuotaProfile(setOf("codex"), "/status", emptyList(), 2_500),
        QuotaProfile(setOf("copilot"), "/usage", emptyList(), 3_000),
        QuotaProfile(
            setOf("grok"), "/usage", listOf("esc"), 3_000,
            openMarker = Regex("Esc close", RegexOption.IGNORE_CASE), everyMinutes = 15,
        ),
        QuotaProfile(
            setOf("agy", "antigravity"), "/usage", listOf("esc"), 3_500,
            bareMeansLeft = true, openMarker = Regex("esc Close", RegexOption.IGNORE_CASE), everyMinutes = 15,
        ),
        QuotaProfile(setOf("hermes"), "/usage", emptyList(), 2_500),
    )

    /** The key quota readings are filed under, shared by every pane running that agent. */
    fun key(agent: String?): String? = forAgent(agent)?.agents?.first()

    fun forAgent(agent: String?): QuotaProfile? {
        val a = agent?.lowercase()?.trim() ?: return null
        return builtIn.firstOrNull { a in it.agents }
    }
}

private const val SEP = "│┃"
private val BOX = "│┃|┌┐└┘╭╮╰╯├┤═║"
private val BAR = Regex("[█░▓▒■□▤▦▩▏▎▍▌▋▊▉━]")
private val PERCENT = Regex("""(\d{1,3}(?:\.\d+)?)\s*%""")
private val RESET_INLINE = Regex("""resets?:?\s+(?:in\s+)?([^)·•│┃]+)""", RegexOption.IGNORE_CASE)

private fun clean(line: String) = line.trim().trim { it in BOX || it.isWhitespace() }

/** True for a status bar such as Hermes's footer, which packs many fields between separators. */
private fun isStatusBar(line: String) = line.count { it in SEP } >= 4

/**
 * Reads usage limits out of an agent's screen. It looks for lines with a percentage and either a
 * bar or the words used, left or remaining, takes the label from the same line or the line above,
 * and a reset time from the same line or the next two. "Left" and "remaining" are turned into
 * percent used. Agents that group limits under an all-capitals heading keep that heading.
 *
 * Some agents draw their usage box over the conversation (Grok), so a screen line can hold the
 * conversation on the left and the box on the right. Each percentage is read only within the
 * column it sits in, between the box borders around it, and the label and reset lines above and
 * below are cut to the same column.
 */
fun parseQuota(text: String, bareMeansLeft: Boolean = false): List<QuotaItem> {
    val raw = text.lines()
    val found = linkedMapOf<String, QuotaItem>()
    var section: String? = null
    for ((i, line) in raw.withIndex()) {
        val whole = clean(line)
        if (line.none { it in SEP } && whole.length in 4..40 && whole.any { it.isLetter() } &&
            whole == whole.uppercase() && whole.all { it.isLetterOrDigit() || it == ' ' || it in "&/-." }
        ) {
            section = whole
            continue
        }
        if (isStatusBar(line)) continue
        // Each column of the line is read once, from its first percentage.
        val seenColumns = mutableSetOf<Int>()
        for (first in PERCENT.findAll(line)) {
        // The column around the percentage: from the border on its left to the border on its right.
        val left = line.substring(0, first.range.first).indexOfLast { it in SEP }
        if (!seenColumns.add(left)) continue
        val right = line.indexOfAny(SEP.toCharArray(), first.range.last + 1).let { if (it < 0) Int.MAX_VALUE else it }
        fun column(s: String): String {
            if (left + 1 >= s.length) return ""
            return clean(s.substring(left + 1, minOf(right, s.length)))
        }

        val seg = column(line)
        val m = PERCENT.find(seg) ?: continue
        if (m.range.first > 0 && seg[m.range.first - 1] == '~') continue
        val hasBar = BAR.containsMatchIn(seg)
        val after = seg.substring(m.range.last + 1).trim().lowercase()
        val word = after.substringBefore(' ').trim('(', ')', ',', '.')
        if (!hasBar && word !in setOf("used", "left", "remaining")) continue

        val before = seg.substring(0, m.range.first).let { b ->
            val cut = b.indexOfFirst { BAR.matches(it.toString()) || it == '[' }
            (if (cut >= 0) b.substring(0, cut) else b).trim().trimEnd(':').trim()
        }
        val label = before.ifBlank {
            (i - 1 downTo maxOf(0, i - 2)).map { column(raw[it]) }
                .firstOrNull { it.isNotBlank() && !PERCENT.containsMatchIn(it) && !BAR.containsMatchIn(it) }
                ?.trimEnd(':')
                ?.takeIf { it.length <= 48 && !it.endsWith(".") }
        } ?: continue
        // Context-window gauges are not usage limits.
        if (label.length > 60 || label.contains("context", true)) continue

        val pct = m.groupValues[1].toDoubleOrNull() ?: continue
        val remaining = when (word) {
            "left", "remaining" -> true
            "used" -> false
            else -> label.contains("remaining", true) || label.contains(" left", true) || bareMeansLeft
        }
        val used = (if (remaining) 100.0 - pct else pct).coerceIn(0.0, 100.0)

        val reset = RESET_INLINE.find(seg)?.groupValues?.get(1)?.trim()
            ?: (i + 1..minOf(raw.lastIndex, i + 2)).map { column(raw[it]) }
                .firstOrNull { it.startsWith("resets", true) || it.contains("· resets", true) }
                ?.let { RESET_INLINE.find(it)?.groupValues?.get(1)?.trim() }

        val sec = section
        val full = if (sec != null && !label.contains(sec, true)) "${titleCase(sec)} · $label" else label
        found[full] = QuotaItem(full, used, reset?.substringBefore(" (")?.trimEnd('.', ' ', ',')?.ifBlank { null })
        }
    }
    return found.values.toList()
}

/**
 * A short name for a card: "Current week (all models)" becomes "Week (all)", "Claude and GPT
 * Models · Five Hour Limit Remaining" becomes "Claude/GPT 5h", "Weekly limit (SuperGrok)" becomes
 * "Week (SuperGrok)".
 */
fun shortLabel(label: String): String {
    val parts = label.split(" · ")
    val group = if (parts.size > 1) parts[0] else null
    var l = parts.last()
    l = l.replace(Regex("(?i)five[ -]hour|5[ -]hour"), "5h")
        .replace(Regex("(?i)\\bweekly\\b|\\bweek\\b"), "Week")
        .replace(Regex("(?i)\\b(current|limit|remaining|left)\\b"), "")
        .replace(Regex("(?i)\\(all models\\)"), "(all)")
    if (group != null) l = l.replace(Regex("(?i)" + Regex.escape(group)), "")
    l = l.replace(Regex("\\s+"), " ").trim()
    val g = group?.replace(Regex("(?i)\\s*models?\\b"), "")?.replace(Regex("(?i)\\s+and\\s+"), "/")?.trim()
    val out = listOfNotNull(g?.ifBlank { null }, l.ifBlank { null }).joinToString(" ")
    return out.ifBlank { label }
}

private val SMALL = setOf("and", "or", "of", "the", "for", "in", "to")

/** "CLAUDE AND GPT MODELS" to "Claude and GPT Models": short words stay as acronyms. */
private fun titleCase(s: String) = s.lowercase().split(' ').joinToString(" ") { w ->
    when {
        w in SMALL -> w
        w.length <= 3 -> w.uppercase()
        else -> w.replaceFirstChar { it.uppercase() }
    }
}

/** "4 min ago", "2 h ago". */
fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

/**
 * The active model, read from the footer each agent draws at the bottom of its screen. Claude Code
 * has no model in its footer, so its model comes from /status during the usage check instead.
 */
fun footerModel(agent: String?, screen: String): String? {
    val lines = screen.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(12)
    fun lastMatch(r: Regex) = lines.asReversed().firstNotNullOfOrNull { r.find(it)?.groupValues?.get(1)?.trim() }
    val found = when (agent?.lowercase()) {
        // "GPT-6.1-Sol default · ~/agents/codex"
        "codex" -> lastMatch(Regex("""^\s*(\S[^·]*?)\s+·\s+~"""))
            ?.replace(Regex("""\s+(default|minimal|low|medium|high|xhigh)$"""), " · $1")
        // "? for shortcuts            Gemini 4 Argon · medium"
        "agy", "antigravity" -> lastMatch(Regex("""\S\s{3,}(\S[^│]*·\s*\S+)\s*$"""))
        // "╰────── Grok 4.7 (high) ─╯"
        "grok" -> lastMatch(Regex("""─\s+([^─│]+?)\s+─+╯"""))
        // "☤ gpt-6.1-sol │ ~124K/500K │ ..."
        "hermes" -> lastMatch(Regex("""☤\s+([^│]+?)\s+│"""))
        // "... / commands · tab next tab        GPT-5.6 Terra"
        "copilot" -> lines.lastOrNull { it.contains("/ commands") }
            ?.let { Regex("""\s{3,}(\S.*?)\s*$""").find(it)?.groupValues?.get(1) }
        // "┃  Build · Big Pickle OpenCode"
        "opencode" -> lastMatch(Regex("""┃\s+(?:Build|Plan|\w+)\s+·\s+(.+?)\s*$"""))
        // "0.0%/128k (auto)          (compat-llmproxy) llmproxy/flagship__free"
        "pi" -> lastMatch(Regex("""\)\s+(\S+/\S+)\s*$""")) ?: lastMatch(Regex("""%/\S+.*\s{3,}(\S+)\s*$"""))
        else -> null
    }
    return found?.takeIf { it.length in 2..48 }
}
