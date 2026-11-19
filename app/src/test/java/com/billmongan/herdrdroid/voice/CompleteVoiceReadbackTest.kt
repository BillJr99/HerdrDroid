package com.billmongan.herdrdroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteVoiceReadbackTest {
    @Test fun oversizedSpeechCannotAuthorizeTheOriginalDraft() {
        val messages = VoiceMessageDraft<String> { 0L }
        messages.begin("pane", "Demo")
        val draft = checkNotNull(messages.dictate("pane", "a".repeat(3801)))
        var speech: String? = null
        val accepted = speakCompleteReadback("Message to Demo. ${draft.text}. Say confirm message.") {
            speech = it
            messages.onReadBack(draft)
        }
        assertFalse(accepted)
        assertNull(speech)
        assertNull(messages.consume("pane"))
    }

    @Test fun completeSpeechAtTheLimitStillRequiresPlaybackCompletion() {
        val messages = VoiceMessageDraft<String> { 0L }
        val start = "Message to Demo. "
        val end = ". Say confirm message."
        val text = "x".repeat(3800 - start.length - end.length)
        messages.begin("pane", "Demo")
        val draft = checkNotNull(messages.dictate("pane", text))
        val fullSpeech = start + text + end
        var heard: (() -> Unit)? = null
        assertTrue(speakCompleteReadback(fullSpeech) { speech ->
            assertEquals(fullSpeech, speech)
            heard = { messages.onReadBack(draft) }
        })
        assertEquals("pane", messages.activeTarget { it == "pane" })
        checkNotNull(heard).invoke()
        assertEquals(text, messages.consume("pane")?.text)
        assertNull(messages.consume("pane"))
    }
}
