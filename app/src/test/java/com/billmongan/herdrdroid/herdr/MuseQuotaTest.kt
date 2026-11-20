package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1.0: Muse Code's "You're using your quota fast" warning as a question card. */
class MuseQuotaTest {
    private val e = "\u001B"
    private val dash = "—"
    private val times = "×"
    private fun screen(name: String) = checkNotNull(javaClass.classLoader?.getResource("screens/muse/$name")).readText()
    private val captured get() = screen("captured-2026-11-20/quota-fast.txt")
    private val full get() = screen("derived-2.1.0/quota-fast-full.txt")

    /** [text] read with styles: option row [hi] (1-based) bold in a colour, the others plain. */
    private fun styled(text: String, hi: Int?): String {
        var n = 0
        return text.lines().joinToString("\n") { row ->
            if (MUSE_QUOTA_TITLES.any { row.trimStart().startsWith(it) } && row.startsWith("  ") && !row.startsWith("    ")) {
                n++
                if (n == hi) "  $e[1;38;2;120;170;255m${row.trimStart()}$e[0m" else "  $e[38;2;230;230;230m${row.trimStart()}$e[0m"
            } else row
        }
    }

    @Test fun `the captured warning is a card with every option and the data-use note`() {
        val p = checkNotNull(detectPrompt(captured, false, "muse"))
        assertTrue(p.museQuota)
        assertEquals(listOf("Switch to Spark $dash about 5$times more turns", "Both $dash over 10$times more turns", "Keep going"), p.choices.map { it.label })
        val text = p.parts!!.details
        assertTrue(text.toString(), "Both: Spark becomes your default; session content may be used for product improvement. Terms of Service (https://dev.meta.ai/legal/terms-of-service)" in text)
        assertTrue("Switch to Spark: Spark becomes your default model. Switch back anytime with /model." in text)
        assertTrue(text.any { it.startsWith("Keep going closes this") })
        // A plain read can't show the highlight: no keys for any option.
        assertNull(p.selected)
        for (c in p.choices) assertNull(menuKeys(p, c))
    }

    @Test fun `the full warning from Muse's strings has its title, pace and four options`() {
        val p = checkNotNull(detectPrompt(full, true, "muse"))
        assertEquals("You're using your quota fast", p.parts!!.heading)
        assertTrue(p.parts!!.details.first().startsWith("At this pace your 5-hour quota runs out in about 40 minutes"))
        assertEquals(listOf("Switch to Spark", "Turn on contributor mode", "Both", "Keep going"), p.choices.map { it.label.substringBefore(" $dash") })
        assertTrue(p.parts!!.details.any { it == "Turn on contributor mode: Session content may be used for product improvement." })
    }

    @Test fun `a styled read finds the highlight and answers with arrows and Enter`() {
        val p = checkNotNull(detectPrompt(styled(full, 1), false, "muse"))
        assertEquals("1", p.selected)
        val both = p.choices.first { it.label.startsWith("Both") }
        assertEquals(listOf("down", "down", "enter"), menuKeys(p, both))
        assertEquals(listOf("down", "down", "down", "enter"), menuKeys(p, p.choices.last()))
        assertEquals(listOf("enter"), menuKeys(p, p.choices.first()))
        val fromKeep = checkNotNull(detectPrompt(styled(captured, 3), false, "muse"))
        assertEquals("3", fromKeep.selected)
        assertEquals(listOf("up", "up", "enter"), menuKeys(fromKeep, fromKeep.choices.first()))
        // The card read plainly still fits the live styled one, whatever is highlighted.
        assertTrue(cardAnswerStillFits(checkNotNull(detectPrompt(full, false, "muse")), p))
        // No option stands out (all alike): no highlight, no keys.
        assertNull(checkNotNull(detectPrompt(styled(full, null), false, "muse")).selected)
        // Never Esc, never a digit.
        for (c in p.choices) assertFalse(menuKeys(p, c)!!.any { it == "esc" || it.all(Char::isDigit) })
    }

    @Test fun `only Muse's own list counts`() {
        // Another agent, or the same words in a reply.
        assertFalse(detectPrompt(full, false, "claude")?.museQuota == true)
        assertNull(detectMuseQuotaDialog("◆ Keep going until the tests pass.\n  Keep going\n  Both\n" + "─".repeat(40) + "\n  footer"))
        // No offer of more turns, the list not at the bottom, or rows out of line.
        assertNull(detectMuseQuotaDialog("  Both\n  Keep going\n" + "─".repeat(40)))
        assertNull(detectMuseQuotaDialog(captured.replace("  Keep going", "  Keep going\n  more output\n  and more\n  and more\n  and more")))
        assertNull(detectMuseQuotaDialog(captured.replace("  Both $dash", "   Both $dash")))
        // A notification or voice answer never answers it (the chat card's live read only).
        assertNull(com.billmongan.herdrdroid.voice.voiceCardKeys(checkNotNull(detectPrompt(styled(full, 1), false, "muse")),
            checkNotNull(detectPrompt(styled(full, 1), false, "muse")).choices.last()))
    }
}
