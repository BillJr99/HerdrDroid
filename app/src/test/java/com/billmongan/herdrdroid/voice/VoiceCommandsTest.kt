package com.billmongan.herdrdroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {
    @Test fun routesSpokenNamesAndNumbers() {
        assertEquals(VoiceCommand.Switch("codex", "mini pc two"), parseVoiceCommand("Switch to Codex on mini PC two."))
        assertEquals("minipc2", voiceName("mini PC two"))
        assertEquals(VoiceCommand.Choose(1), parseVoiceCommand("Choose option two"))
        assertEquals(VoiceCommand.Choose(9), parseVoiceCommand("Option 10"))
    }

    @Test fun requiresExplicitCommandsAndConfirmation() {
        assertEquals(VoiceCommand.ReadQuestion, parseVoiceCommand("Read the pending question"))
        assertEquals(VoiceCommand.Confirm, parseVoiceCommand("Confirm answer"))
        assertEquals(VoiceCommand.Cancel, parseVoiceCommand("Cancel answer"))
        for (text in listOf("yes", "approve", "option zero", "maybe confirm", "switch to", "hey herdr")) {
            assertNull(text, parseVoiceCommand(text))
        }
    }
}
