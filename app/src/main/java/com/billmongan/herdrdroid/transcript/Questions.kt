package com.billmongan.herdrdroid.transcript

import com.billmongan.herdrdroid.herdr.Prompt
import org.json.JSONArray
import org.json.JSONObject

/*
 * Question history in the chat (1.3.0, Bill: "when I get asked a question by an agent the question
 * text and the proposed answers and my actual answer should remain as text in the input output
 * window as part of the chat history doesn't have to be in an orange card or anything like that
 * but it should still be there so I can scroll through and persist it").
 *
 * Every question card the app shows (any agent: Claude, including its multi-question dialog,
 * Codex, Grok, including its radio cards and the privacy banner, Copilot, Antigravity, Kiro, Vibe)
 * is recorded as a [QuestionItem]: the question laid out the way 1.2.9 lays it out (no box or
 * marker characters), the options offered, and the answer. The answer is the option picked or the
 * text typed in the app; a question that goes away without an answer from the app is kept and
 * marked "answered elsewhere" (the agent went back to work) or "dismissed" (it did not). Nothing is
 * made up: an answer is only shown when the app sent it, the agent's own log recorded it, or the
 * agent's review screen showed it.
 *
 * The records live per pane in the phone's sealed chat store (see [ChatStore.saveQuestions]), not
 * in the reader's items, so a fresh read of the agent's log never drops them. [withQuestions] puts
 * them into the chat in order: each one after the log item that was newest when the question
 * appeared. Claude's own AskUserQuestion calls in its log are shown as the same kind of item, and a
 * recorded question that matches one is merged into it, so a question appears once.
 */

/** Where a question stands. */
enum class QState { OPEN, ANSWERED, ELSEWHERE, DISMISSED }

/** One question: for Claude's multi-question dialog, one per step ([title] is the step's name). */
data class QPart(
    val title: String? = null,
    val question: String = "",
    val details: List<String> = emptyList(),
    val command: String? = null,
    val options: List<String> = emptyList(),
    val answer: String? = null,
)

data class QuestionItem(
    override val key: String,
    val parts: List<QPart>,
    val state: QState = QState.OPEN,
    /** The agent that asked ("Claude"), for the label. */
    val agent: String? = null,
    /** The key of the log item that was newest when the question appeared; null when there was none. */
    val after: String? = null,
    /** When it was first seen, and when it was settled (milliseconds). */
    val at: Long = 0L,
    val doneAt: Long = 0L,
    /** What closed a multi-question dialog ("Submit answers", "Cancel"), when the app sent it. */
    val action: String? = null,
    /** The dialog it came from, for telling a new question from the same one seen again. */
    val sig: String = "",
    /** The answer as the agent's log recorded it, when it couldn't be split into one per question. */
    val recorded: String? = null,
) : Item

object QuestionLog {
    /** Questions kept per pane, newest last. */
    const val MAX_QUESTIONS = 100
    /** A question seen again this soon after it was settled is the same one (the screen redrew), not a new one. */
    const val SAME_WINDOW_MS = 30_000L

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** What tells one dialog from another: its steps for Claude's multi-question dialog, else its question and options. */
    fun signature(p: Prompt): String {
        val parts = p.shownParts()
        val steps = parts.tabs.filter { !it.submit }
        if (steps.size >= 2) return "steps:" + steps.joinToString("|") { norm(it.title) }
        return "q:" + norm(parts.heading ?: p.question) + "|" + p.choices.joinToString("|") { norm(it.label) }
    }

    /** The parts a prompt shows now: the current step filled in, the other steps by name only. */
    private fun partsOf(p: Prompt, old: List<QPart>): List<QPart> {
        val qp = p.shownParts()
        val steps = qp.tabs.filter { !it.submit }
        val here = QPart(
            title = null,
            question = qp.heading ?: p.question.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
            details = qp.details,
            command = qp.command,
            options = p.choices.map { it.label },
        )
        if (steps.size < 2) return listOf(here.copy(answer = old.firstOrNull()?.answer))
        val reviewing = qp.tabs.any { it.submit && it.current }
        // The review step lists "Step: answer" lines: answers given in the terminal, read off the screen.
        // They name each step by its title or by its question ("Close residue 537?: Close it").
        val shown = if (reviewing) qp.details.mapNotNull { d -> d.split(": ", limit = 2).takeIf { it.size == 2 }?.let { norm(it[0]) to it[1].trim() } }.toMap() else emptyMap()
        return steps.map { t ->
            val prev = old.firstOrNull { it.title == t.title }
            val base = if (t.current && !reviewing) here.copy(title = t.title) else (prev ?: QPart(title = t.title))
            val fromScreen = shown[norm(t.title)] ?: prev?.question?.let { shown[norm(it)] }
            base.copy(title = t.title, answer = prev?.answer ?: fromScreen?.takeIf { it.isNotBlank() })
        }
    }

    /** The log after a question card was seen on screen. */
    fun seen(log: List<QuestionItem>, p: Prompt, agent: String?, anchor: String?, now: Long): List<QuestionItem> {
        val sig = signature(p)
        val last = log.lastOrNull()
        if (last != null && last.sig == sig && (last.state == QState.OPEN || now - last.doneAt < SAME_WINDOW_MS)) {
            val upd = last.copy(parts = partsOf(p, last.parts), state = QState.OPEN, doneAt = 0L)
            return if (upd == last) log else log.dropLast(1) + upd
        }
        // A different question while one is still open: the open one went without an answer from here.
        val settled = if (last != null && last.state == QState.OPEN) log.dropLast(1) + settle(last, working = true, now) else log
        val item = QuestionItem(key = "q:$now:${sig.hashCode().toUInt().toString(16)}", parts = partsOf(p, emptyList()),
            agent = agent, after = anchor, at = now, sig = sig)
        return (settled + item).takeLast(MAX_QUESTIONS)
    }

    private fun settle(q: QuestionItem, working: Boolean, now: Long): QuestionItem {
        val answered = q.parts.any { it.answer != null } || q.action != null
        return q.copy(state = when {
            answered -> QState.ANSWERED
            working -> QState.ELSEWHERE
            else -> QState.DISMISSED
        }, doneAt = now)
    }

    /**
     * The log once the open question is gone from the screen: answered when the app sent an answer;
     * otherwise "answered elsewhere" if the agent went back to work ([working]), else "dismissed".
     */
    fun gone(log: List<QuestionItem>, working: Boolean, now: Long): List<QuestionItem> {
        val last = log.lastOrNull() ?: return log
        if (last.state != QState.OPEN) return log
        return log.dropLast(1) + settle(last, working, now)
    }

    /**
     * The log after the app answered [p] with [answer] (an option's label, typed text, or a key).
     * On Claude's review step the answer closes the dialog ([QuestionItem.action]).
     */
    fun answered(log: List<QuestionItem>, p: Prompt?, answer: String): List<QuestionItem> {
        val i = log.indexOfLast { it.state == QState.OPEN && (p == null || it.sig == signature(p)) }
        if (i < 0) return log
        val q = log[i]
        val qp = p?.shownParts()
        val current = qp?.tabs?.firstOrNull { it.current }
        val upd = when {
            current?.submit == true -> q.copy(action = answer)
            current != null && q.parts.size >= 2 -> q.copy(parts = q.parts.map { if (it.title == current.title) it.copy(answer = answer) else it })
            else -> q.copy(parts = q.parts.mapIndexed { n, it -> if (n == q.parts.lastIndex) it.copy(answer = answer) else it })
        }
        return log.toMutableList().also { it[i] = upd }
    }

    // ---- Claude's AskUserQuestion calls in its log ----------------------------------------------

    private val ANSWER_PAIR = Regex(""""([^"]+)"\s*=\s*"([^"]*)"""")

    /** Claude's AskUserQuestion tool call as a question item, with the answers its result recorded; null if it isn't one. */
    fun fromTool(t: ToolItem): QuestionItem? {
        if (t.name != "AskUserQuestion") return null
        val qs = runCatching { JSONObject(t.input ?: return null).optJSONArray("questions") }.getOrNull() ?: return null
        val answers = ANSWER_PAIR.findAll(t.out.orEmpty()).associate { norm(it.groupValues[1]) to it.groupValues[2] }
        val parts = (0 until qs.length()).mapNotNull { qs.optJSONObject(it) }.map { q ->
            val opts = q.optJSONArray("options")
            val text = q.optString("question")
            QPart(
                title = q.optString("header").ifBlank { null },
                question = text,
                options = (0 until (opts?.length() ?: 0)).mapNotNull { opts?.optJSONObject(it)?.optString("label")?.ifBlank { null } },
                answer = answers[norm(text)]?.ifBlank { null },
            )
        }
        if (parts.isEmpty()) return null
        val raw = if (parts.none { it.answer != null }) t.out?.trim()?.take(400)?.ifBlank { null } else null
        val state = when (t.state) {
            ToolState.RUNNING -> QState.OPEN
            ToolState.ERROR -> QState.DISMISSED
            ToolState.OK -> if (parts.any { it.answer != null } || raw != null) QState.ANSWERED else QState.ELSEWHERE
        }
        return QuestionItem(key = "ask:" + t.key, parts = parts, state = state, agent = "Claude", recorded = if (t.state == ToolState.OK) raw else null)
    }

    /**
     * The same question (1.3.2, from a code review: any shared step title, a contained string or
     * equal Yes/No options merged different questions): the same full set of question texts, or the same full
     * set of step titles; the same options only where one side has no question text.
     */
    internal fun sameQuestion(a: QuestionItem, b: QuestionItem): Boolean {
        val qa = a.parts.map { norm(it.question) }.filter { it.isNotEmpty() }
        val qb = b.parts.map { norm(it.question) }.filter { it.isNotEmpty() }
        if (qa.isNotEmpty() && qb.isNotEmpty()) return qa.toSet() == qb.toSet()
        val ta = a.parts.mapNotNull { it.title?.let(::norm) }.toSet()
        val tb = b.parts.mapNotNull { it.title?.let(::norm) }.toSet()
        if (ta.size >= 2 && ta == tb) return true
        fun opts(q: QuestionItem) = q.parts.map { p -> p.options.map(::norm).filter { it.isNotEmpty() && it != "typesomething" }.toSet() }.filter { it.size >= 2 }
        return opts(a).any { x -> opts(b).any { y -> x == y } }
    }

    /** A log question merged with the recorded one: the log's text, and each answer from whichever has it. */
    private fun merge(log: QuestionItem, mine: QuestionItem): QuestionItem {
        val parts = log.parts.map { p ->
            val po = p.options.map(::norm).toSet()
            val m = mine.parts.firstOrNull { (p.title != null && it.title == p.title) || norm(it.question) == norm(p.question) }
                ?: mine.parts.firstOrNull { po.size >= 2 && it.options.map(::norm).filter { o -> o != "typesomething" }.toSet() == po }
                ?: mine.parts.singleOrNull()?.takeIf { log.parts.size == 1 }
            p.copy(answer = p.answer ?: m?.answer, details = p.details.ifEmpty { m?.details.orEmpty() }, command = p.command ?: m?.command)
        }
        val state = when {
            parts.any { it.answer != null } || mine.action != null || log.recorded != null -> QState.ANSWERED
            log.state == QState.OPEN -> mine.state
            else -> log.state
        }
        return log.copy(parts = parts, state = state, action = mine.action, at = mine.at, doneAt = mine.doneAt)
    }

    /**
     * The chat with its questions in place: Claude's AskUserQuestion calls become question items
     * (merged with a matching recorded question), and each other recorded question follows the log
     * item it was asked after. One whose item isn't in the chat (fallen out of the window, or a pane
     * with no log) goes at the end, oldest first.
     */
    /**
     * The chat with each recorded question placed where it happened (1.4.3, Bill: answered questions
     * sat at the bottom, under newer messages). Claude's AskUserQuestion is merged into its tool call.
     * Every other question goes after the last item whose time ([times], the computer's clock) is at
     * or before the question's ([QuestionItem.at] on the phone's clock, less [hostOffsetMs], the phone
     * clock minus the computer's), and never before its anchor ([QuestionItem.after], the newest item
     * when it appeared). Items without a time take the time of the item before them. A question
     * older than every loaded item (the chat shows only its newest part) goes to the top. Without any
     * times (Grok's log has none), the anchor alone decides; a question with neither goes last, in
     * time order, as before.
     */
    fun withQuestions(items: List<Item>, questions: List<QuestionItem>, times: Map<String, Long> = emptyMap(), hostOffsetMs: Long = 0L,
                      /** When the app first saw each item (computer clock): one seen after a question was answered always goes below it. */
                      seen: Map<String, Long> = emptyMap()): List<Item> {
        if (questions.isEmpty() && items.none { it is ToolItem && it.name == "AskUserQuestion" }) return items
        val left = questions.toMutableList()
        // Pair each AskUserQuestion in the log with its recorded question first, so a recorded one
        // is never also placed after its anchor (which comes before the tool call in the chat).
        val asks = HashMap<String, QuestionItem>()
        for (it in items) {
            val asked = (it as? ToolItem)?.let(::fromTool) ?: continue
            val m = left.firstOrNull { q -> sameQuestion(asked, q) }
            if (m != null) left.remove(m)
            asks[it.key] = if (m != null) merge(asked, m) else asked
        }
        // Each item's time, carried forward over items that have none. Items before the first known
        // time (history read in bulk from a log without times) count as older than any question.
        val eff = LongArray(items.size)
        var last = Long.MIN_VALUE + 1
        items.forEachIndexed { i, it -> times[it.key]?.let { t -> if (t > last) last = t }; eff[i] = last }
        val timed = items.any { times[it.key] != null }
        val index = HashMap<String, Int>(items.size * 2).also { m -> items.forEachIndexed { i, it -> m[it.key] = i } }
        // Where each question goes: after item [pos], -1 for before every item.
        val at = HashMap<Int, MutableList<QuestionItem>>()
        for (q in left) {
            val anchor = q.after?.let { index[it] } ?: -1
            val pos = when {
                timed -> {
                    val qt = q.at - hostOffsetMs
                    var t = -1
                    for (i in eff.indices) if (eff[i] != Long.MIN_VALUE && eff[i] <= qt) t = i
                    maxOf(t, anchor)
                }
                anchor >= 0 -> anchor
                else -> items.size - 1
            }
            // Never below an item the app first saw after the question was settled (answered, or
            // gone): that message is newer than the answer, whatever its log time says (1.4.3, Bill).
            val settled = if (q.doneAt > 0) q.doneAt - hostOffsetMs else Long.MAX_VALUE
            val firstAfter = items.indices.firstOrNull { i -> seen[items[i].key]?.let { it > settled } == true } ?: items.size
            at.getOrPut(maxOf(anchor, minOf(pos, firstAfter - 1))) { mutableListOf() } += q
        }
        val out = ArrayList<Item>(items.size + questions.size)
        at[-1]?.let { out += it.sortedBy { q -> q.at } }
        items.forEachIndexed { i, it ->
            out += asks[it.key] ?: it
            at[i]?.let { out += it.sortedBy { q -> q.at } }
        }
        return out
    }

    // ---- On the phone ---------------------------------------------------------------------------

    fun toJson(q: QuestionItem): JSONObject = JSONObject().put("k", q.key).put("state", q.state.name).putOpt("agent", q.agent)
        .putOpt("after", q.after).put("at", q.at).put("doneAt", q.doneAt).putOpt("action", q.action).put("sig", q.sig)
        .putOpt("recorded", q.recorded)
        .put("parts", JSONArray(q.parts.map { p ->
            JSONObject().putOpt("title", p.title).put("q", p.question).put("details", JSONArray(p.details)).putOpt("cmd", p.command)
                .put("options", JSONArray(p.options)).putOpt("answer", p.answer)
        }))

    private fun JSONObject.s(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    fun fromJson(o: JSONObject): QuestionItem = QuestionItem(
        key = o.getString("k"),
        parts = o.getJSONArray("parts").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
                QPart(p.s("title"), p.optString("q"), p.optJSONArray("details").strings(), p.s("cmd"), p.optJSONArray("options").strings(), p.s("answer"))
            }
        },
        state = runCatching { QState.valueOf(o.optString("state")) }.getOrDefault(QState.DISMISSED),
        agent = o.s("agent"), after = o.s("after"), at = o.optLong("at"), doneAt = o.optLong("doneAt"),
        action = o.s("action"), sig = o.optString("sig"), recorded = o.s("recorded"),
    )

    /** The kept questions as JSON: the newest [MAX_QUESTIONS] within [ChatCodec.MAX_CHARS]. */
    fun encode(qs: List<QuestionItem>): String {
        val keep = ArrayList<JSONObject>()
        var chars = 0
        for (q in qs.asReversed()) {
            val j = toJson(q)
            val n = j.toString().length
            if (keep.size >= MAX_QUESTIONS || (chars + n > ChatCodec.MAX_CHARS && keep.isNotEmpty())) break
            keep += j
            chars += n
        }
        keep.reverse()
        return JSONObject().put("v", 1).put("questions", JSONArray(keep)).toString()
    }

    fun decode(s: String): List<QuestionItem>? = runCatching {
        val o = JSONObject(s)
        if (o.optInt("v") != 1) return null
        val a = o.getJSONArray("questions")
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }.getOrNull()

    /** The question as plain text, for copying, sharing and the Markdown export. */
    fun plain(q: QuestionItem): String = buildString {
        append(label(q)).append('\n')
        for (p in q.parts) {
            append('\n')
            p.title?.let { append(it).append('\n') }
            if (p.question.isNotBlank()) append(p.question).append('\n')
            p.details.forEach { append(it).append('\n') }
            p.command?.let { append("    ").append(it).append('\n') }
            if (p.options.isNotEmpty()) p.options.forEach { append("- ").append(it).append('\n') }
            p.answer?.let { append("Your answer: ").append(it).append('\n') }
        }
        q.action?.let { append("\nThen: ").append(it).append('\n') }
        q.recorded?.let { append("\nRecorded answer: ").append(it).append('\n') }
    }.trimEnd()

    /** "Question from Claude, answered", "..., answered elsewhere", "..., dismissed", "..., waiting for you". */
    fun label(q: QuestionItem): String {
        val who = q.agent?.let { "Question from $it" } ?: "Question"
        val n = q.parts.size
        val what = if (n >= 2) "$who ($n questions)" else who
        return what + ", " + when (q.state) {
            QState.OPEN -> "waiting for you"
            QState.ANSWERED -> "answered"
            QState.ELSEWHERE -> "answered elsewhere"
            QState.DISMISSED -> "dismissed"
        }
    }
}
