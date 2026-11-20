package com.billmongan.herdrdroid.voice

import com.billmongan.herdrdroid.store.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {
    @Test fun settingsSayMiniPcAndTheOldServerAliasStillWorks() {
        assertEquals(listOf("Switch to Codex on Mini PC", "Switch to Mini PC", "Send a message to Codex on Mini PC"),
            voiceCommandHelp.filter { it.contains("mini pc", ignoreCase = true) })
        val saved = Server(id = "test", name = "minipc2", host = "minipc2", port = 22, user = "test", herdrPath = "herdr")
        for (phrase in listOf("Switch to Mini PC", "Switch to Mini PC Two", "which too many pc")) {
            val command = parseVoiceCommand(phrase) as VoiceCommand.Switch
            assertEquals(listOf(saved), voiceServersNamed(listOf(saved), command.name))
        }
        val exact = saved.copy(id = "other", name = "Mini PC", host = "minipc")
        assertEquals(listOf(exact), voiceServersNamed(listOf(saved, exact), "Mini PC"))
    }

    @Test fun routesSpokenNamesAndNumbers() {
        assertEquals(VoiceCommand.Switch("codex", "mini pc two"), parseVoiceCommand("Switch to Codex on mini PC two."))
        assertEquals("minipc2", voiceName("mini PC two"))
        assertEquals(VoiceCommand.Choose(1), parseVoiceCommand("Choose option two"))
        assertEquals(VoiceCommand.Choose(9), parseVoiceCommand("Option 10"))
        assertEquals(VoiceCommand.Choose(0), parseVoiceCommand("Answer option one"))
    }

    @Test fun requiresExplicitCommandsAndConfirmation() {
        assertEquals("ListQuestions", parseVoiceCommand("List questions").toString())
        assertEquals(VoiceCommand.ReadQuestion, parseVoiceCommand("Read the pending approval"))
        assertEquals(VoiceCommand.Confirm, parseVoiceCommand("Confirm approval"))
        assertEquals(VoiceCommand.Cancel, parseVoiceCommand("Cancel approval"))
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
