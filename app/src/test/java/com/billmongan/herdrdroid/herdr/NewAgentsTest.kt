package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiro CLI and Mistral Vibe: named, found, started, and recognized when herdr doesn't. */
class NewAgentsTest {
    @Test fun programNamesMapToAgents() {
        assertEquals("kiro", canonicalAgent("kiro-cli"))
        assertEquals("kiro", canonicalAgent("kiro"))
        assertEquals("vibe", canonicalAgent("vibe-rs"))
        assertEquals("vibe", canonicalAgent("mistral-vibe"))
    }

    @Test fun kiroRunsAsKiroCli() {
        assertEquals("kiro-cli", agentExecutable("kiro"))
        assertEquals("vibe", agentExecutable("vibe"))
        assertEquals("claude", agentExecutable("claude"))
    }

    @Test fun herdrStartsKiroButNotVibe() {
        assert("kiro" in HERDR_KINDS)
        assert("vibe" !in HERDR_KINDS)
    }

    @Test fun vibeIsKnownByItsTitle() {
        assertEquals("vibe", titleAgent("Vibe"))
        assertEquals("vibe", titleAgent("vibe - fixing tests"))
        assertNull(titleAgent("bill@demo-box2: ~/agents/vibe"))
        assertNull(titleAgent("Vibes check"))
        assertNull(titleAgent(""))
    }

    @Test fun displayNames() {
        assertEquals("Kiro", Pane(id = "p", tabId = "t", workspaceId = "w", agent = "kiro", status = Status.IDLE, title = "", cwd = "/").agentName)
        assertEquals("Vibe", Pane(id = "p", tabId = "t", workspaceId = "w", agent = "vibe", status = Status.UNKNOWN, title = "Vibe", cwd = "/").agentName)
    }

    @Test fun coreTerminalsRunningThemAreAgents() {
        val json = """{"terminals":[{"address":"a1","agent":"kiro-cli","status":"idle","title":"","cwd":"/k"},""" +
            """{"address":"a2","agent":"vibe","status":"","title":"Vibe","cwd":"/v"}]}"""
        val panes = corePanes(json)
        assertEquals(listOf("kiro", "vibe"), panes.map { it.agent })
    }

    // Screens captured from the live tabs on 2026-11-13 (trimmed).
    private val kiroFooter = """
 Trust All Tools active, confirmations are off · /quit to exit
─────────────────────────────────────────────────────────────
Default · auto · ◔ 2%                                       ~/agents/kiro

›  ask a question or describe a task ↵"""

    private val kiroUsage = """
 /usage
─────────────────────────────────────────────────────────────
 This session: 0.05 credits
 Estimated Usage | resets on 2026-12-01 | KIRO FREE
 Credits (0.06 of 50 covered in plan)
 ████████████████████████████████████████████████ 0.1%
 To manage your plan navigate to app.kiro.dev/account/usage
─────────────────────────────────────────────────────────────
 esc to close                                    Tab to switch to /context"""

    private val kiroModels = """
› /model
search   sort   edit
Select model:   type to search
  Model                Credit usage     Description
❯ auto                 1.00x credits    [active] Models chosen by task for optimal usage and consistent quality
  claude-sonnet-4.5    1.30x credits    Claude Sonnet 4.5 model
  claude-haiku-4.5     0.40x credits    The latest Claude Haiku model
  deepseek-3.2         0.25x credits    Experimental preview of DeepSeek V3.2
─────────────────────────────────────────────────────────────
 Settings for selected model: auto                          tab to switch panels
─────────────────────────────────────────────────────────────
 esc to go back · ↑↓ to navigate · ↵ to select"""

    private val vibeFooter = """
  pong
──────────────────────────────────────── Mistral Medium 3.5[high] · auto approve ─
>
──────────────────────────────────────────────────────────────────────────────────
~/agents/vibe                                  usage ▏      7% · ctx ▎      9.26k/202k"""

    private val vibeModels = """
┌──────────────────────────────────────────────────┐
│ Select Model                                     │
│    Default  (currently Mistral Medium 3.5)       │
│    GLM-5.3 Flash (Mistral Hosted)                │
│  › Mistral Medium 3.5                            │
│                                                  │
│ ↑↓/jk navigate  Enter save  s session only  Esc cancel │
└──────────────────────────────────────────────────┘
~/agents/vibe                                  usage ▏      0% · ctx ▎      9.81k/202k"""

    @Test fun kiroUsagePanelReads() {
        val p = QuotaProfiles.forAgent("kiro")!!
        assertEquals("/usage", p.command)
        val items = parseQuota(kiroUsage, p.bareMeansLeft)
        assertEquals(1, items.size)
        assertEquals("Credits (0.06 of 50 covered in plan)", items[0].label)
        assertEquals(0.1, items[0].usedPercent, 0.001)
        assertTrue(p.screenOpen(kiroUsage))
        // The footer's context gauge is not a limit.
        assertTrue(parseQuota(kiroFooter).isEmpty())
    }

    @Test fun vibeUsageComesFromItsFooter() {
        val p = QuotaProfiles.forAgent("vibe")!!
        assertNotNull(p.screenUsage)
        assertEquals("", p.command)
        val items = p.screenUsage!!(vibeFooter)
        assertEquals(1, items.size)
        assertEquals(7.0, items[0].usedPercent, 0.001)
        assertTrue(vibeFooterUsage("no footer here").isEmpty())
    }

    @Test fun footerModels() {
        assertEquals("auto", footerModel("kiro", kiroFooter))
        assertEquals("Mistral Medium 3.5 · high", footerModel("vibe", vibeFooter))
    }

    @Test fun modes() {
        assertEquals("bypass", agentMode("kiro", kiroFooter))
        assertEquals("plan", agentMode("kiro", kiroFooter.replace("Default · auto", "Plan · auto")))
        assertEquals("default", agentMode("kiro", kiroFooter.replace(" Trust All Tools active, confirmations are off · /quit to exit", "")))
        assertEquals("bypass", agentMode("vibe", vibeFooter))
        assertEquals("default", agentMode("vibe", vibeFooter.replace("· auto approve", "· ask · auto-approve")))
        assertEquals("accept edits", agentMode("vibe", vibeFooter.replace("· auto approve", "· accept edits · auto-approve")))
        assertEquals("auto", agentMode("vibe", vibeFooter.replace("· auto approve", "· smart approve · auto-approve")))
    }

    @Test fun kiroModelPickerReads() {
        assertNull(detectPrompt(kiroModels, blocked = true)?.takeIf { it.choices.size >= 2 })
        val menu = detectUnnumberedMenu(kiroModels)!!
        assertEquals(listOf("auto", "claude-sonnet-4.5", "claude-haiku-4.5", "deepseek-3.2"), menu.choices.map { it.label.substringBefore(" · ") })
        assertEquals("1", menu.selected)
        assertEquals("Select model", menu.question)
        assertEquals(listOf("down", "down", "enter"), menuKeys(menu, menu.choices[2]))
    }

    @Test fun vibeModelPickerReads() {
        val menu = detectUnnumberedMenu(vibeModels)!!
        assertEquals(listOf("Default · (currently Mistral Medium 3.5)", "GLM-5.3 Flash (Mistral Hosted)", "Mistral Medium 3.5"), menu.choices.map { it.label })
        assertEquals("3", menu.selected)
        assertEquals(listOf("up", "up", "enter"), menuKeys(menu, menu.choices[0]))
    }

    @Test fun noMenuOnAnInputLine() {
        assertNull(detectUnnumberedMenu(kiroFooter))
    }

    @Test fun backgroundKeyOnlyWhereItBackgrounds() {
        for (a in listOf("claude", "opencode", "grok", "agy")) assertTrue(a, Backgrounding.supported(a))
        for (a in listOf("codex", "pi", "omp", "hermes", "copilot", "kiro", "vibe", null)) assertFalse(a.toString(), Backgrounding.supported(a))
        assertEquals("ctrl+b", Backgrounding.KEY)
    }

    @Test fun slashCommandsForBoth() {
        val kiro = com.billmongan.herdrdroid.ui.SlashCommands.forAgent("kiro").map { it.name }
        val vibe = com.billmongan.herdrdroid.ui.SlashCommands.forAgent("vibe").map { it.name }
        assertTrue("/usage" in kiro && "/model" in kiro)
        assertTrue("/status" in vibe && "/model" in vibe)
    }
}
