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
    @Test fun recognizesNamedMessageAndPreservesDictatedCaseAndPunctuation() {
        assertEquals("Message(pane=Switch(name=codex, server=mini pc two))", parseVoiceCommand("Send a message to Codex on Mini PC Two.").toString())
        assertEquals("Dictate(text=Check README, then stop.)", parseVoiceCommand("Dictate: Check README, then stop.").toString())
        assertEquals("Repeat", parseVoiceCommand("Repeat that").toString())
        assertEquals("ConfirmMessage", parseVoiceCommand("Confirm message").toString())
        assertNull(parseVoiceCommand("Send a message to codex on"))
    }
    @Test fun normalizesObservedOfflineSwitchTranscriptions() {
        val command = parseVoiceCommand("which to codex on mini pc to") as VoiceCommand.Switch
        assertEquals("codex", command.name)
        assertEquals("minipc2", voiceName(command.server!!))
        val host = parseVoiceCommand("which too many pc to") as VoiceCommand.Switch
        assertEquals("minipc2", voiceName(host.name))
        assertNull(parseVoiceCommand("[unk] confirm message"))
        assertNull(parseVoiceCommand("confirm"))
    }
}
