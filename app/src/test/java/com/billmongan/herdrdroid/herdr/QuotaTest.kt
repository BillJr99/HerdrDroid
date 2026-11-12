package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screens captured from each agent's usage command, trimmed to the parts that matter. */
class QuotaTest {
    private fun one(items: List<QuotaItem>, label: String) = items.single { it.label == label }

    @Test fun claude() {
        val s = """
   Current week (all models)
   █████████████████████████                          50% used
   Resets Nov 12, 2:59am (America/New_York)

   Current week (Fable)
   █████████████                                      26% used
   Resets Nov 12, 2:59am (America/New_York)

   100% Give "fork" subagents a cheaper model
   91%  Start fresh when you switch tasks · run /clear

   Subagents               % of usage
   fork                           16%

   Usage credits
                                                      0% used
   ${'$'}0.00 / ${'$'}200.00 spent · Resets Dec 1 (America/New_York)
"""
        val items = parseQuota(s)
        assertEquals(3, items.size)
        assertEquals(50.0, one(items, "Current week (all models)").usedPercent, 0.01)
        assertEquals("Nov 12, 2:59am", one(items, "Current week (all models)").resets)
        assertEquals("Dec 1", one(items, "Usage credits").resets)
    }

    @Test fun codex() {
        val s = """
  Model:               GPT (reasoning low)
  Weekly limit:        [████████████████████] 100% left (resets 6:04 AM on 18 Nov)
  Context window:      [████████████████████] 95% left (12K used / 258K)
  Credits:             2013 credits
"""
        val items = parseQuota(s)
        assertEquals(1, items.size)
        assertEquals(0.0, one(items, "Weekly limit").usedPercent, 0.01)
        assertEquals("6:04 AM on 18 Nov", one(items, "Weekly limit").resets)
    }

    @Test fun copilot() {
        val items = parseQuota("""
   Active account/provider usage
   Plan       ■■■■■■■■■■■■■■■■■■■■ 0% used
              0 / 1,500 AIC
""")
        assertEquals(listOf("Plan"), items.map { it.label })
    }

    @Test fun grok() {
        val items = parseQuota("""
                       │  Weekly limit (SuperGrok)                                                          │
                       │  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  0%                                                │
                       │  Resets: November 17, 12:44                                                        │
""")
        assertEquals(1, items.size)
        assertEquals("Weekly limit (SuperGrok)", items[0].label)
        assertEquals("November 17, 12:44", items[0].resets)
    }

    @Test fun antigravity() {
        val items = parseQuota("""
CLAUDE AND GPT MODELS
  Models within this group: Claude Opus, Claude Sonnet, GPT-OSS
  Weekly Limit Remaining
    [██████████████████████████████████████████████████] 80.00%
    Quota available
  Five Hour Limit Remaining
    [██████████████████████████████████████████████████] 100.00%
    Quota available
GEMINI 4 ARGON
  Gemini 4 Argon Weekly Limit
    [██████████████████████████████████████████████████] 100.00%
    Quota available
""", bareMeansLeft = true)
        assertEquals(3, items.size)
        assertEquals(20.0, one(items, "Claude and GPT Models · Weekly Limit Remaining").usedPercent, 0.01)
        assertEquals(0.0, one(items, "Gemini 4 Argon Weekly Limit").usedPercent, 0.01)
    }

    @Test fun hermes() {
        val items = parseQuota("""
Nous Rate Limits (captured just now):

  Requests/min [██░░░░░░░░] 12.3%  12/100 used  (88 left, resets in 30s)
  ⚠ requests at 85% — resets in 2m
""")
        assertEquals(1, items.size)
        assertEquals(12.3, items[0].usedPercent, 0.01)
        assertEquals("30s", items[0].resets)
    }

    @Test fun piFooterIsNotAQuota() {
        assertTrue(parseQuota("0.0%/128k (auto)      (compat-llmproxy) llmproxy/flagship__free").isEmpty())
    }

    @Test fun profilesByAgent() {
        assertEquals("agy", QuotaProfiles.key("Antigravity"))
        assertEquals(null, QuotaProfiles.key("opencode"))
        assertEquals(null, QuotaProfiles.key(null))
    }

    /** Grok draws its box over the conversation; the text to its left must not leak into labels. */
    @Test fun grokOverConversation() {
        val items = parseQuota("""
                       │─────────────────────────────────────────────────────────────────────────────────────│
     Worked for 12s    │  Weekly limit (SuperGrok)                                                           │
                       │                                                                                     │
                       │  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  0%                                                 │
     ❯ A long line of t│  Resets: November 17, 12:44                                                         │ally         8:59
       keeps 50% going │                                                                                     │only an
                       │  Credits: ${'$'}100.00                                                              │ reset
""")

        assertEquals(1, items.size)
        assertEquals("Weekly limit (SuperGrok)", items[0].label)
        assertEquals(0.0, items[0].usedPercent, 0.01)
        assertEquals("November 17, 12:44", items[0].resets)
    }

    /** Hermes's real /usage, with its status-bar footer, which is not a limit. */
    @Test fun hermesAccountLimits() {
        val items = parseQuota("""
  Current context:  123,055 / 500,000 (25%)
  Messages:         185

  📈 Account limits
  Provider: openai-codex (Prolite)
  Weekly: 100% remaining (0% used) • resets in 6d 21h (2026-11-18 06:04 EST)
  You have 2 resets banked - use /usage reset to activate
 ☤ gpt │ ~124K/500K │ [██░░░░░░░░] ~25% │ ◎ 99.8% │ ◷ 6.0s │ ↑ 25 t/s │ 3.0d │ ⏲ 27s │ ✓ 1
""")
        assertEquals(listOf("Weekly"), items.map { it.label })
        assertEquals(0.0, items[0].usedPercent, 0.01)
        assertEquals("6d 21h", items[0].resets)
    }

    /** Antigravity's real screen, including its intro lines and blank lines between limits. */
    @Test fun antigravityLive() {
        val items = parseQuota("""
└ Models & Quota

  Account: someone@example.com

GEMINI MODELS
  Models within this group: Gemini Flash, Gemini Pro

  Weekly Limit Remaining
    [██████████████████████████████████████████████████] 100.00%
    Quota available

  Five Hour Limit Remaining
    [██████████████████████████████████████████████████] 100.00%
    Quota available


CLAUDE AND GPT MODELS
  Models within this group: Claude Opus, Claude Sonnet, GPT-OSS

  Weekly Limit Remaining
    [██████████████████████████████████████████████████] 100.00%
    Quota available

GEMINI 4 ARGON
  For paid Pro plans, Argon quota is separate. Higher Argon quota (included as part of the larger Gemini quota bucket) i

  Gemini 4 Argon Weekly Limit
    [██████████████████████████████████████████████████] 100.00%
    Quota available
""", bareMeansLeft = true)
        assertEquals(4, items.size)
        assertTrue(items.all { it.usedPercent == 0.0 })
    }

    @Test fun shortLabels() {
        assertEquals("Week (all)", shortLabel("Current week (all models)"))
        assertEquals("Week (Fable)", shortLabel("Current week (Fable)"))
        assertEquals("Week", shortLabel("Weekly limit"))
        assertEquals("Week (SuperGrok)", shortLabel("Weekly limit (SuperGrok)"))
        assertEquals("Claude/GPT 5h", shortLabel("Claude and GPT Models · Five Hour Limit Remaining"))
        assertEquals("Gemini 4 Argon Week", shortLabel("Gemini 4 Argon Weekly Limit"))
        assertEquals("Plan", shortLabel("Plan"))
        assertEquals("Usage credits", shortLabel("Usage credits"))
    }

    /** Footers captured from each agent's pane on demo-box2. */
    @Test fun modelsFromFooters() {
        assertEquals("GPT-6.1-Sol · default", footerModel("codex", """
› Ask Codex to do anything
  GPT-6.1-Sol default · ~/agents/codex
  ← for agents · ? for shortcuts
"""))
        assertEquals("Gemini 4 Argon · medium", footerModel("agy", """
>
? for shortcuts                                                                                               Gemini 4 Argon · medium
"""))
        assertEquals("Grok 4.7 (high)", footerModel("grok", """
  │ ❯                                                                                                                              │
  ╰────────────────────────────────────────────────────────────────────────────────────────────────────────────── Grok 4.7 (high) ─╯
  Shift+Tab:mode  │  Ctrl+.:shortcuts
"""))
        assertEquals("gpt-6.1-sol", footerModel("hermes", """
 ☤ gpt-6.1-sol │ ~124K/500K │ [██░░░░░░░░] ~25% │ ◎ 99.8% │ ◷ 6.0s │ ↑ 25 t/s │ 3.0d │ ⏲ 27s │ ✓ 1... ─ Complete teaching brief r...
❯ Type / to browse commands, or Ctrl+P for the palette
"""))
        assertEquals("GPT-5.6 Terra", footerModel("copilot", """
❯
 Update: v1.0.109 · next launch or /  ·  ← open sidebar · Interactive · Manual Approval · / commands · tab next tab     GPT-5.6 Terra
"""))
        assertEquals("Big Pickle OpenCode", footerModel("opencode", """
  ┃
  ┃  Build · Big Pickle OpenCode
  ╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
  ~/agents/opencode                                           54.1K (27%)  ctrl+p commands    ~/agents/opencode
"""))
        assertEquals("llmproxy/flagship__free", footerModel("pi", """
~/agents/pi
0.0%/128k (auto)                                                                             (compat-llmproxy) llmproxy/flagship__free
"""))
        assertEquals(null, footerModel("claude", "❯\n  ⏵⏵ auto mode on (shift+tab to cycle) · esc to interrupt"))
    }

    @Test fun claudeModelPicker() {
        val screen = """
   Select model
   ❯ 1.  Default (recommended) ✔  Fable 5.2
     2.  Opus 5.5                 For complex work and everyday tasks
     3.  Fable 5.2                For your toughest challenges
   ◐ Medium effort (default) ←/→ to adjust
   Enter to set as default · s to use this session only · Esc to cancel
"""
        assertEquals(PaneInfo("Fable 5.2", "medium"), claudeModelScreen(screen))
        val picked = "   ❯ 2.  Opus 5.5 ✔               For complex work and everyday tasks\n   ◐ High effort ←/→ to adjust"
        assertEquals(PaneInfo("Opus 5.5", "high"), claudeModelScreen(picked))
        val p = QuotaProfiles.forAgent("claude")!!
        assertEquals("/model", p.infoCommand)
        assertEquals(false, p.whileWorking)
        assertTrue(p.openMarker!!.containsMatchIn("   Esc to cancel"))
        assertTrue(p.screenOpen(screen))
        assertEquals(false, p.screenOpen("I'll use high effort here.\n Do you want to proceed?\n ❯ 1. Yes\n Esc to cancel"))
    }

    @Test fun hermesReasoning() {
        val p = QuotaProfiles.forAgent("hermes")!!
        val screen = "⚙️  /reasoning\n  Reasoning effort:  medium\n  Reasoning display: on ✓ (clamped to 10 lines)\n"
        assertEquals(PaneInfo(null, "medium"), p.parseInfo!!(screen))
    }

    /** With Antigravity's usage screen still open, the model line has nothing to its left. */
    @Test fun antigravityFooterUnderDialog() {
        assertEquals("Gemini 4 Argon · medium", footerModel("agy", """
  ↑/↓ Scroll · pgup/pgdown Page · ctrl+end Bottom · ctrl+home Top · esc Close
                                                                                                              Gemini 4 Argon · medium
"""))
    }

    @Test fun leftoverScreenIsRecognisedOnlyWithItsNumbers() {
        val p = QuotaProfiles.forAgent("claude")!!
        val usage = """
   Current week (all models)
   █████████████████████████                          50% used
   Resets Nov 12, 2:59am (America/New_York)

   Esc to cancel
"""
        assertTrue(p.screenOpen(usage))
        val permission = """
 Bash command
   rm -rf build
 Do you want to proceed?
 ❯ 1. Yes
   2. No
   Esc to cancel
"""
        assertEquals(false, p.screenOpen(permission))
        assertEquals(false, p.screenOpen("❯ \n  ⏵⏵ auto mode on · esc to interrupt"))
        assertEquals(false, p.whileWorking)
    }

    @Test fun profilesWithoutAScreenNeverClose() {
        val codex = QuotaProfiles.forAgent("codex")!!
        assertEquals(false, codex.screenOpen("Esc to cancel 50% used"))
    }

    @Test fun ompUsageJson() {
        val json = """{"generatedAt":1,"reports":[{"provider":"anthropic","fetchedAt":1,"limits":[
            {"id":"5h","label":"5-hour","scope":{"provider":"anthropic"},"window":{"id":"5h","label":"5h","resetLabel":"in 2h"},"amount":{"usedFraction":0.42,"unit":"percent"}},
            {"id":"7d","label":"Weekly","scope":{"provider":"anthropic"},"amount":{"used":30,"limit":120,"unit":"requests"}}]}],
            "accountsWithoutUsage":[],"disabledCredentials":[],"capacity":{}}"""
        val items = ompUsage(json)
        assertEquals(2, items.size)
        assertEquals(42.0, items[0].usedPercent, 0.01)
        assertEquals("anthropic 5-hour", items[0].label)
        assertEquals("in 2h", items[0].resets)
        assertEquals(25.0, items[1].usedPercent, 0.01)
        assertEquals(0, ompUsage("""{"reports":[],"accountsWithoutUsage":[]}""").size)
        val p = QuotaProfiles.forAgent("omp")!!
        assertEquals("omp usage --json", p.cliCommand)
    }
}
