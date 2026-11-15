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
}
