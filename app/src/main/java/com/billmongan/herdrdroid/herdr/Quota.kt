package com.billmongan.herdrdroid.herdr

/** One usage limit, normalized to the percent used. */
data class QuotaItem(val label: String, val usedPercent: Double, val resets: String?)

/** The model and reasoning effort read from an agent's own screen; either may be unknown. */
data class PaneInfo(val model: String?, val effort: String?)

/**
 * Claude Code's /model picker: the current choice carries a ✔ ("1. Default (recommended) ✔ Fable
 * 5.2", or "2. Opus 5.5 ✔ For complex work…"), and the effort sits below ("◐ Medium effort").
 */
fun claudeModelScreen(screen: String): PaneInfo {
    val lines = screen.lines()
    val picked = lines.lastNotNullOfOrNull { Regex("""^\s*[❯>]?\s*\d+\.\s+(.+?)\s*✔\s*(.*?)\s*$""").find(it) }
    val model = picked?.let { m ->
        val name = m.groupValues[1].trim()
        val rest = m.groupValues[2].trim()
        if (name.startsWith("Default", true) && rest.isNotBlank() && rest.length <= 24) rest else name
    }
    val effort = lines.lastNotNullOfOrNull { Regex("""(?i)\b(low|medium|high|xhigh|max)\s+effort\b""").find(it) }
        ?.groupValues?.get(1)?.lowercase()
    return PaneInfo(model, effort)
}

private inline fun <T, R : Any> List<T>.lastNotNullOfOrNull(f: (T) -> R?): R? {
    for (i in indices.reversed()) f(this[i])?.let { return it }
    return null
}

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
    /**
     * A command that shows what the footer does not: Claude's /model (model and effort) or Hermes's
     * /reasoning (effort). [parseInfo] reads its screen.
     */
    val infoCommand: String? = null,
    val parseInfo: ((String) -> PaneInfo)? = null,
    /**
     * How often to check. Agents whose usage screen closes without a trace are checked more often;
     * agents that print their usage into the conversation stay at 30 minutes to keep it uncluttered.
     */
    val everyMinutes: Int = 30,
    /**
     * A shell command that reports usage without touching the pane (Oh My Pi: `omp usage --json`).
     * When set, [command] is not typed anywhere and [parseCli] reads the command's output.
     */
    val cliCommand: String? = null,
    val parseCli: ((String) -> List<QuotaItem>)? = null,
)

/**
 * `omp usage --json`: `{reports: [{provider, limits: [{label, window: {label, resetLabel, resetsAt},
 * amount: {usedFraction, used, limit, remainingFraction}}]}]}`. Accounts signed in to Oh My Pi
 * itself report here; providers reached through pi-openai-compat (llmproxy) do not, so with none
 * signed in this is empty and the card simply shows no usage.
 */
fun ompUsage(json: String): List<QuotaItem> {
    val root = org.json.JSONObject(json.trim())
    val reports = root.optJSONArray("reports") ?: return emptyList()
    val out = mutableListOf<QuotaItem>()
    for (i in 0 until reports.length()) {
        val rep = reports.optJSONObject(i) ?: continue
        val provider = rep.optString("provider")
        val limits = rep.optJSONArray("limits") ?: continue
        for (j in 0 until limits.length()) {
            val l = limits.optJSONObject(j) ?: continue
            val a = l.optJSONObject("amount") ?: continue
            val used = when {
                a.has("usedFraction") -> a.optDouble("usedFraction") * 100
                a.has("remainingFraction") -> (1 - a.optDouble("remainingFraction")) * 100
                a.has("used") && a.optDouble("limit", 0.0) > 0 -> a.optDouble("used") / a.optDouble("limit") * 100
                else -> continue
            }
            if (used.isNaN()) continue
            val w = l.optJSONObject("window")
            val label = listOf(provider, l.optString("label").ifBlank { w?.optString("label").orEmpty() })
                .filter { it.isNotBlank() }.joinToString(" ")
            val resets = w?.optString("resetLabel")?.ifBlank { null }
                ?: w?.optLong("resetsAt", 0L)?.takeIf { it > 0 }?.let {
                    java.text.SimpleDateFormat("MMM d, h:mma", java.util.Locale.US).format(java.util.Date(it))
                }
            out += QuotaItem(label.ifBlank { "Usage" }, used.coerceIn(0.0, 100.0), resets)
        }
    }
    return out
}

/**
 * True when [screen] shows this agent's usage (or model) screen open: its close hint is visible
 * AND the screen has usage numbers, or the model/effort the info command shows. The close hint
 * alone is not enough, because a permission prompt can carry the same "Esc to cancel", and an
 * Esc there would answer it.
 */
fun QuotaProfile.screenOpen(screen: String): Boolean {
    val marker = openMarker ?: return false
    val tail = screen.lines().takeLast(40).joinToString("\n")
    if (!marker.containsMatchIn(tail)) return false
    if (parseQuota(tail, bareMeansLeft).isNotEmpty()) return true
    // The model screen counts only by its picked model (the ✔ line); "high effort" alone can
    // appear in ordinary conversation text.
    return parseInfo?.invoke(tail)?.model != null
}

object QuotaProfiles {
    /**
     * Agents checked on the computer this was built against. pi and OpenCode have no usage limit
     * to read (OpenCode's /stats is a token history), so they have no profile and are skipped.
     */
    val builtIn = listOf(
        QuotaProfile(
            // Not while working: Claude queues a command typed mid-turn and opens the screen only
            // when the turn ends, after the app has stopped watching, which left it stuck open
            // (2026-11-12). Idle panes only, like every other agent.
            // No /model: its picker could stay open when it was drawn differently than expected
            // (2026-11-12, twice). Claude's model now comes from its own session log
            // (Herdr.logModels), so nothing but /usage is ever typed into a Claude pane.
            setOf("claude"), "/usage", listOf("esc"), 3_000,
            openMarker = Regex("Esc to cancel", RegexOption.IGNORE_CASE),
            // Kept only to recognize a model picker left open (and close it); never typed.
            parseInfo = ::claudeModelScreen,
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
        QuotaProfile(
            setOf("omp"), "", emptyList(), 0,
            cliCommand = "omp usage --json", parseCli = ::ompUsage,
        ),
        QuotaProfile(
            setOf("hermes"), "/usage", emptyList(), 2_500,
            infoCommand = "/reasoning",
            parseInfo = { screen -> PaneInfo(null, Regex("""Reasoning effort:\s+(\S+)""").findAll(screen).lastOrNull()?.groupValues?.get(1)) },
        ),
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
/** "claude-opus-5-5" as "Opus 5.5"; anything else as it is. */
fun prettyClaudeModel(id: String): String {
    val m = Regex("""^claude-(opus|sonnet|haiku|fable)-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?(\[1m])?$""").find(id.trim()) ?: return id.trim()
    val (family, major, minor, big) = m.destructured
    return family.replaceFirstChar { it.uppercase() } + " " + major + (if (minor.isNotEmpty()) ".$minor" else "") + if (big.isNotEmpty()) " (1M)" else ""
}

fun footerModel(agent: String?, screen: String): String? {
    val lines = screen.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(12)
    fun lastMatch(r: Regex) = lines.asReversed().firstNotNullOfOrNull { r.find(it)?.groupValues?.get(1)?.trim() }
    val found = when (agent?.lowercase()) {
        // "GPT-6.1-Sol default · ~/agents/codex"
        "codex" -> lastMatch(Regex("""^\s*(\S[^·]*?)\s+·\s+~"""))
            ?.replace(Regex("""\s+(default|minimal|low|medium|high|xhigh)$"""), " · $1")
        // "? for shortcuts            Gemini 4 Argon · medium"
        // The text on its left changes, and is blank while a dialog covers the screen.
        "agy", "antigravity" -> lastMatch(Regex("""^(?:.*\S\s{3,}|\s*)([^\s│][^│·]*·\s*(?:minimal|low|medium|high|xhigh|max))\s*$"""))
            // A footer with the model alone: "? for shortcuts            Gemini 4 Argon"
            ?: lastMatch(Regex("""for shortcuts\s{3,}(\S.*?)\s*$"""))
        // "╰────── Grok 4.7 (high) ─╯"
        "grok" -> lastMatch(Regex("""─\s+([^─│]+?)\s+─+╯"""))
        // "☤ gpt-6.1-sol │ ~124K/500K │ ..." (older) or "☤ gpt-6.1-sol · ctx -- · [░░░] -- · ..." (2026-11-12)
        "hermes" -> lastMatch(Regex("""☤\s+([^│·\s][^│·]*?)\s+[│·]"""))
        // "... / commands · tab next tab        GPT-5.6 Terra"
        "copilot" -> lines.lastOrNull { it.contains("/ commands") }
            ?.let { Regex("""\s{3,}(\S.*?)\s*$""").find(it)?.groupValues?.get(1) }
        // "┃  Build · Big Pickle OpenCode"
        "opencode" -> lastMatch(Regex("""┃\s+(?:Build|Plan|\w+)\s+·\s+(.+?)\s*$"""))
        // " π > ⬢ llmproxy/flagship > 📁 ~/agents/omp ▶───41%───"
        "omp" -> lastMatch(Regex("""⬢\s+(\S+)"""))
        // "0.0%/128k (auto)          (compat-llmproxy) llmproxy/flagship__free"
        "pi" -> lastMatch(Regex("""\)\s+(\S+/\S+)\s*$""")) ?: lastMatch(Regex("""%/\S+.*\s{3,}(\S+)\s*$"""))
        else -> null
    }
    return found?.takeIf { it.length in 2..48 }
}
