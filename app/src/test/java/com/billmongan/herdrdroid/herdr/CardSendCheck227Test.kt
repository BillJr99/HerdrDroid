package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 2.2.7 (Bill, "check right before send"): a card's keys go in one command that first checks, on
 * the computer, that the pane's occupant and screen are the ones the card was checked against.
 */
class CardSendCheck227Test {
    private val getJson = """{"result":{"pane":{"pane_id":"p1","tab_id":"t1","workspace_id":"w1","agent":"hermes","agent_status":"blocked",""" +
        """"cwd":"/fixture","terminal_id":"term-1","agent_session":{"kind":"id","agent":"hermes","value":"sess-A"},"scroll":{"offset_from_bottom":0}}}}"""
    private val screen = "\u001b[1m⚠ Dangerous Command\u001b[0m\n❯ 1. Allow once\n  2. Deny\n↑/↓ to select, Enter to confirm (57s)\n⚠ ❯\n"

    private fun guard(json: String = getJson, visible: String = screen) =
        CardGuard(cardOccupantDigest(JSONObject(json).getJSONObject("result").getJSONObject("pane")), cardScreenDigest(visible))

    /** The transport seam: answers the combined command with [exit], and keeps what it was sent. */
    private class Seam(var exit: Int = 0) : Herdr.Commands {
        override val connection: Any? = Any()
        val writes = mutableListOf<String>()
        override suspend fun bulk(command: String) = ExecResult(0, "", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            writes += command
            return ExecResult(exit, "", if (exit == 0) "" else "fixture exit $exit")
        }
    }

    private fun client(seam: Seam, backend: Backend = Backend.TUI) =
        Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), "herdr", backend, null, seam)

    @Test fun aMatchIsSentInOneCommandCarryingOnlyThePaneAndTwoDigests() = runBlocking {
        val seam = Seam()
        val g = guard()
        client(seam).sendCardInput("p1", CardInput.Keys(listOf("down", "enter")), g, seam.connection!!)
        val c = seam.writes.single()
        assertTrue(c.contains(g.occupant) && c.contains(g.screen))
        assertTrue("the keys go only after the check", c.indexOf(" && ") < c.indexOf("'pane' 'send-keys' 'p1' 'down' 'enter'"))
        assertFalse("no prompt text in the check", c.contains("Allow once") || c.contains("Dangerous"))
    }

    @Test fun aChangedPaneOrScreenIsNotSentAndSaysSo() = runBlocking<Unit> {
        val seam = Seam(CARD_CHANGED_EXIT)
        assertThrows(CardChanged::class.java) {
            runBlocking { client(seam).sendCardInput("p1", CardInput.Keys(listOf("enter")), guard(), seam.connection!!) }
        }
        assertEquals("The question changed; not sent.", CardChanged().message)
    }

    @Test fun aFailedCheckIsNotSentAndReported() = runBlocking<Unit> {
        for (exit in listOf(CARD_CHECK_FAILED_EXIT, 127)) {
            val seam = Seam(exit)
            assertThrows(CardCheckFailed::class.java) {
                runBlocking { client(seam).sendCardInput("p1", CardInput.Text("1", enter = true), guard(), seam.connection!!) }
            }
        }
        // Any other failure (the send itself) stays a failure of delivery, not "not sent".
        val seam = Seam(1)
        assertThrows(HerdrError::class.java) {
            runBlocking { client(seam).sendCardInput("p1", CardInput.Keys(listOf("enter")), guard(), seam.connection!!) }
        }
    }

    @Test fun withoutAGuardTheKeysGoAsBefore() = runBlocking {
        val seam = Seam()
        client(seam).sendCardInput("p1", CardInput.Keys(listOf("enter")), null, seam.connection!!)
        assertEquals(listOf("\"\$(command -v herdr || echo \"\$HOME/.local/bin/herdr\")\" 'pane' 'send-keys' 'p1' 'enter'"), seam.writes)
    }

    @Test fun aCountdownChangesNeitherDigestButAnythingElseDoes() {
        assertEquals(cardScreenDigest(screen), cardScreenDigest(screen.replace("(57s)", "(56s)")))
        assertNotEquals(cardScreenDigest(screen), cardScreenDigest(screen.replace("Allow once", "Allow always")))
        assertNotEquals(guard().occupant, guard(getJson.replace("sess-A", "sess-B")).occupant)
        assertNotEquals(guard().occupant, guard(getJson.replace("term-1", "term-2")).occupant)
    }

    // ---- The check itself, in a real shell with python3 and a stand-in herdr ------------------

    private fun tool(name: String) = listOf("/usr/bin/$name", "/bin/$name").map { File(it) }.firstOrNull { it.canExecute() }

    /** Runs the real command against a stand-in herdr that serves [json] and [visible] and logs what it is sent. */
    private fun runReal(g: CardGuard, json: String, visible: String, input: CardInput = CardInput.Keys(listOf("down", "enter"))): Pair<Int, String> {
        val sh = tool("sh")
        assumeTrue("needs sh and python3", sh != null && tool("python3") != null)
        val dir = java.nio.file.Files.createTempDirectory("hd227-card").toFile()
        try {
            File(dir, "get.json").writeText(json)
            File(dir, "screen.txt").writeText(visible)
            val herdr = File(dir, "herdr")
            herdr.writeText("#!/bin/sh\nd=\$(dirname \"\$0\")\ncase \"\$2\" in\n" +
                "  get) cat \"\$d/get.json\" ;;\n  read) cat \"\$d/screen.txt\" ;;\n" +
                "  send-keys|send-text) shift; echo \"\$*\" >> \"\$d/sent.log\" ;;\n  *) exit 9 ;;\nesac\n")
            herdr.setExecutable(true)
            val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), herdr.path, Backend.TUI, null)
            val p = ProcessBuilder(sh!!.path, "-c", h.cardSendCommand("p1", input, g)).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            val exit = p.waitFor()
            return exit to File(dir, "sent.log").takeIf { it.exists() }?.readText().orEmpty()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theRealCheckSendsOnAMatchEvenAsTheCountdownRuns() {
        val (exit, sent) = runReal(guard(), getJson, screen.replace("(57s)", "(55s)"))
        assertEquals(0, exit)
        assertEquals("send-keys p1 down enter\n", sent)
        val (exit2, typed) = runReal(guard(), getJson, screen, CardInput.Text("1", enter = true))
        assertEquals(0, exit2)
        assertEquals("send-text p1 1\nsend-keys p1 enter\n", typed)
    }

    @Test fun theRealCheckRefusesAChangedOccupant() {
        val (exit, sent) = runReal(guard(), getJson.replace("sess-A", "sess-B"), screen)
        assertEquals(CARD_CHANGED_EXIT, exit)
        assertEquals("", sent)
    }

    @Test fun theRealCheckRefusesAChangedScreen() {
        val (exit, sent) = runReal(guard(), getJson, screen.replace("Allow once", "Allow always"))
        assertEquals(CARD_CHANGED_EXIT, exit)
        assertEquals("", sent)
        val (scrolled, none) = runReal(guard(), getJson.replace("\"offset_from_bottom\":0", "\"offset_from_bottom\":3"), screen)
        assertEquals("a scrolled-back pane is not the card's", CARD_CHANGED_EXIT, scrolled)
        assertEquals("", none)
    }

    @Test fun theRealCheckThatCantReadSendsNothing() {
        val (exit, sent) = runReal(guard(), "not json", screen)
        assertEquals(CARD_CHECK_FAILED_EXIT, exit)
        assertEquals("", sent)
    }
}
