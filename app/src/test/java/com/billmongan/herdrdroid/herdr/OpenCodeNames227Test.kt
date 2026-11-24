package com.billmongan.herdrdroid.herdr

import org.junit.Assert.*
import org.junit.Test

/**
 * 2.2.7: OpenCode is recognized by its own name or any named launch of it ("opencode-<name>"),
 * so a pane started under any such name gets every OpenCode rule.
 */
class OpenCodeNames227Test {
    private val names = listOf("opencode", "OpenCode", "opencode-work", "opencode-lab2", "OpenCode-Anything")

    @Test fun anyOpencodeNameIsOpenCodeAndNothingElseIs() {
        for (n in names) assertTrue(n, isOpenCode(n))
        for (n in listOf(null, "", "opencodex", "my-opencode", "codex", "opencode_work")) assertFalse("$n", isOpenCode(n))
        assertEquals("opencode", ruleAgent("opencode-lab2"))
        assertEquals("codex", ruleAgent("Codex"))
    }

    @Test fun anArbitraryOpencodeNameGetsOpenCodesSessionsModelAndPanels() {
        val footer = "  ┃  Build · Sample Model (default) provider (local)"
        val box = buildString {
            appendLine("  ┌──────────────────────────────────────────────────────────────────────┐")
            appendLine("  │ ${"Status".padEnd(68)} │")
            appendLine("  │ ${"Model      sample".padEnd(68)} │")
            appendLine("  │ ${"esc close".padEnd(68)} │")
            appendLine("  └──────────────────────────────────────────────────────────────────────┘")
            appendLine()
            appendLine("  ╭──────────────────────────────────────────────────────────────────────╮")
            appendLine("  │ ❯                                                                    │")
            appendLine("  ╰──────────────────────────────────────────────────────────────────────╯")
        }
        val plain = infoPanelOn("opencode", box)
        for (n in names.drop(1)) {
            assertEquals(n, SessionControls.forAgent("opencode"), SessionControls.forAgent(n))
            assertEquals(n, "Sample Model", footerModel(n, footer))
            val p = checkNotNull(infoPanelOn(n, box)) { n }
            assertEquals(n, plain?.kind, p.kind)
            assertEquals(n, plain?.known, p.known)
        }
        assertTrue("the box is one OpenCode closes", plain?.known == true)
    }

    /** Grok's review: the composer, mode menu, background key and display name follow the same rule. */
    @Test fun aNamedLaunchBehavesLikeOpenCodeEverywhere() {
        for (n in names.drop(1)) {
            assertEquals(n, com.billmongan.herdrdroid.ui.SlashCommands.forAgent("opencode"), com.billmongan.herdrdroid.ui.SlashCommands.forAgent(n))
            assertEquals(n, "/models", com.billmongan.herdrdroid.ui.ModelPresets.pickerCommand(n))
            assertEquals(n, PaneControls.modes("opencode"), PaneControls.modes(n))
            assertTrue(n, Backgrounding.supported(n))
        }
        fun pane(agent: String) = Pane("p1", "t1", "w1", agent, Status.IDLE, "", "/fixture")
        assertEquals("OpenCode", pane("opencode").agentName)
        assertEquals("OpenCode (work)", pane("opencode-work").agentName)
        assertEquals("/model", com.billmongan.herdrdroid.ui.ModelPresets.pickerCommand("claude"))
    }
}
