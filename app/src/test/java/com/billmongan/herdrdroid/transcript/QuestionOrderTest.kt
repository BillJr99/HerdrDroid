package com.billmongan.herdrdroid.transcript

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.4.3 (Bill: "the questions from Claude answered are appearing at the bottom of the chat but they
 * should appear in proper temporal order so that new messages appear below them"): each recorded
 * question sits where it happened, by the times in the agent's log.
 */
class QuestionOrderTest {
    private val T = 1_800_000_000_000L   // a computer-clock time, in ms
    private fun q(key: String, at: Long, after: String? = null) =
        QuestionItem(key, listOf(QPart(question = "Go on? $key", options = listOf("Yes", "No"), answer = "Yes")), QState.ANSWERED, "Claude", after, at)
    private fun keys(items: List<Item>) = items.map { it.key }

    private val chat = listOf<Item>(UserItem("u1", "start"), TextItem("t1", "working"), TextItem("t2", "next step"), UserItem("u2", "more"), TextItem("t3", "done"))
    private val times = mapOf("u1" to T, "t1" to T + 10_000, "t2" to T + 60_000, "u2" to T + 120_000, "t3" to T + 130_000)

    @Test fun anAnsweredQuestionSitsBeforeTheMessagesThatCameAfterIt() {
        // Asked 30 s after t1, answered; t2 and the rest came later.
        val shown = QuestionLog.withQuestions(chat, listOf(q("qa", T + 30_000, after = "t1")), times)
        assertEquals(listOf("u1", "t1", "qa", "t2", "u2", "t3"), keys(shown))
    }

    @Test fun aQuestionSeenWithNoAnchorStillGoesByTime() {
        // Seen by the background watcher before the chat was loaded: no anchor, only its time.
        val shown = QuestionLog.withQuestions(chat, listOf(q("qa", T + 125_000)), times)
        assertEquals(listOf("u1", "t1", "t2", "u2", "qa", "t3"), keys(shown))
    }

    @Test fun severalQuestionsEachInItsPlace() {
        val qs = listOf(q("q3", T + 140_000), q("q1", T + 5_000, after = "u1"), q("q2", T + 61_000, after = "t2"), q("q2b", T + 62_000, after = "t2"))
        val shown = QuestionLog.withQuestions(chat, qs, times)
        assertEquals(listOf("u1", "q1", "t1", "t2", "q2", "q2b", "u2", "t3", "q3"), keys(shown))
    }

    @Test fun aTrimmedWindowPutsOlderQuestionsAtTheTop() {
        // The chat now shows only its newest part (a reload, or the kept copy trimmed): the anchor is gone.
        val window = chat.drop(3)
        val shown = QuestionLog.withQuestions(window, listOf(q("old", T + 30_000, after = "t1"), q("new", T + 125_000, after = "u2")), times)
        assertEquals(listOf("old", "u2", "new", "t3"), keys(shown))
        // Reloaded in full, the same questions go back where they were.
        assertEquals(listOf("u1", "t1", "old", "t2", "u2", "new", "t3"),
            keys(QuestionLog.withQuestions(chat, listOf(q("old", T + 30_000, after = "t1"), q("new", T + 125_000, after = "u2")), times)))
    }

    @Test fun inexactTimesAndClockSkew() {
        // Items without a time take the time of the item before them (t2 here has none).
        val gappy = times - "t2"
        assertEquals(listOf("u1", "t1", "t2", "qa", "u2", "t3"), keys(QuestionLog.withQuestions(chat, listOf(q("qa", T + 61_000)), gappy)))
        // The phone's clock runs 90 s ahead of the computer's: the offset takes it back.
        val offset = 90_000L
        assertEquals(listOf("u1", "t1", "qa", "t2", "u2", "t3"),
            keys(QuestionLog.withQuestions(chat, listOf(q("qa", T + 30_000 + offset)), times, offset)))
        // Unknown skew putting it too early never puts it before its anchor (the newest item when it appeared).
        assertEquals(listOf("u1", "t1", "t2", "qa", "u2", "t3"),
            keys(QuestionLog.withQuestions(chat, listOf(q("qa", T - 999_999, after = "t2")), times)))
        // A tie goes after the item with the same time.
        assertEquals(listOf("u1", "t1", "qa", "t2", "u2", "t3"), keys(QuestionLog.withQuestions(chat, listOf(q("qa", T + 10_000)), times)))
    }

    @Test fun withoutTimesTheAnchorDecides() {
        // A log with no times (Grok's): the anchor alone, and a question with neither goes last.
        assertEquals(listOf("u1", "t1", "qa", "t2", "u2", "t3", "qz"),
            keys(QuestionLog.withQuestions(chat, listOf(q("qa", 5, after = "t1"), q("qz", 9)))))
    }

    @Test fun claudesAskUserQuestionStaysOnItsToolCall() {
        val input = """{"questions":[{"question":"Which database?","header":"DB","options":[{"label":"SQLite"},{"label":"Postgres"}]}]}"""
        val tool = ToolItem("tool9", "c9", "AskUserQuestion", "AskUserQuestion", "other", input, null, null, "User has answered your questions: \"Which database?\"=\"SQLite\".", ToolState.OK)
        val items = chat.take(2) + tool + chat.drop(2)
        val shown = QuestionLog.withQuestions(items, emptyList(), times + ("tool9" to T + 20_000))
        assertEquals(listOf("u1", "t1", "ask:tool9", "t2", "u2", "t3"), keys(shown))
    }

    @Test fun timesSurviveTheReadTheKeptCopyAndAReload() {
        val raw = listOf(JSONObject().put("k", "u1").put("t", "user").put("text", "start").put("ts", T),
            JSONObject().put("k", "t1").put("t", "text").put("text", "working").put("ts", T + 10_000),
            JSONObject().put("k", "t2").put("t", "text").put("text", "no time"))
        val read = TranscriptRead(true, null, "{}", true, false, null, raw, hostNow = T + 20_000)
        val chatNow = CachedChat(emptyList(), "", false, null).withRead(read, T + 20_000)
        // The log's times, and the app's own for the item without one.
        assertEquals(mapOf("u1" to T, "t1" to T + 10_000, "t2" to T + 20_000), chatNow.times)
        val back = ChatCodec.decode(ChatCodec.encode(chatNow))!!
        assertEquals(chatNow.times, back.times)
        // A later read adds its items' times to the ones kept.
        val more = TranscriptRead(true, null, "{}", false, false, null, listOf(JSONObject().put("k", "t3").put("t", "text").put("text", "x").put("ts", T + 30_000)))
        assertEquals(T + 30_000, back.withRead(more).times["t3"])
        assertEquals(T + 20_000, Transcript.parseRead("""{"ok":true,"items":[],"cur":{},"now":${T + 20_000}}""").hostNow)
    }
    // ---- Logs without times (Grok): the app's own clock (1.4.3, Bill) ----

    private fun raw(vararg ks: String) = ks.map { JSONObject().put("k", it).put("t", "text").put("text", it) }
    private fun read(reset: Boolean, vararg ks: String) = TranscriptRead(true, null, "{}", reset, false, null, raw(*ks))

    @Test fun grokQuestionsGoByWhenTheAppSawEachMessage() {
        val t0 = T
        var c = CachedChat(emptyList(), "", false, null, agent = "grok").withRead(read(true, "a", "b"), t0)
        val q = q("qg", t0 + 10_000)          // no anchor: seen by the watcher in the background
        c = c.withRead(read(false, "c"), t0 + 20_000)
        assertEquals(listOf("a", "b", "qg", "c"), keys(QuestionLog.withQuestions(c.items, listOf(q), c.times)))
        // Kept and reopened, then the log read again from the start with one new message.
        c = ChatCodec.decode(ChatCodec.encode(c))!!.withRead(read(true, "a", "b", "c", "d"), t0 + 30_000)
        assertEquals(listOf("a", "b", "qg", "c", "d"), keys(QuestionLog.withQuestions(c.items, listOf(q), c.times)))
        // Earlier messages loaded above them later are older than what was on screen, not newer.
        val older = CachedChat(raw("x", "y").map { Transcript.toItem(it)!! } + c.items, "", false, null, times = c.times)
        val stamped = older.copy(times = Transcript.stampSeen(older.times, older.items, t0 + 40_000))
        assertEquals(listOf("x", "y", "a", "b", "qg", "c", "d"), keys(QuestionLog.withQuestions(stamped.items, listOf(q), stamped.times)))
        // The window trimmed to its newest part: the times are still known.
        val trimmed = c.items.drop(1)
        assertEquals(listOf("b", "qg", "c", "d"), keys(QuestionLog.withQuestions(trimmed, listOf(q), c.times)))
    }

    @Test fun aMessageFirstSeenAfterAReloadIsNewer() {
        // A log with times for some items and none for others: the log's times win, the rest are the app's.
        val mixed = listOf(JSONObject().put("k", "a").put("t", "text").put("text", "a").put("ts", T)) + raw("b")
        var c = CachedChat(emptyList(), "", false, null).withRead(TranscriptRead(true, null, "{}", true, false, null, mixed), T + 50_000)
        assertEquals(T, c.times["a"]); assertEquals(T + 50_000, c.times["b"])
        c = ChatCodec.decode(ChatCodec.encode(c))!!.withRead(read(false, "c"), T + 90_000)
        assertEquals(T + 50_000, c.times["b"])   // kept across the reopen
        assertEquals(T + 90_000, c.times["c"])
        val shown = QuestionLog.withQuestions(c.items, listOf(q("q1", T + 60_000), q("q0", T + 1_000)), c.times)
        assertEquals(listOf("a", "q0", "b", "q1", "c"), keys(shown))
    }
    // ---- Bill's rule, for every agent: a message the app first sees after a question was answered
    // goes below it, with or without log times, across reloads and reopens (1.4.3). ----

    /** Each agent's item keys, and whether its log gives times (Grok's doesn't). */
    private val agents = listOf(
        Triple("claude", "k%d.0", true), Triple("codex", "c%d", true), Triple("grok", "g%d", false),
        Triple("copilot", "e%d", true), Triple("kiro", "k%d.1", true), Triple("vibe", "v%d", true),
        Triple("pi", "p%d", true), Triple("omp", "m%d", true), Triple("opencode", "o%d", true), Triple("hermes", "h%d", true),
    )

    private fun rawFor(fmt: String, timed: Boolean, vararg pairs: Pair<Int, Long>) = pairs.map { (n, ts) ->
        JSONObject().put("k", fmt.format(n)).put("t", "text").put("text", "m$n").apply { if (timed) put("ts", ts) } }

    @Test fun aMessageSeenAfterTheAnswerGoesBelowTheQuestionForEveryAgent() {
        for ((agent, fmt, timed) in agents) {
            val k = { n: Int -> fmt.format(n) }
            // The chat read from the start: two messages.
            var c = CachedChat(emptyList(), "", false, null, agent = agent)
                .withRead(TranscriptRead(true, null, "{}", true, false, null, rawFor(fmt, timed, 1 to T, 2 to T + 10_000)), T + 15_000)
            // The question appears (anchored on message 2) and is answered at T + 40 s.
            val q = q("q-$agent", T + 20_000, after = k(2)).copy(doneAt = T + 40_000)
            // Message 3 arrives after the answer, though its log time (a skewed clock) says T + 25 s.
            c = c.withRead(TranscriptRead(true, null, "{}", false, false, null, rawFor(fmt, timed, 3 to T + 25_000)), T + 45_000)
            assertEquals(agent, listOf(k(1), k(2), "q-$agent", k(3)), keys(QuestionLog.withQuestions(c.items, listOf(q), c.times, 0L, c.seen)))
            // Kept and reopened, a message first seen then also goes below it.
            c = ChatCodec.decode(ChatCodec.encode(c))!!
            c = c.withRead(TranscriptRead(true, null, "{}", false, false, null, rawFor(fmt, timed, 4 to T + 26_000)), T + 90_000)
            assertEquals(agent, listOf(k(1), k(2), "q-$agent", k(3), k(4)), keys(QuestionLog.withQuestions(c.items, listOf(q), c.times, 0L, c.seen)))
            // The log read again from the start: nothing moves.
            c = c.withRead(TranscriptRead(true, null, "{}", true, false, null,
                rawFor(fmt, timed, 1 to T, 2 to T + 10_000, 3 to T + 25_000, 4 to T + 26_000)), T + 120_000)
            assertEquals(agent, listOf(k(1), k(2), "q-$agent", k(3), k(4)), keys(QuestionLog.withQuestions(c.items, listOf(q), c.times, 0L, c.seen)))
        }
    }

    @Test fun antigravityHasNoChatSoItsQuestionsListInTimeOrder() {
        // Antigravity keeps no log the app can read: its screen view is the current screen, redrawn on
        // every read, with no lasting messages to place a question among, so its questions are listed
        // in the order they were asked, above that screen.
        val shown = QuestionLog.withQuestions(emptyList(), listOf(q("b", T + 2), q("a", T + 1), q("c", T + 3)))
        assertEquals(listOf("a", "b", "c"), keys(shown))
    }
}
