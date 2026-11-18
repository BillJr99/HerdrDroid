package com.billmongan.herdrdroid.voice

import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.UserItem
import org.junit.Assert.*
import org.junit.Test

class VoiceSessionTest {
    @Test fun messageIsConsumedExactlyOnceAfterReadback() {
        val session = VoiceMessageDraft<String> { 100L }
        session.begin("server/pane/visit", "Test pane")
        val draft = session.dictate("server/pane/visit", "Check the build")!!
        session.onReadBack(draft)
        assertEquals(draft, session.consume("server/pane/visit"))
        assertNull(session.consume("server/pane/visit"))
    }

    @Test fun noSendWithoutReadbackOrAfterStopCancellationTargetChangeOrTimeout() {
        var now = 0L
        val session = VoiceMessageDraft<String> { now }
        fun draft() {
            session.begin("visit1", "Test pane")
            session.onReadBack(session.dictate("visit1", "Check the build")!!)
        }
        session.begin("visit1", "Test pane")
        val unread = session.dictate("visit1", "Unread")!!
        assertNull(session.consume("visit1"))
        draft()
        session.onReadBack(unread)
        assertNull(session.consume("visit2"))
        assertNull(session.consume("visit1"))
        draft()
        session.clear()
        assertNull(session.consume("visit1"))
        draft()
        now = 45_000
        assertNull(session.consume("visit1"))
    }

    @Test fun announcementsSkipHistoryUserEchoAndWorkingTextAndReadCompletedReplyOnlyOnce() {
        val monitor = NewVoiceReplies()
        val old = TextItem("old", "Old private reply")
        val user = UserItem("prompt", "Check the build")
        val partial = TextItem("reply", "Running")
        val final = partial.copy(text = "Build passed")
        assertNull(monitor.observe(VoiceReplySnapshot("a", Status.DONE, listOf(old))))
        assertNull(monitor.observe(VoiceReplySnapshot("a", Status.WORKING, listOf(old, user, partial))))
        val snapshot = VoiceReplySnapshot("a", Status.DONE, listOf(old, user, final))
        assertNull(monitor.observe(snapshot))
        assertEquals("Build passed", monitor.observe(snapshot))
        assertNull(monitor.observe(snapshot))
        assertNull(monitor.observe(snapshot.copy(session = "b")))
        assertNull(monitor.observe(snapshot.copy(session = "b")))
        monitor.clear()
        assertNull(monitor.observe(snapshot))
    }

    @Test fun everyDocumentedCommandParses() {
        voiceCommandHelp.forEach { assertNotNull(it, parseVoiceCommand(it)) }
        listOf("dictate /reset", "dictate [unk] test", "confirm message tomorrow", "send a message to .")
            .forEach { assertNull(it, parseVoiceCommand(it)) }
    }
}
