package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.1: the fixes from Grok's review of 1.4.0. */
class Release141Test {
    @Test fun onlyTheComposerRowCountsAsTyping() {
        // A quoted "> " row in the output above the composer box is not typing.
        assertEquals("", inputLineOf("> a quoted line from the agent\nmore output\n────────────\n❯ \n────────────\n  ? for shortcuts"))
        // A quote or diff row under the composer box isn't either.
        assertEquals("", inputLineOf("output\n────────────\n❯ \n────────────\n> quoted after the box\n> and another"))
        // Colour codes around the prompt are taken out.
        assertEquals("half typed", inputLineOf("output\n────────────\n\u001B[1;36m❯\u001B[0m half typed\n────────────\n footer"))
        // A "> " row with a long screen of output under it is not the composer.
        assertEquals(null, inputLineOf("> diff line\n+ added\n- removed\n+ more\n  context\n  more context"))
        // Still found without a box, just above a short footer.
        assertEquals("half a message", inputLineOf("output\n│ > half a message   │\n  footer"))
    }

    @Test fun aRefusalGrantsNoStandingPermission() {
        assertFalse(grantsStandingPermission("No, don't trust this folder"))
        assertFalse(grantsStandingPermission("Never allow"))
        assertFalse(grantsStandingPermission("Deny, and always ask"))
        assertTrue(grantsStandingPermission("Yes, and don't ask again for this command"))
        assertTrue(grantsStandingPermission("Trust this folder"))
        assertTrue(grantsStandingPermission("Always allow"))
        assertTrue(grantsStandingPermission("Yes, allow for this session"))
    }

    @Test fun aPrereleaseComesBeforeItsRelease() {
        assertTrue(AgentVersions.compare("2.0.0-rc1", "2.0.0") < 0)
        assertTrue(AgentVersions.compare("2.0.0", "2.0.0-rc1") > 0)
        assertTrue(AgentVersions.compare("2.0.0-rc.2", "2.0.0-rc.1") > 0)
        assertTrue(AgentVersions.compare("2.0.0-alpha", "2.0.0-beta") < 0)
        assertTrue(AgentVersions.compare("2.0.0-rc.1", "2.0.0-rc.1.1") < 0)
        assertEquals(0, AgentVersions.compare("2.0.0+abc", "2.0.0"))
        assertTrue(AgentVersions.compare("2.0.1-rc1", "2.0.0") > 0)
        assertFalse(AgentVersions.atLeast("2.0.0-rc1", "2.0.0"))
        assertTrue(AgentVersions.atLeast("2.0.0", "2.0.0"))
    }

    @Test fun versionChecksRunTogetherAndAreWaitedFor() {
        val s = AgentVersions.script()
        assertTrue(s.trimEnd().endsWith("wait"))
        assertTrue(s.contains(") &"))
        assertEquals(mapOf("codex" to "0.60.1"), AgentVersions.parse("@@ver codex codex-cli 0.60.1\nnoise\n"))
    }
    @Test fun kirosApprovalRowIsSeenAsAQuestionNotTyping() {
        // What holdReason checks (1.4.1, from a second review): the row taken as the input line is
        // the highlighted option of the open menu, so the send is held as "a question is open".
        val screen = javaClass.getResource("/screens/kiro/captured-2026-11-13/shell-approval.txt")!!.readText()
        val row = inputLineOf(screen)
        assertEquals("Yes, single permission", row)
        assertTrue(detectUnnumberedMenu(screen)!!.choices.any { it.label.trim() == row })
    }
}
