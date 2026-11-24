package com.billmongan.herdrdroid.voice

import com.billmongan.herdrdroid.store.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {
    private fun server(name: String, id: String = name) = Server(id = id, name = name, host = "127.0.0.1", port = 1, user = "test", herdrPath = "herdr")

    @Test fun settingsShowTheExamplesAndTheyMatchAServerWithANumber() {
        assertEquals(listOf("Switch to Codex on Lab Box", "Switch to Lab Box", "Send a message to Codex on Lab Box"),
            voiceCommandHelp.filter { it.contains("lab box", ignoreCase = true) })
        val saved = server("labbox2")
        // The number left off, said as a word, or misheard as "lob box": the one server it can mean.
        for (phrase in listOf("Switch to Lab Box", "Switch to Lab Box Two", "which too lob box", "which to lab box to")) {
            val command = parseVoiceCommand(phrase) as VoiceCommand.Switch
            assertEquals(phrase, listOf(saved), voiceServersNamed(listOf(saved), command.name))
        }
        val exact = server("Lab Box", id = "other")
        assertEquals("a server named exactly that wins", listOf(exact), voiceServersNamed(listOf(saved, exact), "Lab Box"))
    }

    /** No server name is built in: any saved name matches by the same rule, number words as digits. */
    @Test fun anySavedServerNameMatchesItsSpokenForm() {
        val servers = listOf(server("buildbox3"), server("lab-box-2"), server("Edge Node 12"), server("devbox"))
        assertEquals(listOf(servers[0]), voiceServersNamed(servers, "build box three"))
        assertEquals(listOf(servers[1]), voiceServersNamed(servers, "lab box two"))
        assertEquals(listOf(servers[1]), voiceServersNamed(servers, "Lab Box too"))
        assertEquals(listOf(servers[2]), voiceServersNamed(servers, "edge node twelve"))
        assertEquals(listOf(servers[3]), voiceServersNamed(servers, "dev box"))
        assertEquals(emptyList<Server>(), voiceServersNamed(servers, "build box four"))
        assertEquals(emptyList<Server>(), voiceServersNamed(servers, "printer"))
        assertEquals("two servers with the number left off: both, for the caller to ask",
            2, voiceServersNamed(listOf(server("node1"), server("node2")), "node").size)
    }

    @Test fun routesSpokenNamesAndNumbers() {
        assertEquals(VoiceCommand.Switch("codex", "lab box two"), parseVoiceCommand("Switch to Codex on lab box two."))
        assertEquals("labbox2", voiceName("lab box two"))
        assertEquals("buildbox3", voiceName("Build Box Three"))
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
        assertEquals("Message(pane=Switch(name=codex, server=lab box two))", parseVoiceCommand("Send a message to Codex on Lab Box Two.").toString())
        assertEquals("Dictate(text=Check README, then stop.)", parseVoiceCommand("Dictate: Check README, then stop.").toString())
        assertEquals("Repeat", parseVoiceCommand("Repeat that").toString())
        assertEquals("ConfirmMessage", parseVoiceCommand("Confirm message").toString())
        assertNull(parseVoiceCommand("Send a message to codex on"))
    }
    @Test fun normalizesObservedOfflineSwitchTranscriptions() {
        val saved = listOf(server("labbox2"))
        val command = parseVoiceCommand("which to codex on lab box to") as VoiceCommand.Switch
        assertEquals("codex", command.name)
        assertEquals(saved, voiceServersNamed(saved, command.server!!))
        val host = parseVoiceCommand("which too lob box to") as VoiceCommand.Switch
        assertEquals(saved, voiceServersNamed(saved, host.name))
        assertNull(parseVoiceCommand("[unk] confirm message"))
        assertNull(parseVoiceCommand("confirm"))
    }
}
