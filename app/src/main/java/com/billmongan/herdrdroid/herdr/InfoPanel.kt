package com.billmongan.herdrdroid.herdr

/**
 * 2.2.1: informational panels an agent draws over its composer (status, usage, context, cost,
 * session info, help, shortcuts, dashboards), recognized for every agent the app supports.
 *
 * Bill (2026-11-22): "also grok had a status pane open so make sure that 2.2.1 herdrdroid clears
 * that too", "(for all panes)", "try to be robust to harness version changes". Grok 1.0.92's
 * /status (an alias of /session-info) opens a "Session info" box whose footer differs from its
 * /usage box, so the exact 2.1.4 matcher never saw it and the box stayed open over the composer.
 *
 * Recognition is structural, so a harness update that rewords a row or moves a footer segment
 * still reads the same way:
 *  - a close hint ("esc to close", "Esc close", "esc to cancel", "Esc back", "esc:close"), as one
 *    short segment of a key-hint row (prose that happens to mention Esc is not a hint row);
 *  - a box (light, heavy, rounded or double) or horizontal rules around that row, or nothing but
 *    the agent's own chrome under it (the composer covered or gone);
 *  - a title row naming the panel (status, usage, context, dashboard, cost, session, help,
 *    shortcuts, stats, settings, model).
 * The exact 2.1.4 and 2.1.5 matchers ([exactUsageFrame]) stay as positive evidence.
 *
 * What a read can be: [InfoPanel.known] (positively identified, and the agent's close key for that
 * kind of panel is verified, so one Esc may be pressed after a same-frame check), or a panel that
 * may be open ([InfoPanel.why] says why it can't be closed from here): sends wait, and nothing is
 * pressed. A question, an approval, or a numbered list of choices is never a panel; those have
 * their own cards.
 */
internal enum class PanelKind(val label: String) {
    STATUS("status panel"), USAGE("usage panel"), CONTEXT("context panel"), COST("cost panel"),
    SESSION("session panel"), DASHBOARD("dashboard"), HELP("help panel"), SHORTCUTS("shortcuts panel"),
    STATS("stats panel"), SETTINGS("settings panel"), MODEL("model picker"), UNKNOWN("panel"),
}

internal data class InfoPanel(
    val agent: String,
    val kind: PanelKind,
    /** Positively identified with a verified close key: Esc may be pressed (after a same-frame check). */
    val known: Boolean,
    /** Why it may not be closed from here, when not [known]. */
    val why: String?,
    /** The panel's rows as read, top to bottom (box borders cut off). */
    val frame: List<String>,
    val closeKey: String = "esc",
)

/**
 * The panels each agent closes with one Esc and nothing else, from its installed build on the
 * computer (2026-11-22): bundled docs, key-hint strings and captures. Grok documents that Esc goes
 * to "overlays, modals" first ("steal-Esc"); its Agent Dashboard is left out, since there Esc steps
 * back through search, peek and focus before it exits. Hermes, Copilot and Muse draw no panel the
 * app knows how to close, so any panel there only holds sends.
 */
internal val PANEL_ESC_VERIFIED: Map<String, Set<PanelKind>> = mapOf(
    // Claude Code 2.1.329: Settings (Status, Config, Usage, Stats tabs), /help, /context: "Esc to close/cancel".
    "claude" to setOf(PanelKind.SETTINGS, PanelKind.STATUS, PanelKind.USAGE, PanelKind.STATS, PanelKind.CONTEXT,
        PanelKind.COST, PanelKind.HELP, PanelKind.SHORTCUTS),
    // Codex 0.171: "Thread usage ... esc to dismiss", pager overlays "enter/q/esc close".
    "codex" to setOf(PanelKind.STATUS, PanelKind.USAGE, PanelKind.CONTEXT, PanelKind.HELP, PanelKind.SHORTCUTS),
    // Grok 1.0.92: /session-info (/status, /info), /usage (/cost), /context, Keyboard Shortcuts: "Esc close".
    "grok" to setOf(PanelKind.STATUS, PanelKind.SESSION, PanelKind.USAGE, PanelKind.COST, PanelKind.CONTEXT,
        PanelKind.STATS, PanelKind.HELP, PanelKind.SHORTCUTS),
    // Kiro: /usage and /context: "esc to close   Tab to switch to /context".
    "kiro" to setOf(PanelKind.USAGE, PanelKind.CONTEXT, PanelKind.HELP),
    // Antigravity: usage "esc Close", help "Press Enter/Esc to dismiss".
    "agy" to setOf(PanelKind.USAGE, PanelKind.STATUS, PanelKind.HELP),
    // OpenCode: dialogs with an "esc close" / "esc dismiss" header (status, help, shortcuts).
    "opencode" to setOf(PanelKind.STATUS, PanelKind.HELP, PanelKind.SHORTCUTS),
    "opencode-college" to setOf(PanelKind.STATUS, PanelKind.HELP, PanelKind.SHORTCUTS),
    // Oh My Pi and pi: overlays with "Esc close" / "esc close".
    "omp" to setOf(PanelKind.STATUS, PanelKind.USAGE, PanelKind.SESSION, PanelKind.HELP, PanelKind.SHORTCUTS),
    "pi" to setOf(PanelKind.STATUS, PanelKind.USAGE, PanelKind.SESSION, PanelKind.HELP, PanelKind.SHORTCUTS),
    // Mistral Vibe 3.1.2 (vibe-rs): panels with "Esc close".
    "vibe" to setOf(PanelKind.STATUS, PanelKind.USAGE, PanelKind.SESSION, PanelKind.HELP),
)

private const val VERTICAL = "│┃║|╎╏┆┇"
private const val TOP_CORNERS = "┌╭┏╔┍┎╒╓"
private const val BOTTOM_CORNERS = "└╰┗╚┕┖╘╙"
private const val BOX_CHARS = "─━═┄┅┈┉╌╍-│┃║|┌┐└┘╭╮╰╯┏┓┗┛╔╗╚╝├┤┬┴┼╞╡╟╢┝┥┠┨ "

/** One key hint that closes: "esc to close", "Esc close", "press Esc to go back", "esc:close", "q/Esc closes". */
private val CLOSE_HINT = Regex(
    """^(?:press\s+)?(?:\[\s*)?(?:(?:enter|q|f2)\s*/\s*)*(?:esc|escape)(?:\s*])?(?:\s*/\s*q)?\s*[:=]?\s*(?:key\s+)?(?:to\s+)?""" +
        """(?:close|cancel|back|go\s+back|dismiss|exit|hide|return)(?:s|es)?(?:\s+\S+){0,3}$""",
    RegexOption.IGNORE_CASE,
)
/** The reversed form some footers use: "close: esc", "back esc". */
private val CLOSE_HINT_REVERSED = Regex("""^(?:close|back|dismiss|exit)\s*[:=]?\s*\[?\s*esc(?:ape)?\s*]?$""", RegexOption.IGNORE_CASE)
/** A bare "esc" segment: a close hint only on a header row next to the panel's title (OpenCode's dialogs). */
private val BARE_ESC = Regex("""^\[?\s*esc(?:ape)?\s*]?$""", RegexOption.IGNORE_CASE)
/** Segments of a key-hint row. */
private val HINT_SPLIT = Regex("""\s*[·•│┃|]\s*|\s{2,}""")
/** Enter that commits something: a picker or a form, never closed from here. */
private val COMMIT_HINT = Regex(
    """\benter\b\s*(?:to\s+|or\s+\S+\s+)?(?:submit|send|confirm|approve|allow|accept|save|create|apply|run|choose|select|pick|continue|retry|answer|reply|change|edit|install|delete|remove|switch|use)\b""",
    RegexOption.IGNORE_CASE,
)
/** A hint saying a second Esc quits (Claude: "esc to close · esc again to quit"). */
private val QUIT_HINT = Regex("""\bagain to (?:quit|exit)\b|\besc\b[^·•|│]*\bto quit\b""", RegexOption.IGNORE_CASE)
/** A search or filter field: a typed query may be the user's. */
private val SEARCH_HINT = Regex("""\btype to (?:search|filter)\b""", RegexOption.IGNORE_CASE)
/** Words that make a row a question or an approval. */
private val ASK_WORDS = Regex(
    """\bdo you want\b|\bwould you like\b|\ballow (?:once|always|this|for)\b|\bapprove\b|\bdeny\b|\breject\b|\bproceed\b|\bconfirm\b|\btrust (?:this|the) (?:folder|directory|workspace)\b""",
    RegexOption.IGNORE_CASE,
)
/** A picker's heading inside a panel ("Select model", "Switch model"). */
private val PICKER_TITLE = Regex("""\b(?:select|choose|switch|pick|change)\s+(?:a\s+|the\s+)?models?\b""", RegexOption.IGNORE_CASE)
private val OPTION_ROW = Regex("""^[❯›>▸▶➤*]?\s*\d{1,2}[.)]\s+\S""")
private val TITLE_WORDS = Regex(
    """(?<![\w/.-])/?(dashboard|session[\s-]+info|status|usage|context|costs?|sessions?|help|keyboard\s+shortcuts|shortcuts|hotkeys|keybindings|stats|statistics|credits|limits|settings|config|models?|info)(?![\w/.-])""",
    RegexOption.IGNORE_CASE,
)
private val COMPOSER_ROW = Regex("""^[\s │┃|]*[>❯›»][\s ]*(.*?)[\s │┃|]*$""")

private fun body(row: String) = row.trim().trim { it in VERTICAL || it.isWhitespace() }

private fun segments(row: String) = body(row).split(HINT_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * True when [row] is a key-hint row that offers a close: short segments, one of them a close hint.
 * A row may hold chat text left of a box's border; each part between box borders is judged alone.
 */
internal fun isCloseHintRow(row: String): Boolean = hintPartStart(row) >= 0

/** Where the hint part of [row] starts, or -1 when no part of it is a close-hint row. */
private fun hintPartStart(row: String): Int {
    var start = 0
    for (i in 0..row.length) {
        if (i == row.length || row[i] in BOX_VERTICAL) {
            if (isHintPart(row.substring(start, i))) return start + (row.substring(start, i).indexOfFirst { !it.isWhitespace() }).coerceAtLeast(0)
            start = i + 1
        }
    }
    return -1
}

private const val BOX_VERTICAL = "│┃║"

private fun isHintPart(row: String): Boolean {
    val segs = segments(row)
    if (segs.isEmpty() || segs.any { it.split(' ').size > 7 }) return false
    if (segs.any { CLOSE_HINT.matches(it) || CLOSE_HINT_REVERSED.matches(it) }) return true
    // OpenCode: "Status                     esc" (title left, bare key right).
    return segs.size in 2..3 && BARE_ESC.matches(segs.last()) && segs.dropLast(1).any { titleOf(it) != null }
}

private fun titleOf(row: String): PanelKind? {
    val t = body(row)
    if (t.isEmpty() || t.length > 80 || t.endsWith("?")) return null
    if (t.split(Regex("\\s+")).size > 10) return null
    val words = TITLE_WORDS.findAll(t).map { it.groupValues[1].lowercase().replace(Regex("\\s+|-"), " ") }.toList()
    if (words.isEmpty()) return null
    // A tab header ("Settings  Status  Config  Usage") or a single title ("Session info", "/usage").
    fun kindOf(w: String): PanelKind = when {
        w == "dashboard" -> PanelKind.DASHBOARD
        w == "session info" || w == "info" -> PanelKind.STATUS
        w == "status" -> PanelKind.STATUS
        w == "usage" || w == "credits" || w == "limits" -> PanelKind.USAGE
        w == "context" -> PanelKind.CONTEXT
        w.startsWith("cost") -> PanelKind.COST
        w.startsWith("session") -> PanelKind.SESSION
        w == "help" -> PanelKind.HELP
        w.contains("shortcuts") || w == "hotkeys" || w == "keybindings" -> PanelKind.SHORTCUTS
        w.startsWith("stat") -> PanelKind.STATS
        w == "settings" || w == "config" -> PanelKind.SETTINGS
        else -> PanelKind.MODEL
    }
    val kinds = words.map(::kindOf)
    // A dashboard is never closed with Esc: it wins over any other word on the row.
    if (PanelKind.DASHBOARD in kinds) return PanelKind.DASHBOARD
    // A title is mostly the panel's own words; a sentence that mentions "status" is not a title.
    val rest = TITLE_WORDS.replace(t, "").replace(Regex("""[\s/:·•|│\[\]()-]+"""), " ").trim()
    if (rest.split(' ').filter { it.isNotEmpty() }.size > 3) return null
    if (PanelKind.SETTINGS in kinds && kinds.size > 1) return PanelKind.SETTINGS
    // A tab header ("Session   Usage"): the most specific tab name names it.
    val order = listOf(PanelKind.USAGE, PanelKind.STATUS, PanelKind.CONTEXT, PanelKind.COST, PanelKind.STATS,
        PanelKind.SESSION, PanelKind.HELP, PanelKind.SHORTCUTS, PanelKind.SETTINGS, PanelKind.MODEL)
    return kinds.minByOrNull { order.indexOf(it) } ?: kinds.first()
}

private fun isRule(row: String): Boolean {
    val t = row.trim()
    return t.length >= 10 && t.all { it in "─━═┄┅┈┉╌╍-" }
}

private fun isBorderOnly(row: String): Boolean {
    val t = row.trim()
    // A border may carry a label ("╰──── Grok 4.7 (high) · auto-review ─╯"): it starts and ends with box chars.
    if (t.isEmpty()) return true
    if (t.all { it in BOX_CHARS }) return true
    return t.first() in "└╰┗╚╭┌┏╔" && t.last() in "┘╯┛╝╮┐┓╗" && t.count { it in "─━═" } >= 6
}

private data class Region(val top: Int, val bottom: Int, val col: Int, val boxed: Boolean, val ruled: Boolean, val right: Int = Int.MAX_VALUE)

/** The box whose left border runs through [hintAt] at a column left of [hintCol], if any. */
private fun boxAround(rows: List<String>, hintAt: Int, hintCol: Int): Region? {
    val row = rows[hintAt]
    var col = -1
    for (c in (hintCol - 1).coerceAtMost(row.length - 1) downTo 0) if (row[c] in VERTICAL) { col = c; break }
    if (col < 0) return null
    fun at(r: String, set: String) = r.length > col && r[col] in set
    var top = -1
    for (i in hintAt - 1 downTo (hintAt - 50).coerceAtLeast(0)) {
        val r = rows[i]
        if (at(r, TOP_CORNERS)) { top = i; break }
        if (!at(r, VERTICAL)) return null
    }
    if (top < 0) return null
    var bottom = -1
    for (i in hintAt + 1 until rows.size) {
        val r = rows[i]
        if (at(r, BOTTOM_CORNERS)) { bottom = i; break }
        if (!at(r, VERTICAL)) return null
    }
    if (bottom < 0) return null
    val right = rows[top].indexOfFirst { it in "┐╮┓╗┑┒╕╖" }.takeIf { it > col } ?: Int.MAX_VALUE
    return Region(top, bottom, col, boxed = true, ruled = false, right = right)
}

/** Rules above the hint (Kiro: rule, "/usage", rule, body, rule, hint), or none. */
private fun rulesAbove(rows: List<String>, hintAt: Int): Region? {
    var top = -1
    var blanks = 0
    for (i in hintAt - 1 downTo (hintAt - 30).coerceAtLeast(0)) {
        val r = rows[i]
        if (r.isBlank()) { if (++blanks > 2) break; continue }
        blanks = 0
        if (COMPOSER_ROW.matches(r) && !isRule(r)) break
        if (isRule(r)) top = i
    }
    return if (top >= 0) Region(top, hintAt, 0, boxed = false, ruled = true) else null
}

/** No box or rules: the rows above the hint up to a gap of two blank rows (at most 24 rows). */
private fun plainAbove(rows: List<String>, hintAt: Int): Region? {
    var top = hintAt
    var blanks = 0
    for (i in hintAt - 1 downTo (hintAt - 24).coerceAtLeast(0)) {
        if (rows[i].isBlank()) { if (++blanks >= 2) break; continue }
        blanks = 0
        // The agent's composer (or its box) right above: the hint is under the composer, not a panel's footer.
        if (COMPOSER_ROW.matches(rows[i]) || isBorderOnly(rows[i])) return null
        top = i
    }
    return Region(top, hintAt, 0, boxed = false, ruled = false)
}

/**
 * The informational panel drawn on [visible] for [agent], or null when none shows (see the file
 * comment). Read-only: never a predicate for a key on its own; the caller still checks the live
 * frame twice before an Esc.
 */
internal fun infoPanelOn(agent: String?, visible: String): InfoPanel? {
    val a = agent?.let(::canonicalAgent) ?: return null
    if (visible.isBlank()) return null
    // Claude's /btw side-question panel has its own flow (2.1.4).
    if (a == "claude" && claudeBtwOverlay(visible) != null) return null
    exactUsageFrame(a, visible)?.let { return InfoPanel(a, PanelKind.USAGE, known = true, why = null, frame = it) }
    val text = forPhone(visible)
    val rows = text.lines().map { it.trimEnd() }.takeLast(60)
    val hintAt = rows.indexOfLast(::isCloseHintRow)
    if (hintAt < 0) return null
    // A question or an approval has its own card and is never closed with Esc.
    if (showsQuestionDialog(text) || detectPrompt(text, false, a) != null) return null

    val hintRow = rows[hintAt]
    val hintCol = hintPartStart(hintRow).coerceAtLeast(0)
    val hintSegs = segments(hintRow.substring(hintCol))
    // A header with the title on the hint row ("Status            esc", OpenCode's dialogs): the
    // dialog runs below it, drawn with colours only, so its extent can't be checked from text.
    val headerTitle = if (isOpenCode(a) || BARE_ESC.matches(hintSegs.last()))
        hintSegs.filterNot { CLOSE_HINT.matches(it) || CLOSE_HINT_REVERSED.matches(it) || BARE_ESC.matches(it) }.firstNotNullOfOrNull(::titleOf)
    else null
    if (headerTitle != null) return InfoPanel(a, headerTitle, known = false, why = "its layout can't be checked from the screen text",
        frame = rows.drop(hintAt).filter { it.isNotBlank() }.take(20))
    val region = boxAround(rows, hintAt, hintCol) ?: rulesAbove(rows, hintAt) ?: plainAbove(rows, hintAt) ?: return null
    fun cut(r: String) = if (region.boxed) r.substring((region.col + 1).coerceAtMost(r.length), region.right.coerceAtMost(r.length).coerceAtLeast((region.col + 1).coerceAtMost(r.length))) else r
    val inside = rows.subList(region.top, region.bottom + 1).map(::cut)
    val content = inside.map(::body).filter { it.isNotEmpty() && !isRule(it) && !isBorderOnly(it) }
    if (content.isEmpty()) return null

    // What is under the panel: only blank rows, borders, an empty composer and short chrome rows.
    val under = rows.drop(region.bottom + 1).filter { it.isNotBlank() }
    val drawnUnder = under.filterNot { isBorderOnly(it) }
    val composerUnder = drawnUnder.mapNotNull { COMPOSER_ROW.matchEntire(it)?.groupValues?.get(1) }
    val draftUnder = composerUnder.any { body(it).isNotEmpty() } ||
        (under.isNotEmpty() && composerDraftOf(under.joinToString("\n"), a) != null)
    val chromeUnder = drawnUnder.filter { COMPOSER_ROW.matchEntire(it) == null }
    val maxUnder = if (region.boxed) 6 else 3
    // Text or a composer under an unframed hint: the conversation goes on, the hint was no panel's footer.
    if (!region.boxed && !region.ruled && (drawnUnder.size > maxUnder || composerUnder.isNotEmpty())) return null
    if (drawnUnder.size > maxUnder + 2) return null

    val framed = region.boxed || region.ruled
    // The title is the panel's first row (or one of its first three inside a box or rules).
    val kind = content.take(if (framed) 3 else 1).firstNotNullOfOrNull(::titleOf)
    if (kind == null && !framed) return null
    val k = kind ?: PanelKind.UNKNOWN
    fun maybe(why: String) = InfoPanel(a, k, known = false, why = why, frame = inside)

    // Choices, approvals and forms: the panel takes an answer, so it is never closed from here.
    val panelText = content.joinToString("\n")
    if (content.any { ASK_WORDS.containsMatchIn(it) || (it.endsWith("?") && it.split(' ').size >= 3) }) return maybe("it may be asking something")
    if (content.any { OPTION_ROW.containsMatchIn(it) }) return maybe("it lists choices")
    if (k == PanelKind.MODEL || content.any { PICKER_TITLE.containsMatchIn(it) }) return maybe("a model picker may be open")
    val hints = inside.filter(::isCloseHintRow) + hintRow
    if (hints.any { COMMIT_HINT.containsMatchIn(it) } || COMMIT_HINT.containsMatchIn(panelText)) return maybe("it takes a choice or an entry")
    if (hints.any { QUIT_HINT.containsMatchIn(it) }) return maybe("a second Esc there may quit the agent")
    if (hints.any { SEARCH_HINT.containsMatchIn(it) }) return maybe("it has a search field")
    if (draftUnder) return maybe("text is typed in the input box under it")
    if (chromeUnder.any { it.trim().endsWith("?") || OPTION_ROW.containsMatchIn(body(it)) }) return maybe("something else is drawn under it")
    if (kind == null) return maybe("its title isn't one HerdrDroid knows")
    if (k == PanelKind.DASHBOARD) return maybe("the dashboard takes more than one Esc to leave")
    if (k !in PANEL_ESC_VERIFIED[a].orEmpty()) return maybe("HerdrDroid hasn't verified how this agent closes it")
    return InfoPanel(a, k, known = true, why = null, frame = inside)
}

/** What a guarded live read found for the close flow (2.2.1). */
internal sealed interface PanelState {
    data class Open(val overlay: UsageOverlay) : PanelState
    /** No panel, and the agent's composer is back. */
    data object Closed : PanelState
    /** Something that must not get an Esc: a question, a draft, or a panel not positively known. */
    data class Stop(val why: String) : PanelState
    /** The read failed or was refused, or it can't tell. */
    data object Unknown : PanelState
}

/** Classifies one guarded live read for the close flow; [occupant] binds an Open to that read. */
internal fun panelStateOf(agent: String, occupant: String, visible: String): PanelState {
    if (visible.isBlank()) return PanelState.Unknown
    val panel = infoPanelOn(agent, visible)
    if (panel != null) {
        return if (panel.known && dismissibleUsageFrame(agent, visible) != null) PanelState.Open(UsageOverlay(occupant, visible))
        else PanelState.Stop(panel.why ?: "it isn't a panel HerdrDroid knows how to close")
    }
    val text = forPhone(visible)
    if (showsQuestionDialog(text) || detectPrompt(text, false, agent) != null) return PanelState.Stop("a question is open")
    // Grok's and Antigravity's own hint of a usage dialog in a layout not known: can't tell.
    if (usageDialogHint(agent, visible)) return PanelState.Unknown
    val line = inputLineOf(visible, agent)
    return when {
        line == null && composerDraftOf(visible, agent) == null -> PanelState.Unknown
        else -> PanelState.Closed
    }
}

/** How a panel close went (2.2.1). */
internal data class PanelCloseOutcome(val closed: Boolean, val escapes: Int, val why: String?)

/**
 * Close a positively identified panel and confirm the composer is back (2.2.1). Each round reads
 * the live screen ([readState]); only [PanelState.Open] gets one Esc, sent by [sendEscape] for that
 * exact snapshot (the server reads the pane again and sends only when it is the same frame). Up to
 * [maxEscapes] Esc presses, at least [settle] apart, so two presses never land inside an agent's
 * double-Esc window (Grok and Claude open their rewind picker on a quick double Esc). It stops at
 * once when the frame changed before the key, or a question, draft or unknown dialog appears.
 */
internal suspend fun closePanelAndVerify(
    stillCurrent: () -> Boolean,
    readState: suspend () -> PanelState,
    sendEscape: suspend (UsageOverlay) -> Boolean,
    settle: suspend () -> Unit,
    maxEscapes: Int = 2,
): PanelCloseOutcome {
    var escapes = 0
    var unknown = 0
    while (true) {
        if (!stillCurrent()) return PanelCloseOutcome(false, escapes, "the pane or its connection changed")
        val state = try {
            readState()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            PanelState.Unknown
        }
        when (state) {
            PanelState.Closed -> return PanelCloseOutcome(true, escapes, null)
            is PanelState.Stop -> return PanelCloseOutcome(false, escapes, state.why)
            PanelState.Unknown -> {
                if (++unknown >= 2) return PanelCloseOutcome(false, escapes, "its screen couldn't be checked")
                settle()
            }
            is PanelState.Open -> {
                if (escapes >= maxEscapes) return PanelCloseOutcome(false, escapes, "it was still open after $escapes Esc")
                if (!stillCurrent()) return PanelCloseOutcome(false, escapes, "the pane or its connection changed")
                val sent = try {
                    sendEscape(state.overlay)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (_: Exception) {
                    false
                }
                if (!sent) return PanelCloseOutcome(false, escapes, "the screen changed before Esc could be pressed")
                escapes++
                settle()
            }
        }
    }
}
