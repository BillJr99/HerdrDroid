package com.billmongan.herdrdroid.herdr

/**
 * 2.2.0 (Bill: "let me click on the pill buttons for multi question menus to go back to prior
 * questions or forth to other questions to revise before submitting"): a tap on a question pill
 * moves the agent's own multi-question dialog to that question, with the dialog's own navigation
 * keys, and nothing else.
 *
 * Only Claude Code's AskUserQuestion dialog shows pills in the app (its tab bar, [parseTabBar]).
 * Its footer says "Tab/Arrow keys to navigate", and the styled captures in
 * screens/claude/captured-2.1.329/styled (Right once and twice without answering, Left back, Left
 * back to an answered question with its answer still ticked) show Left and Right move one tab at a
 * time without answering. Codex's question overlay names only the question it shows ("Question
 * 1/2"), and Hermes's clarify batch and OpenCode's question forms are Terminal-only cards, so none
 * of them has pills to tap.
 *
 * Navigation never sends Enter, Esc, Space or a digit: only Left or Right, the fewest presses from
 * the tab the live screen marks as current to the tapped one. The Submit pill only moves to the
 * review step; its Submit answers button still needs its own tap.
 */
internal object QuestionPills {
    /** Whether [agent]'s pills can be tapped at all: only Claude's dialog, on an answerable card with two or more tabs. */
    fun tappable(agent: String?, p: Prompt): Boolean =
        agent.equals("claude", true) && !p.terminalReview && p.shownParts().tabs.size >= 2

    /** Claude's keys from tab [from] to tab [to]: Right to go forward, Left to go back, one per tab. */
    fun claudeKeys(from: Int, to: Int): List<String> =
        if (to >= from) List(to - from) { "right" } else List(from - to) { "left" }

    /** The tab bar of a live read: the tabs, and whether a styled read marked the current one. */
    data class Bar(val tabs: List<QTab>, val marked: Boolean) {
        val current: Int get() = tabs.indexOfFirst { it.current }
        val titles: List<String> get() = tabs.map { it.title }
    }

    /** The last tab bar on [text] (as [stripAnsi] gives it, with [TAB_CURRENT_MARK] kept), or null. */
    fun bar(text: String): Bar? {
        val line = text.lines().takeLast(80).lastOrNull { parseTabBar(it.trim()) != null } ?: return null
        val tabs = parseTabBar(line.trim())?.first ?: return null
        return Bar(tabs, line.indexOf(TAB_CURRENT_MARK) >= 0 && tabs.count { it.current } == 1)
    }

    sealed interface Plan {
        data class Send(val keys: List<String>, val from: Int, val to: Int) : Plan
        /** The dialog already shows that question: nothing to send. */
        object Already : Plan
        data class Refuse(val why: String) : Plan
    }

    /**
     * What a tap on pill [to] (titled [title]) of the card [shown] sends, from a live read: [text]
     * (stripped of styles, mark kept) and [fresh] (the card read from it). Refuses unless the live
     * dialog is the one the card was shown for, its current tab is marked, and the focused row is
     * not the free-text row (where Left and Right would move the text cursor instead).
     */
    fun plan(agent: String?, shown: Prompt, to: Int, title: String, text: String?, fresh: Prompt?): Plan {
        if (!tappable(agent, shown)) return Plan.Refuse("this agent's question steps are for display only")
        val shownTabs = shown.shownParts().tabs
        if (to !in shownTabs.indices || shownTabs[to].title != title) return Plan.Refuse("that step is not on this card")
        if (text == null || fresh == null) return Plan.Refuse("the question is no longer on screen")
        if (claudeUsageOpen(text)) return Plan.Refuse("a usage screen is open")
        if (claudeQuestionBehindDraft(text)) return Plan.Refuse("text is typed in the input box")
        if (!cardAnswerStillFits(shown, fresh)) return Plan.Refuse("the question changed")
        val bar = bar(text) ?: return Plan.Refuse("the question steps are no longer on screen")
        if (bar.titles != shownTabs.map { it.title }) return Plan.Refuse("the question changed")
        if (!bar.marked) return Plan.Refuse("the app couldn't see which question is shown")
        val from = bar.current
        if (from == to) return Plan.Already
        if (focusOnTypedRow(fresh)) return Plan.Refuse("the highlight is on the typed-answer row")
        return Plan.Send(claudeKeys(from, to), from, to)
    }

    /**
     * Whether the dialog's highlight is unknown or on its free-text row ("Type something.", or the
     * row just above "Chat about this", where typed text replaces the label).
     */
    internal fun focusOnTypedRow(p: Prompt): Boolean {
        val focus = p.choices.indexOfFirst { it.keys == p.selected }
        if (focus < 0) return true
        val label = p.choices[focus].label
        if (isTypedAnswerChoice(label) && !CHAT_ROW.matches(label.trim())) return true
        return p.choices.getOrNull(focus + 1)?.label?.trim()?.let(CHAT_ROW::matches) == true
    }

    private val CHAT_ROW = Regex("""^chat about this\.?$""", RegexOption.IGNORE_CASE)

    /**
     * Whether the read after the keys shows tab [to] of the same dialog: the same tab titles, the
     * styled mark on [to], and its question. A question's text is [known] when the app saw that
     * tab before, and must match; otherwise it must differ from [origin], the question the keys
     * left. The review step must offer its Submit answers button.
     */
    fun landed(titles: List<String>, to: Int, text: String?, after: Prompt?, origin: String?, known: String?): Boolean {
        if (text == null || after == null || after.terminalReview) return false
        val bar = bar(text) ?: return false
        if (bar.titles != titles || !bar.marked || bar.current != to) return false
        val heading = after.shownParts().heading?.trim().orEmpty()
        if (bar.tabs[to].submit) return after.choices.any { it.label.trim().equals("Submit answers", true) }
        if (heading.isEmpty()) return false
        return if (known != null) heading == known else heading != origin?.trim()
    }

    /** The outcome of a tap, for the card's note and the log. */
    sealed interface Outcome {
        data class Moved(val to: Int, val keys: List<String>) : Outcome
        object Already : Outcome
        data class Refused(val why: String) : Outcome
        /** Keys went, but the screen did not show the tapped question; no more keys were sent. */
        data class Missed(val to: Int, val keys: List<String>) : Outcome
    }

    /**
     * One tap, end to end: [read] the live screen (null when the card's pane, server or occupant
     * changed, or the read failed), [plan], [send] the keys once, then read again up to [tries]
     * times (with [pause] between) until the tapped question shows. [headings] remembers each
     * tab's question for this dialog, so returning to one is checked against what it showed.
     */
    suspend fun move(
        agent: String?, shown: Prompt, to: Int, title: String,
        headings: MutableMap<Int, String>,
        read: suspend () -> String?,
        detect: (String) -> Prompt?,
        stillCurrent: () -> Boolean,
        send: suspend (List<String>) -> Unit,
        pause: suspend () -> Unit = {},
        tries: Int = 4,
    ): Outcome {
        val text = read()
        val fresh = text?.let(detect)
        when (val p = plan(agent, shown, to, title, text, fresh)) {
            is Plan.Refuse -> return Outcome.Refused(p.why)
            Plan.Already -> return Outcome.Already
            is Plan.Send -> {
                val origin = checkNotNull(fresh).shownParts().heading?.trim()
                if (origin != null && !shown.shownParts().tabs[p.from].submit) headings.putIfAbsent(p.from, origin)
                if (!stillCurrent()) return Outcome.Refused("the pane changed")
                send(p.keys)
                val titles = shown.shownParts().tabs.map { it.title }
                repeat(tries) {
                    pause()
                    if (!stillCurrent()) return Outcome.Missed(to, p.keys)
                    val after = read()
                    val card = after?.let(detect)
                    if (landed(titles, to, after, card, origin, headings[to])) {
                        card?.shownParts()?.heading?.trim()?.takeIf { it.isNotEmpty() && !shown.shownParts().tabs[to].submit }
                            ?.let { headings[to] = it }
                        return Outcome.Moved(to, p.keys)
                    }
                }
                return Outcome.Missed(to, p.keys)
            }
        }
    }
}
