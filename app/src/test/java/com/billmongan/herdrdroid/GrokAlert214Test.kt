package com.billmongan.herdrdroid

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.notify.Notify
import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.notify.alertIdentity
import com.billmongan.herdrdroid.notify.alertOptionsSig
import com.billmongan.herdrdroid.notify.alertText
import com.billmongan.herdrdroid.notify.notificationAnswerStillFits
import com.billmongan.herdrdroid.notify.notificationOffersReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.4: the "needs you" alert for Bill's Grok permission (screens/grok/live-2026-11-21b). Its text
 * is the title, then the command on a line of its own, in order, with no gutter or wrap mark. The
 * question text the alert's identity is made from changed with that read (2.1.3 kept the gutters and
 * marks in it), so an alert posted by 2.1.3 for the same prompt no longer fits it: its tap does
 * nothing, and the card it opens is read fresh.
 */
@RunWith(AndroidJUnit4::class)
class GrokAlert214Test {
    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText()
    private val light = res("/screens/grok/live-2026-11-21b/light-box.txt")
    private fun card(text: String) = checkNotNull(detectPrompt(text, true, "grok"))

    private val title = "Confirm the back arrow click line"
    private val command = "git -C /home/bill/Documents/repos/HerdrDroid show " +
        "507e0344560ef35cbd6f843ff81c4aa499376ca7:app/src/main/java/com/billmongan/herdrdroid/ui/ServersScreen.kt | " +
        """awk 'NR>=114 && NR<=120 {printf "%d:%s\n", NR, ${'$'}0}'"""

    /** The question 2.1.3 read from this screen: the first row, then the "┆" rows, marks and all. */
    private val question213 = "git -C /home/bill/Documents/repos/HerdrDroid show· " +
        "┆ 507e0344560ef35cbd6f843ff81c4aa499376ca7:app/src/main/java/com/billmongan/herdrdroid/ui/ServersScreen.kt |· " +
        """┆ awk 'NR>=114 && NR<=120 {printf "%d:%s\n", NR, ${'$'}0}'"""

    @Test fun theAlertSaysTheTitleThenTheCommandOnItsOwnLine() {
        assertEquals("$title\n$command", alertText(card(light)))
        // A command of several lines keeps them.
        val rows = light.replace("show·", "show").replace("ServersScreen.kt |·", "ServersScreen.kt |")
        assertEquals(listOf(title, "git -C /home/bill/Documents/repos/HerdrDroid show",
            "507e0344560ef35cbd6f843ff81c4aa499376ca7:app/src/main/java/com/billmongan/herdrdroid/ui/ServersScreen.kt |",
            """awk 'NR>=114 && NR<=120 {printf "%d:%s\n", NR, ${'$'}0}'"""), alertText(card(rows)).lines())
    }

    @Test fun thePostedAlertKeepsItsNewlines() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notify.channels(app)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val p = card(light)
        Notify.needsYou(app, "s1", "Fixture", "w2:p8", "Grok", p.question, p.choices, "grok",
            terminalReview = p.terminalReview, reply = notificationOffersReply(p), prompt = p)
        val n = nm.allNotifications.single()
        val body = n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(body, body.startsWith("$title\n$command"))
        // The question and its command, before the alert's own "More in HerdrDroid" line (whose
        // options it joins with " · ").
        assertFalse(body, Regex("""[┆┊╎╏┃│]|·(?:\s|$)""").containsMatchIn(body.substringBefore("\n\n")))
    }

    @Test fun anAlertMadeNowFitsAFreshReadOfTheSameScreen() {
        val shown = card(light)
        for (c in shown.choices) {
            val tap = NotifyAction("s", "w2:p8", c.keys, false, shown.question, label = c.label, agent = "grok",
                options = alertOptionsSig(shown.choices), identity = alertIdentity(shown))
            assertTrue(c.label, notificationAnswerStillFits(card(light), tap))
        }
    }

    @Test fun anAlertPostedBy213RefusesTheSamePrompt() {
        val now = card(light)
        val old = Prompt(question213, now.choices)
        assertTrue(alertIdentity(old) != alertIdentity(now))
        for (c in now.choices) {
            val tap = NotifyAction("s", "w2:p8", c.keys, false, question213, label = c.label, agent = "grok",
                options = alertOptionsSig(now.choices), identity = alertIdentity(old))
            assertFalse(c.label, notificationAnswerStillFits(now, tap))
        }
        val esc = NotifyAction("s", "w2:p8", "esc", false, question213, agent = "grok", isKey = true,
            options = alertOptionsSig(now.choices), identity = alertIdentity(old))
        assertFalse(notificationAnswerStillFits(now, esc))
    }
}
