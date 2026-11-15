package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.9 (Bill: "Check all the cards that all the panes can produce"): the card shapes the audit found
 * in each agent's own program, answered with the right keys. Screens in screens/<agent>/derived-1.4.9.
 */
class CardCoverageTest {
    private fun screen(agent: String, prefix: String): String {
        val dir = java.io.File(javaClass.classLoader!!.getResource("screens/$agent/derived-1.4.9")!!.toURI())
        return dir.listFiles()!!.first { it.name.startsWith(prefix) && it.name.endsWith(".txt") }.readText()
    }
    private fun card(agent: String, prefix: String, blocked: Boolean = true) = detectPrompt(screen(agent, prefix), blocked, agent).also { assertNotNull("$agent $prefix", it) }!!
    private fun keys(p: Prompt) = p.choices.map { menuKeys(p, it) }

    @Test fun openCodesButtonsMoveRightAndRejectWithEsc() {
        val p = card("opencode", "0701-permission-prompt-all-tools.txt".substring(0, 4))
        assertEquals(listOf("Allow once", "Always allow", "Reject"), p.choices.map { it.label })
        assertEquals(listOf(listOf("enter"), listOf("right", "enter"), listOf("esc")), keys(p))
        assertEquals(listOf(false, true, false), p.choices.map { grantsStandingPermission(it.label) })
    }

    @Test fun groksTrustAndPlanAreAnsweredByLetters() {
        val trust = card("grok", "0307")
        assertEquals(listOf("y", "n"), trust.choices.map { it.keys })
        assertEquals(listOf(null, null), keys(trust))  // typed as the letter itself
        val plan = card("grok", "0306")
        assertEquals(listOf("a", "s", "c", "q"), plan.choices.map { it.keys })
        assertEquals("Approve", plan.choices.first().label)
    }

    @Test fun hermesBracketedAndCheckboxRows() {
        val confirm = card("hermes", "1003")
        assertEquals(listOf("1", "2", "3"), confirm.choices.map { it.keys })
        assertTrue(grantsStandingPermission(confirm.choices[1].label))
        val clarify = card("hermes", "1002")
        assertTrue(clarify.multi)
        assertEquals("[ ] pytest", clarify.choices.first().label)
    }

    @Test fun ompAndKiroRowsWithDescriptions() {
        val omp = card("omp", "0602")
        assertEquals(listOf("SQLite (Recommended)", "Postgres", "Other (type your own)", "Chat about this"), omp.choices.map { it.label })
        val kiro = card("kiro", "0902")
        assertEquals(3, kiro.choices.size)
        assertTrue(kiro.choices.all { grantsStandingPermission(it.label) })
    }

    @Test fun piArrowHighlightAndVibeButtons() {
        val pi = card("pi", "0502")
        assertEquals("1", pi.selected)
        assertEquals(listOf(true, true, true, false, false), pi.choices.map { grantsStandingPermission(it.label) })
        val vibe = card("vibe", "0801")
        assertEquals(listOf(listOf("enter"), listOf("esc")), keys(vibe))
    }

    @Test fun codexShortcutsAndCautions() {
        val net = card("codex", "0202")
        assertEquals(listOf(listOf("y"), listOf("a"), listOf("p"), listOf("b"), listOf("esc")), keys(net))
        assertEquals(listOf(false, true, true, false, false), net.choices.map { grantsStandingPermission(it.label) })
        val mcp = card("codex", "0203")
        assertEquals(4, mcp.choices.size)
        assertEquals(listOf(false, true, true, false), mcp.choices.map { grantsStandingPermission(it.label) })
        // The footer's "esc to interrupt" is the card's own hint, not the agent working on (from review).
        assertEquals(3, card("codex", "0206-request-user-input-multi-question.txt".substring(0, 4)).choices.size)
    }

    @Test fun claudesCurlyApostropheRowsCarryTheCaution() {
        val mcp = card("claude", "0105")
        assertTrue(grantsStandingPermission(mcp.choices[1].label))
        val edit = card("claude", "0101")
        assertEquals("Do you want to make this edit to app.py?", edit.parts?.heading ?: edit.shownParts().heading)
    }

    @Test fun opencodesFeedbackPageIsATypedAnswer() {
        val p = detectPrompt(screen("opencode", "0703"), true, "opencode")
        assertNotNull(p)
        assertTrue(p!!.choices.isEmpty() && p.region != null)
    }
}
