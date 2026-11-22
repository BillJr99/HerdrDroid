package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.transcript.LiveSession
import com.billmongan.herdrdroid.transcript.Transcript
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2.0 (Bill: "the pane should match the actual terminal session in use", and a Sessions card with a
 * New session button, "make sure the herdr and HerdrDroid are in sync on this at all times").
 */
class SessionControls220Test {
    private fun s(ref: String, db: String? = null) = PastSession(ref, db, 0, null, 0)

    @Test fun eachAgentsOwnCommands() {
        assertEquals("/clear", SessionControls.forAgent("claude")!!.newCommand)
        assertEquals("/resume 9989db43-2c5c-42c9-b31c-eab9c4663226",
            SessionControls.forAgent("claude")!!.resume(s("/h/.claude/projects/-w/9989db43-2c5c-42c9-b31c-eab9c4663226.jsonl")))
        assertEquals("01a1d64e-b42a-730c-b63e-d2e673458ac9",
            SessionControls.idOfLog("/h/.omp/agent/sessions/-x/2026-11-13T19-07-24-586Z_01a1d64e-b42a-730c-b63e-d2e673458ac9.jsonl"))
        assertEquals("/resume 01a1ff0c-fffa-7003-8ac9-b4cc39e8c1fb",
            SessionControls.forAgent("grok")!!.resume(s("/h/.grok/sessions/%2Fw/01a1ff0c-fffa-7003-8ac9-b4cc39e8c1fb/chat_history.jsonl")))
        assertEquals("/resume 01a1df76", SessionControls.forAgent("muse")!!.resume(s("/h/.local/share/muse/sessions/2026/11/22/01a1df76/session.jsonl")))
        // Agents whose resume takes no id open their own picker.
        for (a in listOf("codex", "opencode", "kiro", "vibe")) {
            val spec = SessionControls.forAgent(a)!!
            assertNull(a, spec.resume(s("/x/y.jsonl")))
            assertTrue(a, spec.picker != null)
        }
        assertEquals("/chat new", SessionControls.forAgent("kiro")!!.newCommand)
        assertEquals("/sessions", SessionControls.forAgent("opencode-college")!!.picker)
        assertNull(SessionControls.forAgent("agy"))
        // No input line the app can check before typing (see PaneControls220Test): no Sessions menu.
        for (a in listOf("pi", "omp", "copilot")) assertNull(a, SessionControls.forAgent(a))
        assertNull(SessionControls.forAgent("gemini"))
    }

    @Test fun anIdThatIsNotPlainIsNeverTyped() {
        assertNull(SessionControls.forAgent("claude")!!.resume(s("/x/a b; rm.jsonl")))
        assertNull(SessionControls.forAgent("grok")!!.resume(s("/x/\$(id)/chat_history.jsonl")))
    }

    @Test fun herdrIsFollowedOnlyWhereItKeepsTheSession() {
        assertFalse(SessionControls.forAgent("opencode")!!.herdrAtOnce)
        for (a in listOf("kiro", "vibe", "muse")) assertFalse(a, SessionControls.forAgent(a)!!.herdrTracks)
        assertTrue(SessionControls.forAgent("claude")!!.herdrTracks)
    }

    @Test fun theReaderIsToldThePaneButAHistoryPinStays() {
        val live = JSONObject(Transcript.withLive("""{"file":"/a","off":3}""", "w2:p5", "sid-1"))
        assertEquals("w2:p5", live.getString("pane"))
        assertEquals("sid-1", live.getString("hsid"))
        assertEquals("/a", live.getString("file"))
        assertFalse(JSONObject(Transcript.withLive("", "p", null)).has("hsid"))
        val pin = """{"pin":"/old.jsonl"}"""
        assertEquals(pin, Transcript.withLive(pin, "p", "s"))
    }

    @Test fun theReadersLiveSessionIsParsed() {
        val r = Transcript.parseRead("""{"ok":true,"cur":{},"items":[],"live":{"sid":"b","how":"claude-pid","ref":"/p/b.jsonl","herdr_stale":true}}""")
        assertEquals(LiveSession("b", "/p/b.jsonl", null, "claude-pid", true), r.live)
        assertTrue(r.live!!.isSession("/p/b.jsonl", null))
        assertFalse(r.live!!.isSession("/p/a.jsonl", null))
        val oc = LiveSession.parse(JSONObject("""{"sid":"ses_1","db":"/d.db","how":"herdr"}"""))!!
        assertTrue(oc.isSession("ses_1", "/d.db"))
        assertNull(Transcript.parseRead("""{"ok":true,"cur":{},"items":[]}""").live)
    }
}
