package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grok's tool approval card numbers its options with radio buttons ("2 (●) Yes, proceed") rather
 * than "2." or "2)". Before 1.2.3 the card showed no question and no choices, so it could not be
 * answered from the phone. Screens captured from Grok 4.7 on 2026-11-13.
 */
class GrokQuestionTest {
    private val shellCard = """
     ◆ Running list panes, first two lines
   ◆ Check SSH to demo-box in batch mode… 58s                                     2m46s ⇣61.9k [↓][stop]
  ┃
  ┃  ›  Check SSH to demo-box in batch mode
  ┃  │ ssh -o BatchMode=yes -o ConnectTimeout=5 bill@192.0.2.26 true; echo "EXIT:${'$'}?"
  ┃
  ┃  1 (○) Yes, and don't ask again for anything (always-approve mode)
  ┃  2 (●) Yes, proceed
  ┃  3 (○) No, reject (type to add feedback)
  ┃
  1/3:select  │  Tab:next option  │  Ctrl+o:always-approve  │  Ctrl+c:cancel  │  Esc:scrollback
""".trimIndent()

    private val scopedCard = """
  ┃  ›  Make a folder and write the date
  ┃  │ mkdir -p sub && date > sub/now.txt
  ┃  ← → narrow scope
  ┃
  ┃  1 (○) Yes, and don't ask again for anything (always-approve mode)
  ┃  2 (○) Yes, proceed
  ┃  3 (●) No, reject (type to add feedback)
  ┃  4 (○) Never allow: mkdir -p
  ┃
  1/4:select  │  Tab:next option  │  ←/→:scope  │  Ctrl+o:always-approve  │  Ctrl+c:cancel  │  Esc:scrollback
""".trimIndent()

    @Test fun grokCardIsAQuestionEvenIfHerdrSaysIdle() {
        assertTrue(showsQuestionDialog(shellCard))
        assertNotNull(detectPrompt(shellCard, blocked = false))
    }

    @Test fun grokCardHasItsQuestionAndChoices() {
        val p = detectPrompt(shellCard, blocked = true)!!
        assertEquals(listOf("1", "2", "3"), p.choices.map { it.keys })
        assertEquals("Yes, proceed", p.choices[1].label)
        assertEquals("No, reject (type to add feedback)", p.choices[2].label)
        assertTrue(p.question, p.question.startsWith("Check SSH to demo-box in batch mode"))
        assertTrue(p.question, p.question.contains("ssh -o BatchMode=yes"))
        assertEquals("2", p.selected)
    }

    @Test fun grokChoicesAreReachedWithArrowsFromTheFilledRadio() {
        val p = detectPrompt(shellCard, blocked = true)!!
        assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[2]))
        assertEquals(listOf("up", "enter"), menuKeys(p, p.choices[0]))
        assertEquals(listOf("enter"), menuKeys(p, p.choices[1]))
    }

    @Test fun scopeHintIsNotPartOfTheQuestion() {
        val p = detectPrompt(scopedCard, blocked = true)!!
        assertEquals(4, p.choices.size)
        assertEquals("3", p.selected)
        assertTrue(p.question, !p.question.contains("narrow scope"))
        assertEquals("Never allow: mkdir -p", p.choices[3].label)
    }

    @Test fun numberedMenusStillWork() {
        val claude = """
 Do you want to proceed?
 ❯ 1. Yes
   2. Yes, and don't ask again for this command
   3. No, and tell Claude what to do differently (esc)
""".trimIndent()
        val p = detectPrompt(claude, blocked = true)!!
        assertEquals(3, p.choices.size)
        assertEquals("1", p.selected)
        assertEquals("Do you want to proceed?", p.question)
    }
}

/** Bypass modes are shown where the mode is shown, read from the footer only (display only). */
class BypassModeTest {
    @Test fun grokCardHintsAndOptionsAreNotBypass() {
        val card = """
  ┃  1 (○) Yes, and don't ask again for anything (always-approve mode)
  ┃  2 (●) Yes, proceed
  1/3:select  │  Tab:next option  │  Ctrl+o:always-approve  │  Ctrl+c:cancel  │  Esc:scrollback
""".trimIndent()
        assertEquals(null, agentMode("grok", card))
        assertEquals(null, agentMode("grok", "  │ ❯\n  Shift+Tab:mode  │  Ctrl+.:shortcuts"))
    }

    @Test fun footerWordsShowBypass() {
        assertEquals("bypass", agentMode("grok", "  │ ❯\n  always-approve on  │  Shift+Tab:mode"))
        assertEquals("bypass", agentMode("hermes", "❯\n ☤ gpt-6.1-sol · YOLO · ~125K/500K"))
        assertEquals("bypass", agentMode("codex", "› Ask Codex\n  GPT-6.1-Sol full access · ~/agents/codex"))
        assertEquals("bypass", agentMode("copilot", "❯\n Interactive · Allow All · / commands"))
        assertEquals(null, agentMode("copilot", "❯\n Interactive · Manual Approval · / commands"))
        assertEquals(null, agentMode("codex", "› Ask Codex\n  GPT-6.1-Sol default · ~/agents/codex"))
    }

    @Test fun claudeKeepsItsOwnRule() {
        assertEquals("bypass", agentMode("claude", "> \n  ⏵⏵ bypass permissions on (shift+tab to cycle)"))
        assertEquals("default", agentMode("claude", "> \n  ? for shortcuts"))
    }
}
