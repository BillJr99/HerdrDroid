package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 1.4.8: Muse Code's approval cards with two options, a tall command, and the header off the read window. */
class Release148Test {
    private val rule = "─".repeat(133)
    private val proceed = javaClass.getResource("/screens/muse/captured-2026-11-15/proceed-approval.txt")!!.readText()

    /** A card as Bill saw it: a long heredoc above the options, two options, no "Always allow". */
    private val tall = buildString {
        appendLine("◈ Calling tools (3m 2s · esc to interrupt)")
        appendLine()
        appendLine(rule)
        appendLine("Would you like to run the following command?")
        appendLine()
        appendLine("  $ python3 - <<'PY'")
        appendLine("  │ import json,pathlib,re")
        appendLine("  │ for label,p in")
        appendLine("    [(\"a\",\"/home/user/work/demo/a-terminal.cast\"),(\"b\",\"/home/user/work/demo/b-terminal.cast\")]:")
        appendLine("  │     rows=[json.loads(x) for x in pathlib.Path(p).read_text().splitlines()]")
        for (i in 1..12) appendLine("  │     step$i = [r for r in rows if len(r) > $i]  # check $i")
        appendLine("  │     print(label, len(rows))")
        appendLine("  │ PY")
        appendLine("  Stage 1/1")
        appendLine("  Current argv: [\"python3\"]")
        appendLine()
        appendLine("⟩ 1. Allow this stage once (y)")
        appendLine("  2. Abort the entire command (esc)")
        appendLine("Change approval defaults in /permissions")
        appendLine(rule)
        appendLine("  muse-wonder-full-1.0 · max · ~/agents/muse · Launch overrides")
    }

    private fun check(screen: String, labels: List<String>, keys: List<List<String>>, blocked: Boolean, agent: String?) {
        val p = detectPrompt(screen, blocked, agent)
        assertNotNull(p)
        assertEquals(labels, p!!.choices.map { it.label })
        assertEquals("1", p.selected)
        assertEquals(keys, p.choices.map { menuKeys(p, it) })
    }

    @Test fun theTwoOptionCardAsDrawnLive() {
        // herdr said "working" while this card waited, so the card must not need "blocked".
        for (agent in listOf("muse", null)) check(proceed, listOf("Yes, proceed (y)", "No, and tell Muse Code what to do differently (esc)"),
            listOf(listOf("y"), listOf("esc")), blocked = false, agent = agent)
    }

    @Test fun aTallCardWithTwoOptions() {
        for (agent in listOf("muse", null)) for (blocked in listOf(true, false))
            check(tall, listOf("Allow this stage once (y)", "Abort the entire command (esc)"), listOf(listOf("y"), listOf("esc")), blocked, agent)
        val p = detectPrompt(tall, true, "muse")!!
        assertEquals("Would you like to run the following command?", p.parts?.heading ?: p.shownParts().heading ?: p.question)
    }

    @Test fun theHeaderOffTheReadWindow() {
        // Only the bottom of the card was read: the options and the /permissions line are enough.
        val bottom = tall.lines().takeLast(14).joinToString("\n")
        check(bottom, listOf("Allow this stage once (y)", "Abort the entire command (esc)"), listOf(listOf("y"), listOf("esc")), blocked = true, agent = "muse")
        check(bottom, listOf("Allow this stage once (y)", "Abort the entire command (esc)"), listOf(listOf("y"), listOf("esc")), blocked = false, agent = null)
    }
}
