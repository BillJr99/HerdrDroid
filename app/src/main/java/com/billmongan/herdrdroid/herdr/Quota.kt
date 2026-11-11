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
)

object QuotaProfiles {
    /**
     * Agents checked on the computer this was built against. pi and OpenCode have no usage limit
     * to read (OpenCode's /stats is a token history), so they have no profile and are skipped.
     */
    val builtIn = listOf(
        QuotaProfile(setOf("claude"), "/usage", listOf("esc"), 3_000),
        QuotaProfile(setOf("codex"), "/status", emptyList(), 2_500),
        QuotaProfile(setOf("copilot"), "/usage", emptyList(), 3_000),
        QuotaProfile(setOf("grok"), "/usage", listOf("esc"), 3_000),
        QuotaProfile(setOf("agy", "antigravity"), "/usage", listOf("esc"), 3_500, bareMeansLeft = true),
        QuotaProfile(setOf("hermes"), "/usage", emptyList(), 2_500),
    )

    /** The key quota readings are filed under, shared by every pane running that agent. */
    fun key(agent: String?): String? = forAgent(agent)?.agents?.first()

    fun forAgent(agent: String?): QuotaProfile? {
        val a = agent?.lowercase()?.trim() ?: return null
        return builtIn.firstOrNull { a in it.agents }
    }
}

private val BOX = "│┃|┌┐└┘╭╮╰╯├┤═║"
private val BAR = Regex("[█░▓▒■□▤▦▩▏▎▍▌▋▊▉━]")
private val PERCENT = Regex("""(\d{1,3}(?:\.\d+)?)\s*%""")
private val RESET_INLINE = Regex("""resets?:?\s+(?:in\s+)?([^)·│┃]+)""", RegexOption.IGNORE_CASE)

private fun clean(line: String) = line.trim().trim { it in BOX || it.isWhitespace() }

/**
 * Reads usage limits out of an agent's screen. It looks for lines with a percentage and either a
 * bar or the words used, left or remaining, takes the label from the same line or the line above,
 * and a reset time from the same line or the next two. "Left" and "remaining" are turned into
 * percent used. Agents that group limits under an all-capitals heading keep that heading.
 */
fun parseQuota(text: String, bareMeansLeft: Boolean = false): List<QuotaItem> {
    val lines = text.lines().map(::clean)
    val found = linkedMapOf<String, QuotaItem>()
    var section: String? = null
    for ((i, line) in lines.withIndex()) {
        if (line.length in 4..40 && line.any { it.isLetter() } && line == line.uppercase() &&
            line.all { it.isLetterOrDigit() || it == ' ' || it in "&/-." }
        ) {
            section = line
            continue
        }
        val m = PERCENT.find(line) ?: continue
        val hasBar = BAR.containsMatchIn(line)
        val after = line.substring(m.range.last + 1).trim().lowercase()
        val word = after.substringBefore(' ')
        if (!hasBar && word !in setOf("used", "left", "remaining")) continue

        val before = line.substring(0, m.range.first).let { b ->
            val cut = b.indexOfFirst { BAR.matches(it.toString()) || it == '[' }
            (if (cut >= 0) b.substring(0, cut) else b).trim().trimEnd(':').trim()
        }
        val label = before.ifBlank {
            (i - 1 downTo maxOf(0, i - 2)).map { lines[it] }
                .firstOrNull { it.isNotBlank() && !PERCENT.containsMatchIn(it) && !BAR.containsMatchIn(it) }
                ?.trimEnd(':')
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

        val reset = RESET_INLINE.find(line)?.groupValues?.get(1)?.trim()
            ?: (i + 1..minOf(lines.lastIndex, i + 2)).map { lines[it] }
                .firstOrNull { it.startsWith("resets", true) || it.contains("· resets", true) }
                ?.let { RESET_INLINE.find(it)?.groupValues?.get(1)?.trim() }

        val sec = section
        val full = if (sec != null && !label.contains(sec, true)) "${titleCase(sec)} · $label" else label
        found[full] = QuotaItem(full, used, reset?.substringBefore(" (")?.trimEnd('.', ' ', ',')?.ifBlank { null })
    }
    return found.values.toList()
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
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
