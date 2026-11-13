package com.billmongan.herdrdroid.transcript

import com.billmongan.herdrdroid.herdr.PastSession
import com.billmongan.herdrdroid.herdr.parseSessions
import com.billmongan.herdrdroid.herdr.safeName
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTest {
    private val items = listOf(
        UserItem("1", "Fix the build"),
        ThinkItem("2", "secret thoughts", null),
        ToolItem("3", null, "Bash", "gradle build", "shell", null, null, null, "BUILD OK", ToolState.OK),
        TextItem("4", "Done, the build passes."),
        UserItem("5", "Thanks"),
        TextItem("6", "You're welcome."),
        TextItem("7", "Anything else?"),
    )

    @Test fun markdownHasSpeakersToolsAndNoThinking() {
        val md = toMarkdown("Build fix", "Claude", items)
        assertTrue(md.startsWith("# Build fix\n"))
        assertTrue(md.contains("## You\n\nFix the build"))
        assertTrue(md.contains("- **Bash**: gradle build"))
        assertTrue(md.contains("```\nBUILD OK\n```"))
        assertFalse(md.contains("secret thoughts"))
        // One heading per run of the same speaker.
        assertEquals(2, Regex("## Claude").findAll(md).count())
    }

    @Test fun lastReplyIsAfterYourLastMessage() {
        assertEquals("You're welcome.\n\nAnything else?", lastReply(items))
        assertEquals("", lastReply(listOf(UserItem("1", "hi"))))
    }

    @Test fun safeNamesRejectPathsAndDots() {
        assertEquals("notes.md", safeName(" notes.md "))
        assertNull(safeName("../x"))
        assertNull(safeName("a/b"))
        assertNull(safeName(".."))
        assertNull(safeName("  "))
    }

    @Test fun sessionsParseAndPin() {
        val arr = JSONArray()
            .put(JSONObject().put("ref", "/h/.claude/projects/x/a.jsonl").put("when", 100).put("title", "Hello").put("kb", 3))
            .put(JSONObject().put("ref", "ses_1").put("db", "/h/opencode.db").put("when", 50).put("title", JSONObject.NULL))
            .put(JSONObject().put("when", 1))
        val s = parseSessions(arr)
        assertEquals(2, s.size)
        assertEquals("""{"pin":"/h/.claude/projects/x/a.jsonl"}""", s[0].pinCursor())
        val o = JSONObject(s[1].pinCursor())
        assertEquals("ses_1", o.getString("pin_sid"))
        assertEquals("/h/opencode.db", o.getString("db"))
        assertNull(s[1].title)
        assertEquals(PastSession("r", null, 0, null, 0).db, null)
    }
}
