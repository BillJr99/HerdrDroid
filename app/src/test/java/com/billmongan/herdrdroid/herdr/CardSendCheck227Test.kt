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
    private class Seam(var exit: Int = 0, var out: String = "") : Herdr.Commands {
        override val connection: Any? = Any()
        val writes = mutableListOf<String>()
        override suspend fun bulk(command: String) = ExecResult(0, "", "")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult {
            writes += command
            return ExecResult(exit, out, if (exit == 0) "" else "fixture exit $exit")
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
        val seam = Seam(CARD_CHANGED_EXIT, CARD_REFUSED_MARK + "\n")
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
        // 2.2.8 (Codex's re-review 6): a failure after the check passed (its line went out) may have reached
        // the pane, whatever its exit, the check's own refusal code included; one with no line is the check's.
        for (exit in listOf(1, CARD_CHANGED_EXIT, CARD_CHECK_FAILED_EXIT)) {
            val passed = Seam(exit, CARD_PASSED_MARK + "\n")
            assertThrows("exit $exit after the check passed", DeliveryUncertain::class.java) {
                runBlocking { client(passed).sendCardInput("p1", CardInput.Keys(listOf("enter")), guard(), passed.connection!!) }
            }
        }
        val seam = Seam(1)
        assertThrows(CardCheckFailed::class.java) {
            runBlocking { client(seam).sendCardInput("p1", CardInput.Keys(listOf("enter")), guard(), seam.connection!!) }
        }
        // Exit 3 without the check's refusal line is not a refusal either.
        val bare = Seam(CARD_CHANGED_EXIT)
        assertThrows(CardCheckFailed::class.java) {
            runBlocking { client(bare).sendCardInput("p1", CardInput.Keys(listOf("enter")), guard(), bare.connection!!) }
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

    // ---- The checks themselves, in a real shell with a stand-in herdr -------------------------
    // The Python check runs where python3 is (hd-build-py.sh); the shell check and the failover
    // to it run everywhere, the failover for real where python3 is missing (the normal image).

    private enum class Mode { PYTHON, SHELL, AUTO }

    private fun tool(name: String) = listOf("/usr/bin/$name", "/bin/$name").map { File(it) }.firstOrNull { it.canExecute() }
    private val hasPython = tool("python3") != null

    private class Run(val exit: Int, val sent: String, val out: String)

    /**
     * Runs the real command against a stand-in herdr that serves [json] and [visible] and logs
     * what it is sent. [path]: the PATH the command gets (null keeps the test's own).
     */
    private fun runReal(g: CardGuard, json: String, visible: String, input: CardInput = CardInput.Keys(listOf("down", "enter")),
        mode: Mode = Mode.SHELL, path: ((File) -> String)? = null): Run {
        val sh = tool("sh")
        assumeTrue("needs sh", sh != null)
        if (mode == Mode.PYTHON) assumeTrue("needs python3", hasPython)
        val dir = java.nio.file.Files.createTempDirectory("hd227-card").toFile()
        try {
            File(dir, "get.json").writeBytes(json.toByteArray())
            File(dir, "screen.txt").writeBytes(visible.toByteArray())
            val herdr = File(dir, "herdr")
            herdr.writeText("#!/bin/sh\ncase \"\$2\" in\n" +
                "  get) /bin/cat '${dir.path}/get.json' ;;\n  read) /bin/cat '${dir.path}/screen.txt' ;;\n" +
                "  send-keys|send-text) shift; echo \"\$*\" >> '${dir.path}/sent.log' ;;\n  *) exit 9 ;;\nesac\n")
            herdr.setExecutable(true)
            val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), herdr.path, Backend.TUI, null)
            val command = h.cardSendCommand("p1", input, g, shellOnly = mode == Mode.SHELL).let {
                // PYTHON: the Python check alone, with no failover, to prove its own digests.
                if (mode == Mode.PYTHON) it.replace(Regex("""; st=.*fi \)""", RegexOption.DOT_MATCHES_ALL), " )") else it
            }
            val b = ProcessBuilder(sh!!.path, "-c", command)
            path?.let { b.environment()["PATH"] = it(dir) }
            val p = b.start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.errorStream.readBytes()
            val exit = p.waitFor()
            return Run(exit, File(dir, "sent.log").takeIf { it.exists() }?.readText().orEmpty(), out)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun bothChecks(block: (Mode) -> Unit) {
        block(Mode.SHELL)
        if (hasPython) block(Mode.PYTHON)
    }

    /** Real herdr shapes and harder ones: both checks must reach the very digests the app computed. */
    private val fixtures = listOf(
        getJson to screen,
        // herdr 0.9.5's own reply, keys in its order, with fields the check ignores.
        """{"id":"cli:pane:get","result":{"pane":{"agent":"claude","agent_session":{"agent":"claude","kind":"id","source":"herdr:claude",""" +
            """"value":"0f1e2d3c-fixture"},"agent_status":"blocked","cwd":"/home/u","focused":true,"foreground_cwd":"/home/u/agents/claude",""" +
            """"pane_id":"p1","revision":2,"scroll":{"max_offset_from_bottom":0,"offset_from_bottom":0,"viewport_rows":41},"tab_id":"w2:t2",""" +
            """"terminal_id":"term_660e5c0f","terminal_title":"◐ Fixture","terminal_title_stripped":"Fixture","workspace_id":"w2"},"type":"pane_info"}}""" to
            "\u001b[2m╭─ Bash ─╮\u001b[0m\n│ rm -rf build │\n❯ 1. Yes\n  2. No\nEsc to cancel (9s) · (1234s) · (12345s) · (s)",
        // Reordered and pretty-printed; escaped quotes, backslashes, slashes and tabs; \u escapes,
        // raw UTF-8 and a surrogate pair; a whole-number terminal id; no agent session; -0.
        "{ \"result\" : { \"pane\" : {\n  \"workspace_id\":\"w1\", \"cwd\":\"/home/u/a \\\"quoted\\\" dir\\\\x \\u00e9t\\u00e9 é \\ud83d\\ude00 \\/s\\t\",\n" +
            "  \"pane_id\":\"p1\", \"terminal_id\": 42, \"agent\":\"codex\", \"scroll\":{\"offset_from_bottom\":-0},\n" +
            "  \"tab_id\":\"t\\u4e2d\", \"agent_session\": null, \"focused\": true } } }\n" to "",
        // A fractional id (no value), a session of the wrong kind, and a screen of blank lines.
        """{"result":{"pane":{"pane_id":"p1","agent":"hermes","agent_session":{"kind":"name","agent":"hermes","value":"w"},""" +
            """"scroll":{"offset_from_bottom":0.0},"terminal_id":1.5,"tab_id":"t"}}}""" to "\n\n  \n",
    )

    @Test fun bothChecksReachTheAppsDigestsOnEveryFixture() {
        for ((json, visible) in fixtures) {
            val g = guard(json, visible)
            bothChecks { mode ->
                val r = runReal(g, json, visible, mode = mode)
                assertEquals("$mode on ${json.take(60)}", 0, r.exit)
                assertEquals("$mode", "send-keys p1 down enter\n", r.sent)
            }
        }
    }

    @Test fun theRealChecksSendOnAMatchEvenAsTheCountdownRuns() = bothChecks { mode ->
        val r = runReal(guard(), getJson, screen.replace("(57s)", "(55s)"), mode = mode)
        assertEquals("$mode", 0, r.exit)
        assertEquals("send-keys p1 down enter\n", r.sent)
        val typed = runReal(guard(), getJson, screen, CardInput.Text("1", enter = true), mode = mode)
        assertEquals(0, typed.exit)
        assertEquals("send-text p1 1\nsend-keys p1 enter\n", typed.sent)
    }

    @Test fun theRealChecksRefuseAChangedOccupant() = bothChecks { mode ->
        for (json in listOf(getJson.replace("sess-A", "sess-B"), getJson.replace("term-1", "term-2"), getJson.replace("\"p1\"", "\"p2\""))) {
            val r = runReal(guard(), json, screen, mode = mode)
            assertEquals("$mode", CARD_CHANGED_EXIT, r.exit)
            assertEquals("", r.sent)
        }
    }

    @Test fun theRealChecksRefuseAChangedOrScrolledScreen() = bothChecks { mode ->
        val r = runReal(guard(), getJson, screen.replace("Allow once", "Allow always"), mode = mode)
        assertEquals("$mode", CARD_CHANGED_EXIT, r.exit)
        assertEquals("", r.sent)
        val scrolled = runReal(guard(), getJson.replace("\"offset_from_bottom\":0", "\"offset_from_bottom\":3"), screen, mode = mode)
        assertEquals("$mode: a scrolled-back pane is not the card's", CARD_CHANGED_EXIT, scrolled.exit)
        assertEquals("", scrolled.sent)
    }

    @Test fun theRealChecksThatCantReadSendNothing() = bothChecks { mode ->
        for (json in listOf("not json", """{"result":{"pane":"p1"}}""", """{"result":{"pane":{"pane_id":"p1","scroll":"yes"}}}""")) {
            val r = runReal(guard(), json, screen, mode = mode)
            assertEquals("$mode on $json", CARD_CHECK_FAILED_EXIT, r.exit)
            assertEquals("", r.sent)
        }
    }

    /**
     * Codex's review: only the agent's own timer line may tick. The same "(12s)" in the command being
     * approved, or in a line that only looks like the hint, is part of the question in all three digests.
     */
    @Test fun onlyTheTimerLineMayTickInAllThreeDigests() {
        val shown = "\u001b[1m⚠ Dangerous Command\u001b[0m\n  \$ sleep (12s) && deploy\n│ ↑/↓ to select, Enter to confirm (12s) │\n" +
            "❯ 1. Allow once\n  2. Deny\n\u001b[2m  ↑/↓ to select, Enter to confirm (57s)\u001b[0m\n⚠ ❯\n"
        val ticked = shown.replace("confirm (57s)\u001b", "confirm (56s)\u001b")
        val changed = shown.replace("sleep (12s)", "sleep (13s)")
        val lookalike = shown.replace("confirm (12s) │", "confirm (13s) │")
        assertEquals(cardScreenDigest(shown), cardScreenDigest(ticked))
        assertEquals(cardScreenDigest(shown), cardScreenDigest(shown.replace("(57s)", "(5s)")))
        assertNotEquals(cardScreenDigest(shown), cardScreenDigest(changed))
        assertNotEquals(cardScreenDigest(shown), cardScreenDigest(lookalike))
        assertNotEquals("text before the hint makes it not the hint", cardScreenDigest("echo (5s) ↑/↓ to select, Enter to confirm (12s)"),
            cardScreenDigest("echo (6s) ↑/↓ to select, Enter to confirm (12s)"))
        val g = guard(getJson, shown)
        bothChecks { mode ->
            assertEquals("$mode: the timer ticks", 0, runReal(g, getJson, ticked, mode = mode).exit)
            for (other in listOf(changed, lookalike)) {
                val r = runReal(g, getJson, other, mode = mode)
                assertEquals("$mode", CARD_CHANGED_EXIT, r.exit)
                assertEquals("", r.sent)
            }
        }
        // Hermes's confirm hint ticks the same way.
        val confirm = "Run /reset?\n  1. Yes\n  2. No\ntype 1/2, or ↑/↓ to select, Enter to confirm (30s)"
        bothChecks { mode -> assertEquals("$mode", 0, runReal(guard(getJson, confirm), getJson, confirm.replace("(30s)", "(29s)"), mode = mode).exit) }
    }

    private fun screenFile(name: String) = checkNotNull(javaClass.getResourceAsStream("/screens/hermes/derived-2.2.7/$name")) { name }
        .use { it.readBytes().toString(Charsets.UTF_8) }

    /** Grok's re-review: Hermes's real hints (two spaces before the timer, any number of digits) tick in all three. */
    @Test fun hermesRealHintsTickInAllThreeDigests() {
        val cases = listOf(
            screenFile("approval-modern.txt").let { it to it.replace("(58s)", "(57s)") },
            screenFile("approval-modern-hidden.txt").let { it to it.replace("(58s)", "(57s)") },
            screenFile("slash-confirm-modern.txt").let { it to it.replace("(41s)", "(40s)") },
            screenFile("approval-modern.txt").replace("(58s)", "(86224s)").let { it to it.replace("(86224s)", "(86223s)") },
        )
        for ((shown, ticked) in cases) {
            assertNotEquals("the fixture ticks", shown, ticked)
            assertEquals(cardScreenDigest(shown), cardScreenDigest(ticked))
            bothChecks { mode -> assertEquals("$mode", 0, runReal(guard(getJson, shown), getJson, ticked, mode = mode).exit) }
        }
        // The command's own text still counts on a real screen.
        val real = screenFile("approval-modern.txt")
        val command = Regex("""\(\d+s\)""").findAll(real).count()
        assertEquals("only the hint has a countdown in the fixture", 1, command)
        val edited = real.replaceFirst("Dangerous", "Dangerous (12s)")
        bothChecks { mode ->
            val r = runReal(guard(getJson, edited), getJson, edited.replace("Dangerous (12s)", "Dangerous (13s)"), mode = mode)
            assertEquals("$mode", CARD_CHANGED_EXIT, r.exit)
        }
    }

    private fun questionFile(name: String) = checkNotNull(javaClass.getResourceAsStream("/screens/hermes/derived-2.2.8-question/$name")) { name }
        .use { it.readBytes().toString(Charsets.UTF_8) }

    /** Hermes's composer rows as it redraws them every second: a new spinner frame, turn time and footer timers. */
    private fun hermesTick(s: String) = s.replace("(88s)", "(87s)").replace("(287s)", "(286s)").replace("(80s)", "(79s)")
        .replace("── ⠹ Working… · 1m 2s ", "── ⠼ Working… · 1m 3s ").replace("◷ 3.1s", "◷ 3.2s").replace("⏲ 1m ·", "⏲ 2m ·")

    /**
     * 2.2.8 (Bill: "Hermes will ask a question occasionally that does not render in chat"): while a clarify
     * or approval waits, Hermes redraws its hint countdown, its composer's top rule (spinner and turn time)
     * and its footer every second. Only those rows may change in all three digests, and only when the
     * composer is proven under a live prompt; the panel, the hint's words and the input row still count.
     */
    @Test fun hermesComposerRowsTickInAllThreeDigestsAndNothingElse() {
        val names = listOf("clarify-single-first.txt", "clarify-batch-second.txt", "clarify-single-other-typing.txt",
            "clarify-freetext.txt", "approval-selected-deny.txt", "clarify-single-no-deadline.txt")
        for (name in names) {
            val shown = questionFile(name)
            val ticked = hermesTick(shown)
            if (name != "clarify-single-no-deadline.txt") assertNotEquals("$name ticks", shown, ticked)
            assertEquals(name, cardScreenDigest(shown), cardScreenDigest(ticked))
            bothChecks { mode -> assertEquals("$mode $name", 0, runReal(guard(getJson, shown), getJson, ticked, mode = mode).exit) }
        }
        val shown = questionFile("clarify-single-first.txt")
        val changed = listOf(
            shown.replace("2. release/demo", "2. release/demo2"),        // an option
            shown.replace("Enter to lock, Tab", "Enter to lock,  Tab"),   // the hint's own words
            shown.replace("\n? ❯\n", "\n? ❯ 2\n"),                      // the input row
            shown.replace("Which branch", "Which branches"),             // the question
        )
        for (other in changed) {
            assertNotEquals(shown, other)
            assertNotEquals(cardScreenDigest(shown), cardScreenDigest(other))
            bothChecks { mode -> assertEquals("$mode", CARD_CHANGED_EXIT, runReal(guard(getJson, shown), getJson, hermesTick(other), mode = mode).exit) }
        }
        // Not under a live prompt (the agent's answer is on screen, the composer is busy): the rule counts.
        val busy = questionFile("resolved-clarify.txt")
        assertNotEquals(cardScreenDigest(busy), cardScreenDigest(hermesTick(busy)))
        // A rule that only looks like the composer's, inside the prompt and not under the hint, still counts.
        val inner = shown.replace("│ 1 question", "│ ── ⠹ Working… · 1m 2s ─")
        assertNotEquals(cardScreenDigest(inner), cardScreenDigest(inner.replace("│ ── ⠹ Working… · 1m 2s ─", "│ ── ⠼ Working… · 1m 3s ─")))
    }

    /** Codex's re-review: a NUL byte (which a shell variable drops) never matches, in all three. */
    @Test fun aNulByteNeverMatchesInAllThree() {
        val plain = "a\nb\n"
        assertNotEquals(cardScreenDigest(plain), cardScreenDigest("a\u0000\nb\n"))
        assertNotEquals(cardScreenDigest("a\u0000\nb\n"), sha256Hex("a\u0000\nb\n".toByteArray()))
        bothChecks { mode ->
            val added = runReal(guard(getJson, plain), getJson, "a\u0000\nb\n", mode = mode)
            assertEquals("$mode: an added NUL", CARD_CHANGED_EXIT, added.exit)
            assertEquals("", added.sent)
            val both = runReal(guard(getJson, "a\u0000\nb\n"), getJson, "a\u0000\nb\n", mode = mode)
            assertEquals("$mode: a NUL on both reads", CARD_CHANGED_EXIT, both.exit)
        }
    }

    /**
     * Codex's re-review: the shell check's files are its own, made new for each check: a link planted
     * at a name the check once used is never followed, two checks on the same pane at once don't
     * share files, and each removes exactly its own when it ends.
     */
    @Test fun theShellChecksFilesArePrivatePerCheck() {
        val sh = tool("sh")
        assumeTrue("needs sh", sh != null)
        val dir = java.nio.file.Files.createTempDirectory("hd227-files").toFile()
        try {
            val home = File(dir, "home").apply { mkdirs() }
            val check = File(home, ".herdrdroid/check").apply { mkdirs() }
            java.nio.file.Files.setPosixFilePermissions(check.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            val target = File(dir, "keep.txt").apply { writeText("keep me") }
            for (n in listOf("p1.j", "p1.v")) java.nio.file.Files.createSymbolicLink(File(check, n).toPath(), target.toPath())
            File(dir, "get.json").writeText(getJson)
            File(dir, "screen.txt").writeText(screen)
            val herdr = File(dir, "herdr")
            // A slow read, so the two checks below overlap.
            herdr.writeText("#!/bin/sh\ncase \"\$2\" in\n  get) /bin/cat '${dir.path}/get.json' ;;\n" +
                "  read) sleep 1; /bin/cat '${dir.path}/screen.txt' ;;\n  *) echo \"\$*\" >> '${dir.path}/sent.log' ;;\nesac\n")
            herdr.setExecutable(true)
            val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), herdr.path, Backend.TUI, null)
            val command = h.cardSendCommand("p1", CardInput.Keys(listOf("enter")), guard(), shellOnly = true)
            val runs = List(2) { ProcessBuilder(sh!!.path, "-c", command).apply { environment()["HOME"] = home.path }.start() }
            val exits = runs.map { p -> p.inputStream.readBytes(); p.errorStream.readBytes(); p.waitFor() }
            assertEquals("both checks pass", listOf(0, 0), exits)
            assertEquals("pane send-keys p1 enter\npane send-keys p1 enter\n", File(dir, "sent.log").readText())
            assertEquals("the planted link's target is untouched", "keep me", target.readText())
            assertEquals("only the planted links are left", setOf("p1.j", "p1.v"), check.list()!!.toSet())
            assertTrue(java.nio.file.Files.isSymbolicLink(File(check, "p1.j").toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Codex's review: an existing folder is checked as it is, never changed: one open to others refuses the check. */
    @Test fun theShellCheckRefusesAnOpenFolderAndLeavesItAlone() {
        val sh = tool("sh")
        assumeTrue("needs sh", sh != null)
        val dir = java.nio.file.Files.createTempDirectory("hd227-mode").toFile()
        try {
            val home = File(dir, "home").apply { mkdirs() }
            val check = File(home, ".herdrdroid/check").apply { mkdirs() }
            java.nio.file.Files.setPosixFilePermissions(check.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
            val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), "/bin/false", Backend.TUI, null)
            val p = ProcessBuilder(sh!!.path, "-c", h.cardSendCommand("p1", CardInput.Keys(listOf("enter")), guard(), shellOnly = true))
                .apply { environment()["HOME"] = home.path }.start()
            p.inputStream.readBytes(); p.errorStream.readBytes()
            assertEquals(CARD_CHECK_FAILED_EXIT, p.waitFor())
            assertEquals("left as it was", "rwxr-xr-x",
                java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(check.toPath())))
            // A new folder is made private from the start.
            val fresh = File(dir, "fresh").apply { mkdirs() }
            val q = ProcessBuilder(sh.path, "-c", h.cardSendCommand("p1", CardInput.Keys(listOf("enter")), guard(), shellOnly = true))
                .apply { environment()["HOME"] = fresh.path }.start()
            q.inputStream.readBytes(); q.errorStream.readBytes(); q.waitFor()
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                java.nio.file.Files.getPosixFilePermissions(File(fresh, ".herdrdroid/check").toPath())))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The folder must be a real one of the user's own: a link there refuses the check. */
    @Test fun theShellCheckRefusesALinkedFolder() {
        val sh = tool("sh")
        assumeTrue("needs sh", sh != null)
        val dir = java.nio.file.Files.createTempDirectory("hd227-link").toFile()
        try {
            val home = File(dir, "home").apply { mkdirs() }
            val elsewhere = File(dir, "elsewhere").apply { mkdirs() }
            File(home, ".herdrdroid").mkdirs()
            java.nio.file.Files.createSymbolicLink(File(home, ".herdrdroid/check").toPath(), elsewhere.toPath())
            val h = Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), "/bin/false", Backend.TUI, null)
            val p = ProcessBuilder(sh!!.path, "-c", h.cardSendCommand("p1", CardInput.Keys(listOf("enter")), guard(), shellOnly = true))
                .apply { environment()["HOME"] = home.path }.start()
            p.inputStream.readBytes(); p.errorStream.readBytes()
            assertEquals(CARD_CHECK_FAILED_EXIT, p.waitFor())
            assertEquals("nothing written through the link", 0, elsewhere.list()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Where python3 is missing, the one command falls over to the shell check: still checked, and said so. */
    @Test fun withoutPython3TheCommandFallsOverToTheShellCheck() {
        assumeTrue("runs where /usr/bin/python3 is missing", !File("/usr/bin/python3").canExecute())
        val noPython: (File) -> String = { "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".split(':')
            .filter { !File(it, "python3").exists() }.joinToString(":") }
        val r = runReal(guard(), getJson, screen, mode = Mode.AUTO, path = noPython)
        assertEquals(0, r.exit)
        assertTrue(r.out, r.out.lines().contains(SHELL_CHECK_MARK))
        assertEquals("send-keys p1 down enter\n", r.sent)
        val changed = runReal(guard(), getJson, screen.replace("Allow once", "Allow always"), mode = Mode.AUTO, path = noPython)
        assertEquals(CARD_CHANGED_EXIT, changed.exit)
        assertEquals("", changed.sent)
    }

    /** No sha256sum, shasum or openssl: the shell check can't check, so nothing is sent. */
    @Test fun theShellCheckWithoutAShaToolSendsNothing() {
        val bin: (File) -> String = { dir ->
            val b = File(dir, "bin").apply { mkdirs() }
            for (t in listOf("sh", "awk", "cut", "sed", "cat", "mkdir", "chmod", "tr", "cmp")) {
                val real = tool(t) ?: continue
                java.nio.file.Files.createSymbolicLink(File(b, t).toPath(), real.toPath())
            }
            b.path
        }
        val r = runReal(guard(), getJson, screen, mode = Mode.SHELL, path = bin)
        assertEquals(CARD_CHECK_FAILED_EXIT, r.exit)
        assertEquals("", r.sent)
    }
}
