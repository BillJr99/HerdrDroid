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
     * A hint shown by a modal usage screen. This is not sufficient to authorize any key.
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
    /**
     * Usage the agent always shows on its own screen (Mistral Vibe's footer gauge). When set, the
     * pane's screen is only read, nothing is typed, and the agent's status doesn't matter.
     */
    val screenUsage: ((String) -> List<QuotaItem>)? = null,
) {
    internal val hasPassiveSource: Boolean get() = (cliCommand != null && parseCli != null) || screenUsage != null
}

/**
 * Mistral Vibe's footer: "~/agents/vibe      usage ▏      0% · ctx ▎   9.81k/202k". The gauge is
 * the plan's usage; the context gauge after it is not a limit.
 */
fun vibeFooterUsage(screen: String): List<QuotaItem> {
    val line = screen.lines().lastOrNull { Regex("""\busage\s""").containsMatchIn(it) && it.contains("ctx") } ?: return emptyList()
    val m = Regex("""\busage\s*[▏▎▍▌▋▊▉█\s]*?(\d{1,3}(?:\.\d+)?)\s*%""").find(line) ?: return emptyList()
    val pct = m.groupValues[1].toDoubleOrNull() ?: return emptyList()
    return listOf(QuotaItem("Vibe usage", pct.coerceIn(0.0, 100.0), null))
}

/**
 * `muse usage --json` (Muse Code 1.4.20, 1.4.5): `{"allowance": {"tier", "window": {"usedPercent",
 * "resetsAtMs"}, "weekly": {...}}, "reason", "schemaVersion": 1}`. With no data yet ("reason":
 * "server_sent_no_usage") there is no allowance, and the card shows no usage.
 */
fun museUsage(json: String): List<QuotaItem> {
    val root = org.json.JSONObject(json.trim().lines().lastOrNull { it.trimStart().startsWith("{") } ?: return emptyList())
    val a = root.optJSONObject("allowance") ?: return emptyList()
    val out = mutableListOf<QuotaItem>()
    for ((key, label) in listOf("window" to "Muse window", "weekly" to "Muse weekly")) {
        val w = a.optJSONObject(key) ?: continue
        if (!w.has("usedPercent")) continue
        val used = w.optDouble("usedPercent")
        if (used.isNaN()) continue
        val resets = w.optLong("resetsAtMs", 0L).takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() }
        out += QuotaItem(label, used.coerceIn(0.0, 100.0), resets)
    }
    return out
}

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

/** Recognize the live Claude usage panel without treating a shared Esc hint as proof. */
internal fun claudeUsageOpen(screen: String): Boolean = claudeUsageFrame(screen) != null

private fun overlayLines(screen: String) = forPhone(screen).lines().takeLast(60)
    .map { it.trim().trim('│', '┃', '|').trim() }.filter { it.isNotEmpty() }

private fun claudeUsageFrame(screen: String): List<String>? {
    val lines = overlayLines(screen)
    val footer = lines.indexOfLast { CLAUDE_USAGE_CLOSE.containsMatchIn(it) }
    if (footer < 0 || lines.drop(footer + 1).any { it.any(Char::isLetterOrDigit) || CLAUDE_INPUT.containsMatchIn(it) }) return null
    val top = lines.take(footer).indexOfLast { CLAUDE_USAGE_SECTION.matches(it) }
    if (top < 0) return null
    val body = lines.subList(top + 1, footer)
    val header = lines.take(top).indexOfLast { CLAUDE_USAGE_HEADER.matches(it) }
    val frame = lines.subList(if (header >= 0) header else top, footer + 1)
    if (body.any { !CLAUDE_USAGE_ROW.matches(it) } || frame.dropLast(1).any { CLAUDE_USAGE_CLOSE.matches(it) || CLAUDE_INPUT.containsMatchIn(it) || CLAUDE_OPTION.containsMatchIn(it) || it.endsWith("?") }) return null
    if (header < 0 && body.none { CLAUDE_USAGE_DETAIL.containsMatchIn(it) }) return null
    return frame
}

private val CLAUDE_USAGE_CLOSE = Regex("""^(?:press )?esc(?: to (?:cancel|close|exit|dismiss|go back))?(?:\s*(?:[·•]|$).*)?$""", RegexOption.IGNORE_CASE)
private val CLAUDE_USAGE_HEADER = Regex("""(?:Settings\s+)?Status\s+Config\s+Usage(?:\s+Stats)?""", RegexOption.IGNORE_CASE)
private val CLAUDE_USAGE_SECTION = Regex("""(?:Current session|Current week(?:\s+\([^)]*\))?|Usage credits|Extra usage|Usage limits)(?:\s+·\s+Resets .+)?""", RegexOption.IGNORE_CASE)
private val CLAUDE_USAGE_ROW = Regex("""(?:[\p{So}\p{Pd}▀-▟↓↑─━\s]*\d+(?:\.\d+)?%\s+(?:used|left)|Resets\s+[^?]+|Loading(?:\.{3}|…)?|\([^()]+\)|[▀-▟↓↑─━\s]+)""", RegexOption.IGNORE_CASE)
private val CLAUDE_USAGE_DETAIL = Regex("""\d+(?:\.\d+)?%\s+(?:used|left)|\bresets\b|\bloading\b""", RegexOption.IGNORE_CASE)
private val CLAUDE_INPUT = Regex("""^[❯›>]""")
private val CLAUDE_OPTION = Regex("""^\d+[.)]\s""")

/**
 * Only known live layouts may authorize an explicit Escape. For Claude that is its usage panel, or
 * (2.1.4) its /btw side-question panel ([claudeBtwOverlay]), which Esc closes the same way.
 */
internal fun dismissibleUsageFrame(agent: String?, visible: String): List<String>? =
    exactUsageFrame(agent, visible) ?: infoPanelOn(agent, visible)?.takeIf { it.known }?.frame

/**
 * The exact layouts known in 2.1.4 and 2.1.5. 2.2.1: kept as positive evidence for [infoPanelOn],
 * which also recognizes the other informational panels structurally, for every agent.
 */
internal fun exactUsageFrame(agent: String?, visible: String): List<String>? = when (agent) {
    "claude" -> claudeUsageFrame(visible)?.takeIf { frame ->
        CLAUDE_USAGE_HEADER.matches(frame.first()) && frame.drop(1).dropLast(1).all {
            CLAUDE_USAGE_SECTION.matches(it) || CLAUDE_USAGE_ROW.matches(it) ||
                it == "Session" || CLAUDE_USAGE_COST.matches(it)
        }
    } ?: claudeBtwOverlay(visible)?.let { listOf("/btw " + it.question) + it.body }
    "kiro" -> {
        val lines = overlayLines(visible)
        val top = lines.indexOfLast { it == "/usage" }
        lines.drop(top.coerceAtLeast(0)).takeIf { top >= 0 && KIRO_USAGE.matches(it.joinToString("\n")) }
    }
    "grok" -> grokUsageFrame(visible)
    "agy" -> agyUsageFrame(visible)
    else -> null
}

/**
 * Grok 1.0.92's session/usage dialog (2.1.4): a light box drawn over the chat whose last row is
 * "Tab switch  |  ↑/↓ scroll  |  c copy session ID  |  Esc close", with Grok's own composer box and
 * shortcut hint still drawn under it. The box may sit to the right of chat text, so its rows are
 * found by the column of the footer's left border. Below the box only blank rows, Grok's composer
 * box (empty: a draft is never under it) and its "Shift+Tab:mode" hint may follow.
 */
private fun grokUsageFrame(visible: String): List<String>? {
    val rows = stripAnsi(visible).lines().map { it.trimEnd() }.takeLast(60)
    val footerAt = rows.indexOfLast { GROK_USAGE_FOOTER.containsMatchIn(it) }
    if (footerAt < 0) return null
    val footerRow = rows[footerAt]
    val hit = GROK_USAGE_FOOTER.find(footerRow) ?: return null
    val col = footerRow.lastIndexOf('│', hit.range.first)
    if (col < 0 || footerRow.substring(col + 1, hit.range.first).isNotBlank()) return null
    val right = footerRow.indexOf('│', hit.range.last + 1)
    if (right < 0 || footerRow.substring(hit.range.last + 1, right).isNotBlank()) return null
    fun at(row: String, c: Char) = row.length > col && row[col] == c
    val bottomAt = (footerAt + 1 until rows.size).firstOrNull { rows[it].isNotBlank() } ?: return null
    val bottom = rows[bottomAt]
    if (!at(bottom, '└') || bottom.length <= right || bottom[right] != '┘' || bottom.substring(col + 1, right).any { it != '─' }) return null
    var topAt = -1
    for (i in footerAt - 1 downTo 0) {
        val r = rows[i]
        if (at(r, '┌')) { topAt = i; break }
        if (!at(r, '│') || r.length <= right || r[right] != '│') return null
    }
    if (topAt < 0 || rows[topAt].length <= right || rows[topAt][right] != '┐') return null
    // Nothing but Grok's own chrome under the box: an empty composer and its hint line.
    val under = rows.drop(bottomAt + 1).map { it.trim() }.filter { it.isNotEmpty() }
    if (!under.all { it.startsWith('╭') || it.startsWith('╰') || GROK_EMPTY_COMPOSER.matches(it) || it.startsWith("Shift+Tab:mode") }) return null
    return rows.subList(topAt, bottomAt + 1).map { it.substring(col.coerceAtMost(it.length)).trim() }
}

private val GROK_USAGE_FOOTER = Regex("""Tab switch\s+[|│]\s+↑/↓ scroll\s+[|│]\s+c copy session ID\s+[|│]\s+Esc close""")
private val GROK_EMPTY_COMPOSER = Regex("""^│\s*❯\s*│$""")

/**
 * Antigravity's usage screen (2.1.4): limits read as bare percentages over its scroll footer
 * "↑/↓ Scroll · pgup/pgdown Page · ctrl+end Bottom · ctrl+home Top · esc Close", with at most its
 * model footer under it (the layout in QuotaTest.antigravityFooterUnderDialog).
 */
private fun agyUsageFrame(visible: String): List<String>? {
    val rows = stripAnsi(visible).lines().map { it.trim() }.takeLast(60)
    val footerAt = rows.indexOfLast { it == AGY_USAGE_FOOTER }
    if (footerAt < 0) return null
    val under = rows.drop(footerAt + 1).filter { it.isNotEmpty() }
    if (under.size > 1 || under.any { !AGY_MODEL_FOOTER.matches(it) }) return null
    val body = rows.subList(0, footerAt)
    if (parseQuota(body.joinToString("\n"), bareMeansLeft = true).isEmpty()) return null
    return rows.subList((footerAt - 20).coerceAtLeast(0), footerAt + 1)
}

private const val AGY_USAGE_FOOTER = "↑/↓ Scroll · pgup/pgdown Page · ctrl+end Bottom · ctrl+home Top · esc Close"
private val AGY_MODEL_FOOTER = Regex("""^[^│·]+·\s*(?:minimal|low|medium|high|xhigh|max)$""")

/**
 * The usage dialogs the app may open and must close (2.1.4): the agents whose usage command in
 * [QuotaProfiles] draws a modal screen that Esc closes. Codex's /status, Copilot's and Hermes's
 * /usage print into the conversation and leave nothing open.
 */
internal val USAGE_DIALOG_AGENTS = setOf("claude", "kiro", "grok", "agy")

/** True when [text], sent to [agent], is that agent's usage command, which opens its usage dialog. */
internal fun opensUsageDialog(agent: String?, text: String): Boolean {
    val a = agent?.let(::canonicalAgent) ?: return false
    if (a !in USAGE_DIALOG_AGENTS) return false
    val p = QuotaProfiles.forAgent(a) ?: return false
    return p.closeKeys.isNotEmpty() && text.trim().substringBefore(' ').equals(p.command, ignoreCase = true)
}

/**
 * An agent's usage dialog is drawn on [visible], in its known layout or (Grok, Antigravity) by its
 * close hint (2.1.4). For holding sends and hiding the draft card only, never for sending a key.
 */
internal fun usageScreenShown(agent: String?, visible: String): Boolean =
    dismissibleUsageFrame(agent, visible) != null || (agent in setOf("grok", "agy") && usageDialogHint(agent, visible)) ||
        infoPanelOn(agent, visible) != null

/**
 * A loose sign that an agent's usage dialog is still drawn, used only to refuse to call it closed
 * (never to send a key): its own close hint is on screen even though the strict layout didn't match.
 */
internal fun usageDialogHint(agent: String?, visible: String): Boolean {
    // 2.2.1: any informational panel that may be open, for every agent ([infoPanelOn]).
    if (infoPanelOn(agent, visible) != null) return true
    val tail = stripAnsi(visible).lines().takeLast(40).joinToString("\n")
    return when (agent) {
        "grok" -> tail.contains("c copy session ID") && tail.contains("Esc close")
        "agy" -> tail.contains("esc Close")
        "kiro" -> tail.contains("esc to close", ignoreCase = true) && tail.contains("/usage")
        "claude" -> claudeUsageOpen(tail) || tail.lines().lastOrNull { it.isNotBlank() }?.trim()?.endsWith("Esc to close") == true &&
            tail.lines().any { it.startsWith("  /btw ") }
        else -> false
    }
}

private val CLAUDE_USAGE_COST = Regex("""Total cost:\s+\$[\d,.]+""")
/**
 * Kiro's /usage panel, top to bottom. 2.1.5: the "This session: N credits" row is not always drawn
 * (a 2026-11-22 pane showed none), and without it the panel wasn't known, so it was never closed.
 */
private val KIRO_USAGE = Regex("""/usage\n─+\n(?:This session: [\d.]+ credits\n)?Estimated Usage \| resets on \d{4}-\d{2}-\d{2} \| [\w ]+\nCredits \([\d.]+ of [\d.]+ covered in plan\)\n[▀-▟ \t]+[\d.]+%\nTo manage your plan navigate to app\.kiro\.dev/account/usage\n─+\nesc to close[ \t]+Tab to switch to /context""", RegexOption.IGNORE_CASE)

/** A coarse read-only parser for usage or model screens. Not a predicate for sending keys. */
fun QuotaProfile.screenOpen(screen: String): Boolean {
    val marker = openMarker ?: return false
    val tail = screen.lines().takeLast(40).joinToString("\n")
    if (!marker.containsMatchIn(tail)) return false
    if ("claude" in agents && claudeUsageOpen(tail)) return true
    if ("claude" !in agents && parseQuota(tail, bareMeansLeft).isNotEmpty()) return true
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
            // Newer Claude Code versions word the close hint differently.
            openMarker = Regex("""esc to (cancel|close|exit|dismiss|go back)|press esc|esc ·""", RegexOption.IGNORE_CASE),
            // Kept to recognize a model picker; never typed or closed by a usage check.
            parseInfo = ::claudeModelScreen,
            everyMinutes = 10,
        ),
        QuotaProfile(setOf("codex"), "/status", emptyList(), 2_500),
        QuotaProfile(setOf("copilot"), "/usage", emptyList(), 3_000),
        // Grok's /usage opens a session/usage dialog over the chat that only Esc closes; while it is
        // open, typed text goes nowhere (2026-11-21). The app types this only when you send it from
        // the composer, and then closes it and checks that it closed ([closeUsageAndVerify]).
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
        // Kiro's /usage opens a panel ("Credits (0.06 of 50 covered in plan)" over a bar and a
        // percentage) that closes with Esc; its hint is "esc to close". Checked 2026-11-13.
        QuotaProfile(
            setOf("kiro"), "/usage", listOf("esc"), 3_000,
            openMarker = Regex("esc to close", RegexOption.IGNORE_CASE), everyMinutes = 30,
        ),
        // Muse Code (1.4.5): `muse usage --json` reports the plan's window and weekly allowance
        // without a model turn and without touching the pane. The launcher's update check is off for it.
        QuotaProfile(
            setOf("muse"), "", emptyList(), 0,
            cliCommand = "MUSE_NO_AUTO_UPDATE=1 muse usage --json", parseCli = ::museUsage, everyMinutes = 15,
        ),
        // Vibe keeps its usage gauge in the footer, so it is read off the screen, never typed for.
        QuotaProfile(setOf("vibe"), "", emptyList(), 0, screenUsage = ::vibeFooterUsage, everyMinutes = 10),
        QuotaProfile(
            setOf("hermes"), "/usage", emptyList(), 2_500,
            infoCommand = "/reasoning",
            everyMinutes = 30,
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

/**
 * True for a status bar such as Hermes's footer, which packs many fields between separators. Hermes
 * has drawn them with "│" and, since mid-November 2026, with " · " ("☤ gpt-6.1-sol · 422K/500K ·
 * [████████░░] 84% · ◷ 7.6s · ..."), so either counts.
 */
private fun isStatusBar(line: String) = line.count { it in SEP } >= 4 || line.split(" · ").size >= 5

/** A token count over a token limit ("422K/500K", "~124K/500K"): a context-window gauge, not a usage limit. */
private val TOKEN_GAUGE = Regex("""~?\d[\d.,]*\s*[KkMm]\s*/\s*\d[\d.,]*\s*[KkMm]\b""")

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
        // A narrow screen wraps the label over lines above the bar ("Current week (all models) ·
        // Resets Nov 19, 7:59am" then "(UTC)"): join up to four lines back, skipping bar lines,
        // stopping at a blank line or another percentage.
        var wrapped: String? = null
        val label = before.ifBlank {
            val parts = mutableListOf<String>()
            var k = i - 1
            while (k >= maxOf(0, i - 4)) {
                val c = column(raw[k])
                if (PERCENT.containsMatchIn(c)) break
                if (c.isBlank()) { if (parts.isNotEmpty()) break; k--; continue }
                if (!BAR.containsMatchIn(c) || c.any { it.isLetter() }) parts.add(0, c)
                k--
            }
            // Nearest line first; join the one above it only when the nearest is a wrapped tail
            // ("(UTC)", a lower-case continuation).
            val near = parts.lastOrNull().orEmpty().trim()
            val fragment = near.startsWith("(") || near.firstOrNull()?.isLowerCase() == true
            val joined = (if (fragment && parts.size > 1) parts[parts.size - 2] + " " + near else near).replace(Regex("\\s+"), " ").trim()
            wrapped = joined
            joined.substringBefore(" · Resets").substringBefore(" Resets ").substringBefore(" · resets").trim().trimEnd(':', '·', ' ')
                .takeIf { it.isNotBlank() && it.length <= 60 && !it.endsWith(".") }
        } ?: continue
        // Context-window gauges are not usage limits.
        if (label.length > 60 || label.contains("context", true) || TOKEN_GAUGE.containsMatchIn(label)) continue

        val pct = m.groupValues[1].toDoubleOrNull() ?: continue
        val remaining = when (word) {
            "left", "remaining" -> true
            "used" -> false
            else -> label.contains("remaining", true) || label.contains(" left", true) || bareMeansLeft
        }
        val used = (if (remaining) 100.0 - pct else pct).coerceIn(0.0, 100.0)

        val reset = RESET_INLINE.find(seg)?.groupValues?.get(1)?.trim()
            ?: wrapped?.let { RESET_INLINE.find(it)?.groupValues?.get(1)?.trim() }
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
    val found = when (ruleAgent(agent)) {
        // Explicit Claude model status or the completed switch acknowledgement, never the picker's highlight.
        "claude" -> lastMatch(Regex("""^\s*(?:(?:⏵{1,2}|⏸)[^·]*·\s*)?Model:\s*((?:Opus|Sonnet|Haiku|Fable)\s+\d+(?:\.\d+)?(?:\s+\(1M(?: context)?\))?)\s*(?:·.*)?$""", RegexOption.IGNORE_CASE))
            ?: lastMatch(Regex("""^\s*⎿[\s\p{Zs}]+Set model to\s+((?:Opus|Sonnet|Haiku|Fable)\s+\d+(?:\.\d+)?(?:\s+\(1M(?: context)?\))?)(?:\s+for this session only)?(?:\s+with (?:low|medium|high|xhigh|max) effort)?\s*[.!]?\s*$""", RegexOption.IGNORE_CASE))
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
        // OpenCode appends the provider and configuration after the selected model.
        "opencode" -> lastMatch(Regex("""^\s*[┃│]\s+[^·│]+?\s+·\s+([^│]+?)\s*$"""))
            ?.substringBefore(" (default)")?.trim()?.take(48)
        // " π > ⬢ llmproxy/flagship > 📁 ~/agents/omp ▶───41%───"
        "omp" -> lastMatch(Regex("""⬢\s+(\S+)"""))
        // "0.0%/128k (auto)          (compat-llmproxy) llmproxy/flagship__free"
        "pi" -> lastMatch(Regex("""\)\s+(\S+/\S+)\s*$""")) ?: lastMatch(Regex("""%/\S+.*\s{3,}(\S+)\s*$"""))
        // Kiro shows the selected model (or Auto) next to the agent name and context usage.
        "kiro", "kiro-cli" -> lastMatch(Regex("""^\s*[^·│]+?\s+·\s+(\S[^·│]*?)\s+·\s+[◔◕◑◐○●]"""))
            ?.let { if (it.equals("auto", ignoreCase = true)) "Auto" else it }
        // "──── Mistral Medium 3.5[high] · auto approve ─": the model and its thinking level.
        "vibe" -> lastMatch(Regex("""─\s+([^─·]+?)\s+·\s+[^─]+─\s*$"""))
            ?.replace(Regex("""\[(\w+)]$"""), " · $1")
        // Muse Code 1.4.20: "  muse-wonder-1.0 · high · ~/agents/muse · Launch overrides" (1.4.5).
        "muse" -> lastMatch(Regex("""^\s*(\S+\s+·\s+(?:none|minimal|low|medium|high|xhigh|max|ultra))\s+·\s+[~/]"""))
            ?: lastMatch(Regex("""^\s*(muse-[\w.-]+)\s+·"""))
        else -> null
    }
    return found?.takeIf { it.length in 2..48 }
}


private val LIMIT_WORDS = Regex("""(?i)week|session|day|hour|month|limit|model|opus|sonnet|fable|haiku|token|request|credit|premium|quota|usage|context|plan|all models""")

/**
 * A safe outline of a usage screen for the diagnostic log: its size, and for each percentage the
 * app saw, whether a bar, a "used/left" word and a label were near it. A label is shown only when
 * it reads like a limit name; any other text is given as its length, so nothing from a
 * conversation reaches the log.
 */
fun screenShape(text: String): String {
    val lines = text.lines()
    val nonBlank = lines.count { it.isNotBlank() }
    val widest = lines.maxOfOrNull { it.trimEnd().length } ?: 0
    val bars = lines.count { BAR.containsMatchIn(it) }
    val more = lines.takeLast(30).any { it.trim().endsWith("↓") || it.trim() == "↓" }
    fun safe(t: String): String {
        val c = t.trim()
        return if (c.length <= 60 && LIMIT_WORDS.containsMatchIn(c) && c.none { it == '/' || it == '@' || it == '=' }) "\"$c\"" else "(${c.length} chars of other text)"
    }
    val hits = lines.withIndex().filter { PERCENT.containsMatchIn(it.value) }.take(10).map { (i, l) ->
        val m = PERCENT.find(l)!!
        val after = l.substring(m.range.last + 1).trim().take(12).takeWhile { it.isLetter() || it == ' ' }.trim()
        val bar = BAR.containsMatchIn(l) || (i > 0 && BAR.containsMatchIn(lines[i - 1])) || (i + 1 < lines.size && BAR.containsMatchIn(lines[i + 1]))
        val word = Regex("(?i)used|left|remaining").containsMatchIn(l)
        val before = l.substring(0, m.range.first).replace(BAR, "").trim()
        val above = (i - 1 downTo maxOf(0, i - 3)).map { lines[it] }.firstOrNull { it.isNotBlank() && !PERCENT.containsMatchIn(it) && !(BAR.containsMatchIn(it) && it.none { c -> c.isLetter() }) }
        val label = when {
            before.any { it.isLetter() } -> "label on its line " + safe(before)
            above != null -> "label above " + safe(above)
            else -> "no label"
        }
        "${m.value}${if (after.isNotEmpty()) " $after" else ""} (bar ${if (bar) "yes" else "no"}, used/left ${if (word) "yes" else "no"}, $label)"
    }
    return "$nonBlank lines, widest $widest cols, $bars bar line(s), more below: ${if (more) "yes" else "no"}; " +
        (if (hits.isEmpty()) "no percentages" else "percentages: " + hits.joinToString("; "))
}

/**
 * A reset time as the agents print it ("Nov 19, 7:59am (UTC)", "November 17, 12:44",
 * "3pm (America/New_York)", "in 4h 12m", an ISO time) in one short form: "Thu 11/19", or just
 * the time when it is less than a day away ("3:00p"); no leading zeros ("Sat 12/5"). A time zone in parentheses is
 * honoured and shown in the phone's own zone. Anything unreadable is returned as it was.
 */
fun shortReset(raw: String, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String {
    val here = now.zone
    var s = raw.trim().removePrefix("Resets").removePrefix("resets").trim().removePrefix(":").removePrefix("at").trim()
    var zone: java.time.ZoneId = here
    Regex("""\(([^)]+)\)""").find(s)?.let { m ->
        runCatching { java.time.ZoneId.of(m.groupValues[1].trim()) }.getOrNull()?.let { zone = it }
        s = s.removeRange(m.range).trim()
    }
    fun out(t: java.time.ZonedDateTime): String {
        val local = t.withZoneSameInstant(here)
        val day = local.format(java.time.format.DateTimeFormatter.ofPattern("EEE M/d", java.util.Locale.US))
        if (java.time.Duration.between(now, local).toHours() >= 24 || local.isBefore(now)) return day
        return local.format(java.time.format.DateTimeFormatter.ofPattern("h:mm", java.util.Locale.US)) + if (local.hour < 12) "a" else "p"
    }
    // "in 4h 12m", "in 2 days", "3d 4h"
    Regex("""^(?:in\s+)?((?:\d+\s*(?:d|days?|h|hrs?|hours?|m|mins?|minutes?)\s*)+)$""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
        var secs = 0L
        Regex("""(\d+)\s*([a-z]+)""", RegexOption.IGNORE_CASE).findAll(m.groupValues[1]).forEach { p ->
            val n = p.groupValues[1].toLong()
            secs += when (p.groupValues[2].lowercase().first()) { 'd' -> n * 86400; 'h' -> n * 3600; else -> n * 60 }
        }
        return out(now.plusSeconds(secs))
    }
    runCatching { return out(java.time.ZonedDateTime.parse(s)) }
    runCatching { return out(java.time.OffsetDateTime.parse(s).toZonedDateTime()) }
    // A time of day: "7:59am", "3pm", "12:44", "15:00".
    fun time(t: String): java.time.LocalTime? {
        val m = Regex("""^(\d{1,2})(?::(\d{2}))?\s*([ap]\.?m\.?)?$""", RegexOption.IGNORE_CASE).find(t.trim()) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].lowercase().replace(".", "")
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h > 23 || min > 59 || (ap.isEmpty() && m.groupValues[2].isEmpty())) return null
        return java.time.LocalTime.of(h, min)
    }
    val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    // "Nov 19, 7:59am", "November 17, 12:44", "Nov 19 at 8am", "Nov 19 2026", "Thu, Nov 19"
    Regex("""^(?:[A-Za-z]{3,9},?\s+)?([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?(?:,?\s*(?:at\s+)?(.+))?$""").find(s)?.let { m ->
        val mi = months.indexOf(m.groupValues[1].lowercase().take(3))
        if (mi >= 0) {
            val today = now.withZoneSameInstant(zone).toLocalDate()
            var date = runCatching { java.time.LocalDate.of(m.groupValues[3].ifEmpty { today.year.toString() }.toInt(), mi + 1, m.groupValues[2].toInt()) }.getOrNull()
            if (date != null) {
                if (m.groupValues[3].isEmpty() && date.isBefore(today.minusDays(30))) date = date.plusYears(1)
                val t = m.groupValues[4].takeIf { it.isNotBlank() }?.let(::time) ?: java.time.LocalTime.MIDNIGHT
                return out(java.time.ZonedDateTime.of(date, t, zone))
            }
        }
    }
    time(s)?.let { t ->
        var at = java.time.ZonedDateTime.of(now.withZoneSameInstant(zone).toLocalDate(), t, zone)
        if (at.isBefore(now)) at = at.plusDays(1)
        return out(at)
    }
    return raw
}

/** What changed between two readings of one limit, for alerts: a line it crossed, a reset, or nothing. */
sealed interface UsageChange {
    data class Crossed(val line: Int) : UsageChange
    data object Reset : UsageChange
}

fun usageChange(was: Double, now: Double): UsageChange? {
    listOf(95, 80).firstOrNull { was < it && now >= it }?.let { return UsageChange.Crossed(it) }
    if (was >= 50 && now <= was - 40) return UsageChange.Reset
    return null
}
