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
}
