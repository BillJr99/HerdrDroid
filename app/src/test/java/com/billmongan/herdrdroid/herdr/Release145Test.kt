package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.5: Meta's Muse Code (1.4.20) as a supported agent. */
class Release145Test {
    private val rule = "─".repeat(120)

    /** The bottom of a live Muse pane (2026-11-15): the composer between two rules, then the footer. */
    private fun museScreen(typed: String) = """
        ◆ 1) echo hi worked: hi

          Skills: 39 loaded · 1 warning · 1 detail hidden (ctrl+o to expand)

        $rule
        ❯$typed
        $rule
          muse-wonder-full-1.0 · high · ~/agents/muse · Launch overrides
    """.trimIndent()

    @Test fun museIsKnownByName() {
        val p = Pane("w2:pZ", "w2:tW", "w2", "muse", Status.IDLE, "muse", "/home/user/agents/muse")
        assertEquals("Muse", p.agentName)
        assertEquals("muse", canonicalAgent("muse-code"))
        // The program herdr-core reports: the binary Muse's launcher execs (from Grok's review).
        assertEquals("muse", canonicalAgent("muse-bin-1.4.20-R8619.1"))
        assertEquals("muse", canonicalAgent("/home/user/.local/bin/muse-bin-1.4.20-R8619.1"))
        assertEquals("muse", canonicalAgent("muse"))
        assertEquals("museum", canonicalAgent("museum"))
        assertEquals("kiro", canonicalAgent("kiro-cli"))
        assertTrue("muse" in HERDR_KINDS)
        assertTrue(Backgrounding.supported("muse"))
        assertTrue(com.billmongan.herdrdroid.ui.SlashCommands.forAgent("muse").map { it.name }.containsAll(listOf("/model", "/compact", "/usage")))
    }

    @Test fun musesFooterGivesModelAndEffort() {
        assertEquals("muse-wonder-full-1.0 · high", footerModel("muse", museScreen("")))
    }

    @Test fun musesComposerIsReadAsTheInputLine() {
        assertEquals("", inputLineOf(museScreen(""), "muse"))
        assertEquals("/exit", inputLineOf(museScreen(" /exit"), "muse"))
    }

    @Test fun musesUsageParsesPrettyJsonAndACompactLineAfterDiagnostics() {
        val json = """
            {
              "allowance": {"window": {"usedPercent": 23}, "weekly": {"usedPercent": 60}}
            }
        """.trimIndent()
        val compactWithBanner = "Loading usage…\n" + json.lines().joinToString("")
        assertEquals(listOf("Muse window", "Muse weekly"), museUsage(json).map { it.label })
        assertEquals(museUsage(json), museUsage(compactWithBanner))
        assertTrue(museUsage("{").isEmpty())
    }

    @Test fun musesUsageFromItsCli() {
        val prof = QuotaProfiles.forAgent("muse")
        assertNotNull(prof)
        assertTrue(prof!!.cliCommand!!.contains("muse usage --json"))
        assertTrue(prof.cliCommand!!.contains("MUSE_NO_AUTO_UPDATE=1"))
        // `muse usage --json` on 2026-11-15.
        val json = """{"allowance":{"asOfMs":1794750989253,"source":"mint","tier":"Muse Code Power Usage","weekly":{"resetsAtMs":1794787200000,"usedPercent":0},"window":{"resetsAtMs":1794768364000,"usedPercent":2}},"hint":null,"reason":null,"schemaVersion":1}"""
        val items = prof.parseCli!!(json)
        assertEquals(listOf("Muse window", "Muse weekly"), items.map { it.label })
        assertEquals(2.0, items[0].usedPercent, 0.0)
        assertEquals("2026-11-15T18:46:04Z", items[0].resets)
        assertTrue(shortReset(items[0].resets!!).isNotBlank())
        // A new account has no data yet: no usage, not an error.
        assertEquals(emptyList<QuotaItem>(), museUsage("""{"allowance":null,"hint":null,"reason":"server_sent_no_usage","schemaVersion":1}"""))
    }

    @Test fun musesListMarkerIsAMenuHighlight() {
        // Muse marks the current row of its lists with "⟩" (its /help command list).
        val screen = "Choose\n  /upgrade\n  /usage\n⟩ /workflows\n\n  Esc to close"
        val m = detectUnnumberedMenu(screen)
        assertNotNull(m)
        assertEquals("3", m!!.selected)
        assertNull(detectUnnumberedMenu(museScreen("")))
    }
}
