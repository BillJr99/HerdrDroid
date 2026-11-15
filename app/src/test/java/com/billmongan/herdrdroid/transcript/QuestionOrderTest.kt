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
        val chatNow = CachedChat(emptyList(), "", false, null).withRead(read)
        assertEquals(mapOf("u1" to T, "t1" to T + 10_000), chatNow.times)
        val back = ChatCodec.decode(ChatCodec.encode(chatNow))!!
        assertEquals(chatNow.times, back.times)
        // A later read adds its items' times to the ones kept.
        val more = TranscriptRead(true, null, "{}", false, false, null, listOf(JSONObject().put("k", "t3").put("t", "text").put("text", "x").put("ts", T + 30_000)))
        assertEquals(T + 30_000, back.withRead(more).times["t3"])
        assertEquals(T + 20_000, Transcript.parseRead("""{"ok":true,"items":[],"cur":{},"now":${T + 20_000}}""").hostNow)
    }
}
