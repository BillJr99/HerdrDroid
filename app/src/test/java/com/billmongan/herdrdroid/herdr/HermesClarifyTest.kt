package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

// Synthetic screen in the format rendered by Hermes UI-TUI ClarifyPrompt and StatusRule.
internal val hermesClarify = """
     ask 1 question
     ▸ Which database should the fixture use?
       ▸ 1. SQLite
         2. Postgres
         3. Other (type your answer)
     0/1 answered · ↑/↓ select · Enter confirm and continue · Tab/Shift+Tab switch
     question · Esc/Ctrl+C cancel

     ─ working │ fixture │ 1K/100K ─────
""".trimIndent()

class HermesClarifyTest {
    @Test fun nativeSingleChoiceUsesOnlyTheExactDigitEvenWhenFocusMoves() {
        val shown = checkNotNull(detectPrompt(hermesClarify, false, "hermes"))
        assertEquals("Which database should the fixture use?", shown.question)
        assertEquals(listOf("SQLite", "Postgres"), shown.choices.map { it.label })
        val redrawn = hermesClarify.replace("▸ 1. SQLite", "  1. SQLite").replace("  2. Postgres", "▸ 2. Postgres")
        val fresh = checkNotNull(detectPrompt(redrawn, false, "hermes"))
        assertTrue(cardAnswerStillFits(shown, fresh))
        assertEquals(listOf("2"), menuKeys(fresh, shown.choices[1]))
        assertNull(com.billmongan.herdrdroid.voice.voiceCardKeys(fresh, shown.choices[1]))
    }

    @Test fun unsupportedOrAmbiguousLiveShapesAreTerminalOnly() {
        val variants = listOf(
            hermesClarify.replace("↑/↓ select", "Space toggle · ↑/↓ select")
                .replace("1. SQLite", "[ ] 1. SQLite").replace("2. Postgres", "[x] 2. Postgres"),
            hermesClarify.replace("ask 1 question", "ask 2 questions").replace("0/1", "0/2")
                .replace("confirm and continue", "lock answer").replace("0/2 answered", "· Another question?\n0/2 answered"),
            hermesClarify.substringBefore("  ▸ 1.") + "> draft\n0/1 answered · Enter confirm and continue · Esc back",
            hermesClarify.replace("▸ 1.", "  1."),
            hermesClarify.substringAfter("ask 1 question"),
        )
        variants.forEach { frame ->
            val card = checkNotNull(detectPrompt(frame, false, "hermes")) { frame }
            assertTrue(frame, card.terminalReview)
            assertTrue(card.choices.isEmpty())
        }
    }

    @Test fun expiredClarifyIsNotALiveQuestionAndDoesNotMaskANewerApproval() {
        val expired = """
            (clarify timed out after 90s — continuing without an answer)
            ❓ Clarify("1 question")
            ask (1 questions)
              · Which database should the fixture use? (no answer)
              (timed out)
            ──────────
            ❯
            ──────────
            ☤ fixture · 1K/100K
        """.trimIndent()
        assertNull(detectPrompt(expired, false, "hermes"))
        assertNull(detectPrompt(expired, true, "hermes"))
        assertNull(detectPrompt("$hermesClarify\n$expired", true, "hermes"))
        val approval = checkNotNull(javaClass.getResource(
            "/screens/hermes/derived-1.4.9/1001-dangerous-command-approval.txt")).readText()
        val after = checkNotNull(detectPrompt("$hermesClarify\n$expired\n$approval", false, "hermes"))
        assertTrue(after.choices.isNotEmpty())
        assertTrue(after.question.contains("Dangerous Command"))
    }
}
