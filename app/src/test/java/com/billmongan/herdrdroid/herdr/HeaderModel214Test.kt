package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2.1.4: the model on the pane screen's title bar, per agent, from screens and logs as they are. */
class HeaderModel214Test {
    /** Two assistant records from a Claude session log tail: the newest one wins. */
    private val claudeLog = """
{"parentUuid":"a1","isSidechain":false,"type":"assistant","message":{"model":"claude-sonnet-5-1","id":"msg_01","type":"message","role":"assistant","content":[{"type":"text","text":"ok"}]},"uuid":"b1"}
{"parentUuid":"b1","isSidechain":false,"type":"user","message":{"role":"user","content":"next"},"uuid":"c1"}
{"parentUuid":"c1","isSidechain":false,"type":"assistant","message":{"model":"claude-opus-5-5","id":"msg_02","type":"message","role":"assistant","content":[{"type":"text","text":"done"}]},"uuid":"d1"}
""".trimIndent()

    /** What Herdr.logModels' reader does with a log tail: the last "claude…" model, pretty. */
    private fun logModel(tail: String) = Regex(""""model"\s*:\s*"([^"]+)"""").findAll(tail)
        .map { it.groupValues[1] }.filter { it.startsWith("claude") }.lastOrNull()?.let(::prettyClaudeModel)

    @Test fun explicitClaudeFooterAndSwitchResultAreModelsButPickerChoicesAreNot() {
        assertEquals("Fable 5.2", footerModel("claude", "❯ \n  ⏵ auto mode · Model: Fable 5.2"))
        assertEquals("Sonnet 4.6", footerModel("claude", "❯ \n  ⎿  Set model to Sonnet 4.6"))
        assertNull(footerModel("claude", "Select model\n❯ 3. Fable 5.2 ✔"))
    }

    @Test fun claudeTakesTheLatestModelFromItsSessionLog() {
        val known = logModel(claudeLog)
        assertEquals("Opus 5.5", known)
        assertEquals("Opus 5.5", headerModel("claude", known))
    }

    @Test fun claudeEffortStaysWithTheModel() {
        assertEquals("Opus 5.5 · high", headerModel("claude", "Opus 5.5 · high"))
    }

    @Test fun claudeBannerOnScreenWhenThereIsNoLog() {
        val screen = """
 ▐▛███▜▌   Claude Code v2.4.1
▝▜█████▛▘  Opus 5.5 · Claude Max
  ▘▘ ▝▝    ~/agents/claude

> hello
""".trimIndent()
        assertEquals("Opus 5.5", headerModel("claude", null, screen))
        // A chat line that merely names a model is not the banner.
        assertNull(headerModel("claude", null, "  3. The model in use, for example \"Opus 5.5\" · smaller"))
        assertNull(bannerModel("codex", screen))
    }

    @Test fun grokComposerFooterGivesTheModelAndEffortWithoutTheMode() {
        val screen = """
     Worked for 20m15s

  ╭──────────────────────────────────────────────────────────────────────────────────╮
  │ ❯                                                                                │
  ╰────────────────────────────────────────────────── Grok 4.7 (high) · auto-review ─╯

  Shift+Tab:mode  │  Ctrl+.:shortcuts
""".trimIndent()
        assertEquals("Grok 4.7 (high)", headerModel("grok", null, screen))
        // The card's model line, as the app already holds it, is tidied the same way.
        assertEquals("Grok 4.7 (high)", headerModel("grok", "Grok 4.7 (high) · auto-review"))
        assertEquals("Grok 4.7 · high", tidyHeaderModel("grok", "Grok 4.7 · high"))
    }

    @Test fun otherFootersAsTheyAreOnScreen() {
        assertEquals("Orchestrator (Neon)", headerModel("agy", null,
            "──────────\n>\n──────────\n? for shortcuts                                                   Orchestrator (Neon)"))
        assertEquals("Mistral Medium 3.5 · high", headerModel("vibe", null,
            "──────────────────────── Mistral Medium 3.5[high] · smart approve ─\n>\n"))
        assertEquals("GPT-6.2-Sol · default", headerModel("codex", null,
            "› Ask Codex to do anything\n\n  GPT-6.2-Sol default · ~/agents/codex · Select argon model\n  ← for agents · ? for shortcuts"))
    }

    @Test fun terminalTitleOnlyWhenItHoldsAModelId() {
        assertEquals("gemini-pro-orchestrator-neon", headerModel("agy", null, "", "agy · gemini-pro-orchestrator-neon"))
        assertEquals("Opus 5.5", headerModel("claude", null, "", "claude-opus-5-5 | Laptop2"))
        assertEquals("gpt-6.2-sol", titleModel("codex (gpt-6.2-sol)"))
        // Real titles from herdr agent list name the conversation, not the model.
        for (t in listOf("◑ Laptop2", "Select argon model | codex", "bill@laptop2: ~", "Grok quota checks, mean review, setup v… - grok",
                "Run Shell Command and Report - GitHub Copilot", "OC | 95% quota pause rule", "π - pi", "muse", "cs374 tutorial examples")) {
            assertNull(t, titleModel(t))
        }
    }

    @Test fun unknownLeavesTheLineOff() {
        assertNull(headerModel("claude", null, "> hello\n  ⏵⏵ accept edits on", "◑ Laptop2"))
        assertNull(headerModel(null, null))
        assertNull(headerModel("grok", "  "))
    }

    @Test fun knownModelComesBeforeTheScreen() {
        assertEquals("Opus 5.5", headerModel("claude", "Opus 5.5", "▝▜█████▛▘  Sonnet 5.1 · Claude Max"))
    }
}
