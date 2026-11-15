package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.4.2: Bill's Codex edit card that showed no question or options, and no card ever empty. */
class Release142Test {
    private fun screen(rel: String) = javaClass.getResource("/screens/$rel")!!.readText()

    @Test fun codexsEditCardShowsTheQuestionTheFileAndItsKeys() {
        val p = detectPrompt(screen("codex/captured-2026-11-14/edit-approval.txt"), blocked = true, agent = "codex")
        assertNotNull(p)
        p!!
        assertEquals(3, p.choices.size)
        val blocks = p.shownParts().blocks
        assertEquals(QBlock(QKind.HEADING, "Would you like to make the following edits?"), blocks.first())
        // The destination in a monospace block of its own, right after its label.
        val at = blocks.indexOfFirst { it.kind == QKind.COMMAND }
        assertEquals("/home/user/agents/codex/cv-tours/c20/out/course-site-lambdacalculus2-8.json", blocks[at].text)
        assertEquals("Destination:", blocks[at - 1].text)
        assertTrue(blocks.any { it.kind == QKind.TEXT && it.text == "Description: Apply proposed file edits" })
        // Each option sends its own key: y, a, Esc.
        assertEquals(listOf(listOf("y"), listOf("a"), listOf("esc")), p.choices.map { menuKeys(p, it) })
    }

    @Test fun aWrappedDestinationStaysOnePath() {
        val text = """
  Would you like to make the following edits?
  Description: Apply proposed file edits
  Destination: /home/user/agents/codex/cv-tours/c20/out/course-site-
  lambdacalculus2-8.json
› 1. Yes, proceed (y)
  2. Yes, and don't ask again for these files (a)
  3. No, and tell Codex what to do differently (esc)
  Press enter to confirm or esc to cancel
""".trimIndent()
        val p = detectPrompt(text, blocked = true, agent = "codex")!!
        assertEquals("/home/user/agents/codex/cv-tours/c20/out/course-site-lambdacalculus2-8.json",
            p.shownParts().blocks.first { it.kind == QKind.COMMAND }.text)
    }

    @Test fun noCardIsEverEmpty() {
        // Every screen in the library, and the screens that made empty cards before: blank (a read
        // that failed), only spaces, a question footer with nothing above it, noise.
        val root = File(javaClass.classLoader!!.getResource("screens")!!.toURI())
        val screens = root.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }.map { it.readText() }.toList() +
            listOf("", "   \n\n  ", "Press enter to confirm or esc to cancel", "Enter to select · ↑/↓ to navigate · Esc to cancel", "\u001B[2J", "────────")
        for (s in screens) for (blocked in listOf(true, false)) for (agent in listOf(null, "codex", "claude", "kiro", "grok", "copilot", "vibe")) {
            val p = detectPrompt(s, blocked, agent)?.forCard() ?: continue
            assertTrue("a card with no question text for ${s.take(40)}", p.question.isNotBlank())
            assertTrue("a card with no options and nothing to show for ${s.take(40)}", p.choices.isNotEmpty() || !p.region.isNullOrEmpty())
        }
        // A prompt made anywhere else with nothing in it still shows the note.
        assertEquals(listOf(UNREAD_SCREEN), Prompt("Waiting", emptyList()).forCard().region)
    }

    @Test fun aBlankScreenWhileTheAgentWaitsSaysTheScreenCouldNotBeRead() {
        val p = detectPrompt("", blocked = true, agent = "codex")!!
        assertTrue(p.choices.isEmpty())
        assertEquals(listOf(UNREAD_SCREEN), p.region)
        assertFalse(detectPrompt("", blocked = false, agent = "codex") != null)
    }

    @Test fun codexsOtherApprovalCardsParseToo() {
        for (name in listOf("codex/captured-2026-11-13/run-command.txt", "codex/synthetic-1.4.2/network-approval.txt", "codex/synthetic-1.4.2/mcp-approval.txt")) {
            val p = detectPrompt(screen(name), blocked = true, agent = "codex")!!
            assertEquals(name, 3, p.choices.size)
            assertTrue(name, p.shownParts().blocks.first().kind == QKind.HEADING)
            assertEquals(name, listOf("esc"), menuKeys(p, p.choices.last()))
        }
    }
    @Test fun anAgentErrorIsABannerNotAQuestion() {
        val screen = screen("opencode/captured-2026-11-14/compaction-failed.txt")
        // herdr said blocked: still no card, and the error is named.
        assertEquals(null, detectPrompt(screen, blocked = true, agent = "opencode"))
        val why = detectAgentError(screen)!!
        assertTrue(why, why.contains("compaction failed") && why.contains("model rate-limited"))
        // A conversation that only talks about rate limits is not an error.
        assertEquals(null, detectAgentError("> how do rate limits work?\nThey cap requests per minute.\n> ok"))
        // A real question with an error further up still gets its card.
        val both = "✗ Compaction failed earlier\n\n" + screen("codex/captured-2026-11-14/edit-approval.txt")
        assertEquals(3, detectPrompt(both, blocked = true, agent = "codex")!!.choices.size)
    }
    @Test fun letterKeysOnlyForCodexMenus() {
        // The same "(y)" label on another agent's menu (no Codex footer, not Codex) keeps arrows and Enter.
        val other = " Go ahead?\n ❯ 1. Yes, go (y)\n   2. No, stop (n)\n Enter to select · Esc to cancel"
        val p = detectPrompt(other, blocked = true, agent = "claude")!!
        assertFalse(p.shortcuts)
        assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[1]))
        // A choice not on the menu gets no letter.
        val codex = detectPrompt(screen("codex/captured-2026-11-14/edit-approval.txt"), blocked = true)!!
        assertTrue(codex.shortcuts)
        assertEquals(null, menuKeys(codex, Choice("Something else (z)", "9", false)))
    }

    @Test fun aRegionOfBlankLinesStillShowsTheNote() {
        assertEquals(listOf(UNREAD_SCREEN), Prompt("Waiting", emptyList(), region = listOf("", "  ")).forCard().region)
    }

    @Test fun aStatusWordAfterAPathIsNotPartOfIt() {
        val text = """
  Would you like to make the following edits?
  Destination: /tmp/foo
  Ready
› 1. Yes, proceed (y)
  2. No, and tell Codex what to do differently (esc)
  Press enter to confirm or esc to cancel
""".trimIndent()
        assertEquals("/tmp/foo", detectPrompt(text, blocked = true, agent = "codex")!!.shownParts().blocks.first { it.kind == QKind.COMMAND }.text)
    }
    @Test fun ompsRetryErrorIsABannerAndItsCodeBoxIsNoCard() {
        for (name in listOf("omp/captured-2026-11-14/retry-exhausted.txt", "pi/synthetic-1.4.2/retry-exhausted.txt")) {
            val text = screen(name)
            for (agent in listOf("omp", "pi", null)) assertEquals(name, null, detectPrompt(text, blocked = true, agent = agent))
            val why = detectAgentError(text)!!
            assertTrue(why, why.contains("retries exhausted") && why.contains("model rate-limited"))
        }
        // The boxed code alone, with herdr saying blocked and no error under it: still no card.
        val box = screen("omp/captured-2026-11-14/retry-exhausted.txt").lines().take(9).joinToString("\n") + "\n π > ⬢ llmproxy/flagship__free > 📁 ~/agents/omp"
        assertEquals(null, detectPrompt(box, blocked = true, agent = "omp"))
        // Even when a line in the box ends in a question mark.
        val asking = box.replace("what comes back?\" }", "what comes back?")
        assertEquals(null, detectPrompt(asking, blocked = true, agent = "omp"))
    }
    @Test fun aConversationAboutErrorsShowsNoBanner() {
        // Every one of these words, said in a conversation (yours or the agent's), not as an error.
        val talk = listOf(
            "> why did we hit a rate limit exceeded error yesterday? was it a 429?",
            "❯ check the quota exceeded and usage limit messages, and the 402 Payment Required case",
            "› what does 'context window exceeded' mean, and when does compaction failed show up?",
            "The 429 responses mean the provider rate-limited us; a 503 Service Unavailable is different.",
            "When the context window exceeds its limit, the agent compacts the conversation first.",
            "A token expired message means you need to log in again; 401 and 403 are auth failures.",
            "Overloaded providers return 529; a connection reset or network error is retried.",
            "I fixed the out of credits banner and the not logged in check in the parser.",
        )
        for (n in talk.indices) {
            val screen = talk.take(n + 1).joinToString("\n")
            assertEquals(screen, null, detectAgentError(screen))
        }
    }
    @Test fun secondReviewRules() {
        // A boxed code or table line is the agent's content, not its error.
        assertEquals(null, detectAgentError("│ Error: 429 Too Many Requests │\n│ status 500                 │\n> next"))
        // A line that opens with the error itself counts, with no mark.
        assertEquals("model rate-limited", detectAgentError("output\nRate limit exceeded\n> "))
        assertTrue(detectAgentError("429 Too Many Requests")!!.contains("model rate-limited"))
        // A question under an error still gets its card.
        assertNotNull(detectPrompt("✗ Compaction failed\nRetry the compaction?", blocked = true))
        // Code that ends in "?" is not a question.
        assertEquals(null, detectPrompt("fun f(name: String?)\nval name: String?\n// why?", blocked = true))
        // A traceback the agent shows from your program is not its crash; with the shell back, it is.
        val tb = "Traceback (most recent call last):\n  File \"t.py\", line 1\nZeroDivisionError: division by zero"
        assertEquals(null, detectAgentError(tb + "\n› "))
        assertTrue(detectAgentError(tb + "\nbill@devbox2:~/x$")!!.contains("the agent exited"))
        // A status word after a path is not part of it.
        val text = "  Would you like to make the following edits?\n  Destination: /tmp/foo\n  Done.\n› 1. Yes, proceed (y)\n  2. No, and tell Codex what to do differently (esc)\n  Press enter to confirm or esc to cancel"
        assertEquals("/tmp/foo", detectPrompt(text, blocked = true, agent = "codex")!!.shownParts().blocks.first { it.kind == QKind.COMMAND }.text)
    }
    @Test fun thirdReviewRules() {
        fun dest(a: String, b: String) = detectPrompt("  Would you like to make the following edits?\n  Destination: $a\n  $b\n› 1. Yes, proceed (y)\n  2. No, and tell Codex what to do differently (esc)\n  Press enter to confirm or esc to cancel",
            blocked = true, agent = "codex")!!.shownParts().blocks.first { it.kind == QKind.COMMAND }.text
        assertEquals("/out/lambdacalculus.json", dest("/out/lambdacal", "culus.json"))
        assertEquals("/tmp/foo", dest("/tmp/foo", "/etc/hosts"))
        // Lower-case and capitalised lead phrases both count; a 404 doesn't carry a sign-in error over.
        assertEquals("model rate-limited", detectAgentError("output\nrate limit exceeded"))
        assertTrue(detectAgentError("429 TOO MANY REQUESTS")!!.contains("model rate-limited"))
        assertEquals(null, detectAgentError("404 Not Found\nlog in again later"))
        // A boxed traceback is content, even with a shell prompt under it.
        assertEquals(null, detectAgentError("│ Traceback (most recent call last): │\n│ unhandled exception │\nbill@host:~$"))
    }
    @Test fun fourthReviewRules() {
        fun dest(a: String, b: String) = detectPrompt("  Would you like to make the following edits?\n  Destination: $a\n  $b\n› 1. Yes, proceed (y)\n  2. No, and tell Codex what to do differently (esc)\n  Press enter to confirm or esc to cancel",
            blocked = true, agent = "codex")!!.shownParts().blocks.first { it.kind == QKind.COMMAND }.text
        assertEquals("/tmp/foo", dest("/tmp/foo", "src/Main.kt"))
        assertEquals("/tmp/foo/", dest("/tmp/foo/", "/etc/hosts"))
        assertEquals("/home/user/agents/codex/out/file.json", dest("/home/user/agents/codex/", "out/file.json"))
        assertEquals("/out/course-site-lambdacalculus2-8.json", dest("/out/course-site-", "lambdacalculus2-8.json"))
        // The shell's prompt must be the screen's last line; a boxed line under it means it isn't back.
        assertEquals(null, detectAgentError("Traceback (most recent call last):\nuser@host:~$\n│ a boxed line │"))
    }
}
