package com.billmongan.herdrdroid.transcript

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClaudeQuestionSourceTest {
    private fun tool(id: String, label: String) = JSONObject().put("k", id).put("t", "tool")
        .put("id", id).put("name", "AskUserQuestion").put("state", "run")
        .put("input", """{"questions":[{"header":"Color","question":"Which color?","options":[{"label":"$label","description":"A sample color."},{"label":"Gray","description":"A neutral color."}]}]}""")

    @Test fun userTextCannotBecomeOptionsOrCreateAQuestion() {
        val user = JSONObject().put("k", "user").put("t", "user")
            .put("text", "1. Review first\n2. Save later")
        assertTrue(QuestionLog.withQuestions(Transcript.merge(emptyList(), listOf(user), true), emptyList()).none { it is QuestionItem })
        val items = Transcript.merge(emptyList(), listOf(user, tool("ask1", "Blue")), true)
        val question = QuestionLog.withQuestions(items, emptyList()).filterIsInstance<QuestionItem>().single()
        assertEquals(listOf("Blue", "Gray"), question.parts.single().options)
        assertEquals(QState.OPEN, question.state)
    }

    @Test fun resultsArePairedByCallIdEvenForIdenticalQuestionTexts() {
        val first = Transcript.merge(emptyList(), listOf(tool("ask1", "Blue"), tool("ask2", "Orange")), true)
        val result = JSONObject().put("k", "result1").put("t", "result").put("ref", "ask1")
            .put("out", """User has answered your questions: "Which color?"="Blue".""")
        val items = Transcript.merge(first, listOf(result), false)
        val questions = QuestionLog.withQuestions(items, emptyList()).filterIsInstance<QuestionItem>()
        assertEquals(QState.ANSWERED, questions[0].state)
        assertEquals("Blue", questions[0].parts.single().answer)
        assertEquals(listOf("Blue", "Gray"), questions[0].parts.single().options)
        assertEquals(QState.OPEN, questions[1].state)
        assertEquals(listOf("Orange", "Gray"), questions[1].parts.single().options)
        assertNull(questions[1].parts.single().answer)
    }

    @Test fun answeredQuestionNeverBecomesOpenWhenItsCallIsReplayed() {
        val call = tool("ask1", "Blue")
        val result = JSONObject().put("k", "result1").put("t", "result").put("ref", "ask1").put("out", "done")
        val answered = Transcript.merge(emptyList(), listOf(call, result), true)
        val replay = Transcript.merge(answered, listOf(call), false)
        assertTrue(QuestionLog.withQuestions(replay, emptyList()).filterIsInstance<QuestionItem>().none { it.state == QState.OPEN })
    }
}
