package com.billmongan.herdrdroid.voice

import com.billmongan.herdrdroid.store.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {
    @Test fun settingsSayLaptopAndTheOldServerAliasStillWorks() {
        assertEquals(listOf("Switch to Codex on Laptop", "Switch to Laptop", "Send a message to Codex on Laptop"),
            voiceCommandHelp.filter { it.contains("laptop", ignoreCase = true) })
        val saved = Server(id = "test", name = "laptop2", host = "laptop2", port = 22, user = "test", herdrPath = "herdr")
        for (phrase in listOf("Switch to Laptop", "Switch to Laptop Two", "which too many pc")) {
            val command = parseVoiceCommand(phrase) as VoiceCommand.Switch
            assertEquals(listOf(saved), voiceServersNamed(listOf(saved), command.name))
        }
        val exact = saved.copy(id = "other", name = "Laptop", host = "laptop")
        assertEquals(listOf(exact), voiceServersNamed(listOf(saved, exact), "Laptop"))
    }

    @Test fun routesSpokenNamesAndNumbers() {
        assertEquals(VoiceCommand.Switch("codex", "laptop two"), parseVoiceCommand("Switch to Codex on laptop two."))
        assertEquals("laptop2", voiceName("laptop two"))
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
        assertEquals("Message(pane=Switch(name=codex, server=laptop two))", parseVoiceCommand("Send a message to Codex on Laptop Two.").toString())
        assertEquals("Dictate(text=Check README, then stop.)", parseVoiceCommand("Dictate: Check README, then stop.").toString())
        assertEquals("Repeat", parseVoiceCommand("Repeat that").toString())
        assertEquals("ConfirmMessage", parseVoiceCommand("Confirm message").toString())
        assertNull(parseVoiceCommand("Send a message to codex on"))
    }
    @Test fun normalizesObservedOfflineSwitchTranscriptions() {
        val command = parseVoiceCommand("which to codex on laptop to") as VoiceCommand.Switch
        assertEquals("codex", command.name)
        assertEquals("laptop2", voiceName(command.server!!))
        val host = parseVoiceCommand("which too many pc to") as VoiceCommand.Switch
        assertEquals("laptop2", voiceName(host.name))
        assertNull(parseVoiceCommand("[unk] confirm message"))
        assertNull(parseVoiceCommand("confirm"))
    }
}
