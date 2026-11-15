package com.billmongan.herdrdroid.notify

import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.2: a notification button answers only the question it was made for, and that one always. */
class NotificationAnswerTest {
    private val screen = """
  Would you like to make the following edits?
  Description: Apply proposed file edits
  Destination: /home/user/agents/codex/out/a.json
› 1. Yes, proceed (y)
  2. Yes, and don't ask again for these files (a)
  3. No, and tell Codex what to do differently (esc)
  Press enter to confirm or esc to cancel
""".trimIndent()

    private val shown = detectPrompt(screen, blocked = true)!!
    private fun button(n: Int) = shown.choices[n].let { NotifyAction("s", "w1:p3", it.keys, it.pressEnter, shown.question, label = it.label) }

    @Test fun theSameQuestionIsAnsweredByEachButton() {
        // Read again at the tap, unchanged: every button and Esc still fit.
        val now = detectPrompt(screen, blocked = true)
        assertTrue(notificationAnswerStillFits(now, button(0)))
        assertTrue(notificationAnswerStillFits(now, button(1)))
        assertTrue(notificationAnswerStillFits(now, NotifyAction("s", "w1:p3", "esc", false, shown.question, isKey = true)))
        // The same question redrawn with other spacing still fits.
        assertTrue(notificationAnswerStillFits(detectPrompt(screen.replace("  Would", "    Would"), blocked = true), button(0)))
    }

    @Test fun aDifferentQuestionOrOptionDoesNot() {
        val other = screen.replace("make the following edits", "run the following command")
        assertFalse(notificationAnswerStillFits(detectPrompt(other, blocked = true), button(0)))
        // Same question, but option 1 now says something else.
        assertFalse(notificationAnswerStillFits(detectPrompt(screen.replace("Yes, proceed (y)", "No (n)"), blocked = true), button(0)))
        // Nothing on screen any more.
        assertFalse(notificationAnswerStillFits(detectPrompt("› Ask Codex to do anything", blocked = false), button(0)))
    }
}
