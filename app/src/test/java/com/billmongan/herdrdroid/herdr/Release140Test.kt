package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.diag.Redact
import com.billmongan.herdrdroid.hermes.HermesError
import com.billmongan.herdrdroid.hermes.parseGatewayUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 1.4.0: the fallback card, answer types, menus past 9, the send guard's input line, the debug bundle's redaction, the gateway address. */
class Release140Test {
    @Test fun aQuestionNoParserReadsGetsTheFallbackCard() {
        val screen = """
 Some agent output

 Pick a deployment target:
   alpha cluster   beta cluster   gamma cluster
 Enter to select · ↑/↓ to navigate · Esc to cancel
""".trimIndent()
        val p = detectPrompt(screen, blocked = false)!!
        assertTrue(p.choices.isEmpty())
        assertNotNull(p.region)
        assertTrue(p.region!!.any { it.contains("alpha cluster") })
        // Plain output with no footer and herdr not saying it waits: no card at all.
        assertNull(detectPrompt("just some output\nand more output", blocked = false))
        // herdr says it waits but nothing on screen asks: a stale status, no card (1.4.2, Bill's
        // opencode pane). Something that asks near the bottom still gets the fallback card.
        assertNull(detectPrompt("Thinking about it\nstill here", blocked = true))
        assertNotNull(detectPrompt("Thinking about it\nShall I go on?", blocked = true)!!.region)
    }

    @Test fun menusPastNineOptions() {
        val rows = (1..12).joinToString("\n") { n -> (if (n == 1) " ❯ " else "   ") + "$n. Option $n" }
        val p = detectPrompt(" Which one?\n$rows\n Enter to select · Esc to cancel", blocked = true)!!
        assertEquals(12, p.choices.size)
        assertEquals("Option 12", p.choices.last().label)
        assertEquals("12", p.choices.last().keys)
        assertEquals(List(11) { "down" } + "enter", menuKeys(p, p.choices.last()))
    }

    @Test fun checkboxesToggleWithSpace() {
        val screen = """
 Which checks should run?
 ❯ 1. [ ] Tests
   2. [x] Lint
   3. [ ] Docs
   4. Submit
 Enter to select · ↑/↓ to navigate · Esc to cancel
""".trimIndent()
        val p = detectPrompt(screen, blocked = true)!!
        assertTrue(p.multi)
        assertEquals(listOf("down", "space"), menuKeys(p, p.choices[1]))
        assertEquals(listOf("down", "down", "down", "enter"), menuKeys(p, p.choices[3]))
    }

    @Test fun typedAnswerAndStandingPermissionOptions() {
        assertTrue(isTypedAnswerChoice("Type something."))
        assertTrue(isTypedAnswerChoice("Other"))
        assertFalse(isTypedAnswerChoice("Keep it open"))
        assertTrue(grantsStandingPermission("Yes, and don't ask again for mkdir commands"))
        assertTrue(grantsStandingPermission("Trust, always allow in this session"))
        assertTrue(grantsStandingPermission("Allow for remainder of this session"))
        assertTrue(grantsStandingPermission("Yes, and don't ask again for anything (always-approve mode)"))
        assertFalse(grantsStandingPermission("Yes, proceed"))
        assertFalse(grantsStandingPermission("No, reject (type to add feedback)"))
        // The highlight moves to "Type something." without Enter, so the typed answer goes there.
        val p = detectPrompt(" Keep it?\n ❯ 1. Yes\n   2. No\n   3. Type something.\n Enter to select · Esc to cancel", blocked = true)!!
        assertEquals(listOf("down", "down"), moveKeys(p, p.choices[2]))
    }

    @Test fun theInputLine() {
        assertEquals("", inputLineOf("output\n────\n❯ \n────\n  ? for shortcuts"))
        assertEquals("half a message", inputLineOf("output\n│ > half a message   │\n  footer"))
        assertNull(inputLineOf("no prompt line here\nat all"))
        // A menu row or a >> prompt is not someone's typing (review fix).
        assertNull(inputLineOf(" Proceed?\n ❯ 1. Yes\n   2. No"))
        assertNull(inputLineOf(">>> import os"))
    }

    @Test fun theDebugBundleLeavesNoSecrets() {
        val raw = "key sk-abcDEF1234567890abcdef token=ghp_abcdefghijklmnopqrstuvwxyz0123 " +
            "password: hunter2 url wss://secret.example/ssh mail user@example.com AKIAABCDEFGHIJKLMNOP"
        val r = Redact.text(raw)
        for (bad in listOf("sk-abcDEF", "ghp_abc", "hunter2", "secret.example", "user@example.com", "AKIAABCDEFGHIJKLMNOP")) assertFalse(r, r.contains(bad))
        assertTrue(r.contains("wss://<address hidden>"))
        assertEquals("plain screen text stays", Redact.text("plain screen text stays"))
        // Prose with the keywords is left alone; an Authorization header loses its value (review fixes).
        assertEquals("the token here is fine and the secret plan", Redact.text("the token here is fine and the secret plan"))
        val auth = Redact.text("Authorization: Bearer abcdefghijklmnop1234")
        assertFalse(auth, auth.contains("abcdefghijklmnop1234"))
        assertEquals("token=[redacted]", Redact.text("token=ghp_abcdefghijklmnopqrstuvwxyz0123"))
    }

    @Test fun agentVersions() {
        val out = "@@ver claude 2.1.325 (Claude Code)\n@@ver codex codex-cli 0.169.0\n@@ver vibe \n@@ver grok grok 4.7.1-beta.2\nnoise"
        assertEquals(mapOf("claude" to "2.1.325", "codex" to "0.169.0", "grok" to "4.7.1-beta.2"), AgentVersions.parse(out))
        assertTrue(AgentVersions.compare("1.10.0", "1.9.3") > 0)
        assertEquals(0, AgentVersions.compare("2.1", "2.1.0"))
        assertTrue(AgentVersions.compare("1.2.10+build.5", "1.2.9") > 0)
        assertTrue(AgentVersions.atLeast("0.169.0", "0.167.2"))
        assertFalse(AgentVersions.atLeast(null, "1.0"))
        assertTrue(AgentVersions.script().contains("timeout 5"))
        // The parser takes the agent and version; with no rule for them it reads as before.
        val s = " Do you want to proceed?\n ❯ 1. Yes\n   2. No\n Enter to select · Esc to cancel"
        assertEquals(detectPrompt(s, true), detectPrompt(s, true, "claude", "2.1.325"))
    }

    @Test fun gatewayAddresses() {
        assertEquals(Triple("127.0.0.1", 8765, "/ws"), parseGatewayUrl("ws://127.0.0.1:8765/ws"))
        assertEquals(Triple("localhost", 8765, "/"), parseGatewayUrl("ws://localhost:8765"))
        assertEquals(Triple("127.0.0.1", 9000, "/?token=x"), parseGatewayUrl("ws://127.0.0.1:9000?token=x"))
        assertEquals(Triple("::1", 9000, "/gw"), parseGatewayUrl("ws://[::1]:9000/gw"))
        try { parseGatewayUrl("wss://127.0.0.1:9000/gw"); fail("wss accepted") } catch (_: HermesError) {}
        try { parseGatewayUrl("http://x"); fail("http accepted") } catch (_: HermesError) {}
    }
}
