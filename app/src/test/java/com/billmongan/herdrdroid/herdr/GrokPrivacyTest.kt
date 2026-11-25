package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.7: Grok's "Help improve Grok" data-sharing banner is its own question, answered through
 * Grok's /privacy dialog, and never merges into an approval card's question. Screens as Grok 4.7
 * drew them on devbox2 on 2026-11-13.
 */
class GrokPrivacyTest {
    private val banner = """
     Worked for 4.4s
  Help improve Grok                                                                                               [Opt out] [Opt in]
  Off by default. Opt-in to allow SpaceXAI to retain coding data, e.g., prompts, traces, & metrics, for training
  and debugging purposes. Change anytime via settings.
  Read Terms and Privacy Policy.
  ╭──────────────────────────────────────────────────────────╮
  │ ❯                                                        │
  ╰──────────────────────────────────────── Grok 4.7 (high) ─╯
  Shift+Tab:mode  │  Ctrl+.:shortcuts
""".trimIndent()

    private val dialog = """
   ┌─ Settings › Coding data, retention, and training ─────────────── [✗] ─┐
   │  Coding data, retention, and training                                 │
   │  Opt-in to provide SpaceXAI the ability to retain and train on coding │
   │  data, e.g., prompts, traces, & metrics, for training and debugging   │
   │  purposes. We may still collect simple user metrics, e.g. how many    │
   │  times you use the product or a feature.                              │
   │   ○  Opt in                                                           │
   │   ●  Opt out                                                          │
   │        ↑/↓ nav  |  Enter select  |  double-click select  |  Esc cancel │
   └───────────────────────────────────────────────────────────────────────┘
""".trimIndent()

    @Test fun bannerIsItsOwnQuestion() {
        val p = detectPrompt(banner, blocked = false)!!
        assertEquals(GROK_PRIVACY_PROMPT, p)
        assertEquals(listOf(GROK_PRIVACY_OUT, GROK_PRIVACY_IN), p.choices.map { it.keys })
        assertTrue(showsGrokBanner(banner))
    }

    @Test fun dialogIsAQuestionAnsweredWithArrows() {
        val p = detectGrokPrivacyDialog(dialog)!!
        assertEquals(listOf("Opt in", "Opt out"), p.choices.map { it.label })
        assertEquals("2", p.selected)
        assertEquals(listOf("up", "enter"), menuKeys(p, p.choices[0]))
        assertEquals(listOf("enter"), menuKeys(p, p.choices[1]))
        assertEquals(p, detectPrompt(dialog, blocked = false))
    }

    @Test fun framedDialogBelowARunningRowRemainsAQuestion() {
        val running = "⠧ Thinking… 4s [stop]"
        val screen = "$running\n$dialog"
        assertFalse(grokWorkingBelowAnyQuestion(screen))
        val card = checkNotNull(detectPrompt(screen, blocked = false, agent = "grok"))
        assertEquals(listOf("Opt in", "Opt out"), card.choices.map { it.label })
        assertEquals("2", card.selected)
        assertTrue(grokWorkingBelowAnyQuestion("$dialog\n$running"))
        assertNull(detectPrompt("$dialog\n$running", blocked = true, agent = "grok"))
    }

    @Test fun grokInputBox() {
        assertEquals("", grokInputText(banner))
        assertEquals("/usage", grokInputText(banner.replace("│ ❯                                                        │", "│ ❯ /usage                                                 │")))
        assertNull(grokInputText(dialog))
    }

    @Test fun bannerNeverMergesIntoACard() {
        val card = """
  Help improve Grok                                                                                               [Opt out] [Opt in]
  Off by default. Opt-in to allow SpaceXAI to retain coding data, e.g., prompts, traces, & metrics, for training
  and debugging purposes. Change anytime via settings.
  Read Terms and Privacy Policy.
  ┃
  ┃  ›  Check SSH to devbox in batch mode
  ┃  │ ssh -o BatchMode=yes user@192.0.2.26 true
  ┃
  ┃  1 (○) Yes, and don't ask again for anything (always-approve mode)
  ┃  2 (●) Yes, proceed
  ┃  3 (○) No, reject (type to add feedback)
  ┃
  1/3:select  │  Tab:next option  │  Ctrl+o:always-approve  │  Ctrl+c:cancel  │  Esc:scrollback
""".trimIndent()
        val p = detectPrompt(card, blocked = true)!!
        assertEquals(3, p.choices.size)
        assertFalse(p.question, p.question.contains("Grok") || p.question.contains("SpaceXAI") || p.question.contains("Privacy"))
        assertTrue(p.question, p.question.startsWith("Check SSH to devbox"))
    }

    @Test fun codexQuestionStopsAtTheConversationAboveIt() {
        // From the emulator test of 1.2.6: the question took in the whole chat above the card.
        val screen = """
  codex stub

› card
● card cancelled (Esc)
› /status
● Stub reply from codex: I received "/status".
› card

  Would you like to run the following command?
  ${'$'} mkdir -p sub
› 1. Yes, proceed (y)
  2. Yes, and don't ask again for commands that start with `mkdir` (p)
  3. No, and tell Codex what to do differently (esc)

  Press enter to confirm or esc to cancel
""".trimIndent()
        val p = detectPrompt(screen, blocked = false)!!
        assertEquals("Would you like to run the following command? \$ mkdir -p sub", p.question)
    }

    @Test fun noBannerNoQuestion() {
        val quiet = banner.lines().filterNot { it.contains("Opt") || it.contains("Help improve") || it.contains("debugging") || it.contains("Privacy") }.joinToString("\n")
        assertFalse(showsGrokBanner(quiet))
        assertNull(detectPrompt(quiet, blocked = false))
        assertNotNull(GROK_PRIVACY_PROMPT.question)
    }
}

/** Bill, 2026-11-13: "the last question didn't come through quite right". Grok 4.7's real card. */
class GrokStatusLineTest {
    private val screen = """
     ◆ Running print plan usage with no session id
   ◆ Print plan usage with no session id… 7.5s                                                                  26s ⇣87.0k [↓][stop]
  ┃  ›  Print plan usage with no session id
  ┃  │ grok usage 2>&1; echo "EXIT:${'$'}?"
  ┃  1 (○) Yes, and don't ask again for anything (always-approve mode)
  ┃  2 (●) Yes, proceed
  ┃  3 (○) No, reject (type to add feedback)
  1/3:select  │  Tab:next option  │  Ctrl+o:always-approve  │  Ctrl+c:cancel  │  Esc:scrollback
""".trimIndent()

    @Test fun titleThenCommandAndNothingElse() {
        val p = detectPrompt(screen, blocked = true)!!
        assertEquals("Print plan usage with no session id: grok usage 2>&1; echo \"EXIT:\$?\"", p.question)
        assertEquals("2", p.selected)
        assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[2]))
    }
}

/** 1.2.7: a numbered list in an ordinary reply is not a menu; Claude's real question dialog is. */
class ProseIsNotAMenuTest {
    private val prose = """
● Here is what I would do next:
  1. Back up the database first
  2. Update the packages on both machines
  3. Reboot when everything is idle
  Which of these do you want me to start with?

────────────────────────────────────────────────────────────
> 
────────────────────────────────────────────────────────────
  ? for shortcuts
""".trimIndent()

    // Claude Code's AskUserQuestion dialog: a highlighted row and its footer.
    private val ask = """
╭──────────────────────────────────────────────────────────────╮
│ ☐ Approach                                                   │
│                                                              │
│ Which approach should we take for the cache?                 │
│                                                              │
│ ❯ 1. Keep it in memory                                       │
│      Fastest, lost on restart.                               │
│   2. Write it to disk                                        │
│      Survives restarts.                                      │
│   3. Type something.                                         │
│                                                              │
╰──────────────────────────────────────────────────────────────╯
 Enter to select · Tab/Arrow keys to navigate · Esc to cancel
""".trimIndent()

    @Test fun replyWithStepsIsNotAQuestionCard() {
        assertNull(detectPrompt(prose, blocked = false))
        // Even when herdr says the agent waits, no fake choices: the plain "waiting" card.
        val p = detectPrompt(prose, blocked = true)!!
        assertTrue(p.choices.isEmpty())
    }

    @Test fun claudeQuestionDialogKeepsItsChoices() {
        val p = detectPrompt(ask, blocked = false)!!
        assertEquals(listOf("Keep it in memory", "Write it to disk", "Type something."), p.choices.map { it.label })
        assertEquals("1", p.selected)
        assertEquals("Which approach should we take for the cache?", p.question)
        assertEquals(listOf("down", "enter"), menuKeys(p, p.choices[1]))
    }
}
