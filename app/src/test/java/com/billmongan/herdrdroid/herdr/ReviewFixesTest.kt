package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.diag.DiagLog
import com.billmongan.herdrdroid.transcript.ToolItem
import com.billmongan.herdrdroid.transcript.ToolState
import com.billmongan.herdrdroid.transcript.Transcript
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.3.0: fixes from the code review of 1.2.9 (Grok, read only, 2026-11-14). */
class ReviewFixesTest {
    @Test fun aCustomHerdrPathIsOneShellWord() {
        assertEquals("'/opt/my herdr/bin/herdr'", programPath("/opt/my herdr/bin/herdr"))
        assertEquals("'/usr/bin/herdr; rm -rf ~'", programPath("/usr/bin/herdr; rm -rf ~"))
        assertEquals("\"\$HOME\"/'.local/bin/herdr'", programPath("~/.local/bin/herdr"))
        assertEquals("'it'\"'\"'s/herdr'", programPath("it's/herdr"))
    }

    @Test fun copilotsTwoRecordsOfOneToolCallAreOneCard() {
        val raw = listOf(
            """{"t":"tool","k":"o10.0","id":"call_1","name":"bash","title":"bash","kind":"bash","input":"ls","state":"run"}""",
            """{"t":"tool","k":"o20","id":"call_1","name":"bash","title":"bash","kind":"bash","input":"ls","state":"run"}""",
            """{"t":"result","k":"o30","ref":"call_1","out":"a.txt","err":false}""",
        ).map { JSONObject(it) }
        val items = Transcript.merge(emptyList(), raw, true)
        val tools = items.filterIsInstance<ToolItem>()
        assertEquals(1, tools.size)
        assertEquals("o10.0", tools.single().key)
        assertEquals(ToolState.OK, tools.single().state)
        assertEquals("a.txt", tools.single().out)
        // In two reads: the second record folds into the card already shown.
        val first = Transcript.merge(emptyList(), raw.take(1), true)
        val next = Transcript.merge(first, raw.drop(1), false)
        assertEquals(1, next.filterIsInstance<ToolItem>().size)
        assertEquals(ToolState.OK, next.filterIsInstance<ToolItem>().single().state)
    }

    @Test fun aConnectFailureLogsNoWebSocketAddress() {
        val e = java.io.IOException("Expected HTTP 101 response but was '403' from wss://secret.example.com/ssh?token=abc")
        val d = DiagLog.describe(e)
        assertFalse(d, d.contains("secret.example.com"))
        assertFalse(d, d.contains("token=abc"))
        assertTrue(d, d.contains("wss://<address hidden>"))
    }
}
