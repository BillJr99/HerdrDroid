package com.billmongan.herdrdroid

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.UNREAD_SCREEN
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.herdr.hermesClarify
import com.billmongan.herdrdroid.herdr.markScreenNeedsYou
import com.billmongan.herdrdroid.herdr.menuKeys
import com.billmongan.herdrdroid.herdr.openCodeNeedsYouReason
import com.billmongan.herdrdroid.herdr.openCodeQuestionEditing
import com.billmongan.herdrdroid.notify.Notify
import com.billmongan.herdrdroid.notify.NotifyAction
import com.billmongan.herdrdroid.notify.alertIdentity
import com.billmongan.herdrdroid.notify.alertOptionsSig
import com.billmongan.herdrdroid.notify.alertPlan
import com.billmongan.herdrdroid.notify.notificationAnswerStillFits
import com.billmongan.herdrdroid.notify.notificationOffersReply
import com.billmongan.herdrdroid.notify.openCodePermissionAlertKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.2 (Bill, 2026-11-21: "The alert should offer whatever options the question does, and chat
 * mode should render the questions or alerts as question cards. Opencode was showing a question
 * card that said it could not be read before."). The alert offers the card's own options, every
 * agent's, up to Android's three buttons; OpenCode 2.3.2's question dialog is a real card, promoted
 * to needs-you like its permission dialog.
 */
@RunWith(AndroidJUnit4::class)
class QuestionOptions212Test {
    private fun res(path: String) = checkNotNull(javaClass.getResource(path)).readText()
    private val question = res("/screens/opencode/live-2026-11-21/question-2.3.2.txt")
    private val permission = res("/screens/opencode/live-2026-11-21/permission-shell-2.3.2.txt")
    private fun card(text: String, agent: String, blocked: Boolean = true) = checkNotNull(detectPrompt(text, blocked, agent)) { "no card for $agent" }
    private fun titles(p: Prompt, reply: Boolean = notificationOffersReply(p)) = alertPlan(p, reply = reply).buttons.map { it.title }

    // ---- OpenCode 2.3.2's question dialog -----------------------------------------------------

    @Test fun theOpenCode232QuestionIsARealCard() {
        for (agent in listOf("opencode", "opencode-ursinus")) {
            val p = card(question, agent, blocked = false)
            assertFalse(p.terminalReview)
            assertFalse(p.openCodeForm)
            assertEquals("Which database should the demo use?", p.question)
            assertEquals(listOf("SQLite (Recommended)", "Postgres"), p.choices.map { it.label })
            assertEquals(listOf("1", "2"), p.choices.map { it.keys })
            assertEquals("3", p.openCodeCustom)
            assertTrue(p.numberKeys)
            assertNotNull(p.openCodeQuestionFrame)
            // Its digit selects and submits, as on screen; no arrows from a colour-only highlight.
            assertEquals(listOf(listOf("1"), listOf("2")), p.choices.map { menuKeys(p, it) })
            assertTrue(p.forCard().region != listOf(UNREAD_SCREEN))
        }
    }

    @Test fun aStatusRowUnderTheQuestionIsNotItsFooter() {
        val p = card(question.trimEnd() + "\n  ~/fixture                         1.2K (1%)  ctrl+p commands    ~/fixture\n", "opencode")
        assertEquals(listOf("SQLite (Recommended)", "Postgres"), p.choices.map { it.label })
    }

    @Test fun severalQuestionsOrAnswersAreACardAnsweredInTerminal() {
        val tabs = question.replace("  ┃  ↑↓ select  enter submit  esc dismiss", "  ┃  ⇆ tab  ↑↓ select  enter confirm  esc dismiss")
            .replace("  ┃  Questions\n  ┃\n", "  ┃  Questions\n  ┃\n  ┃  Database  Cache  Submit\n  ┃\n")
        val multi = tabs.replace("1. SQLite (Recommended)", "1. [ ] SQLite (Recommended)").replace("2. Postgres", "2. [✓] Postgres")
            .replace("enter confirm", "enter toggle")
        for (frame in listOf(tabs, multi)) {
            val p = card(frame, "opencode")
            assertTrue(p.terminalReview)
            assertTrue(p.openCodeForm)
            assertEquals("Which database should the demo use?", p.question)
            assertTrue(p.choices.isEmpty())
            // Promoted, with an alert that has no buttons.
            assertEquals("a question dialog is open", openCodeNeedsYouReason(frame))
            assertTrue(alertPlan(p).buttons.isEmpty())
        }
    }

    @Test fun anOwnAnswerRowWithTextInItIsNotGuessedAt() {
        // The form's last row shows typed text instead of "Type your own answer": a digit there
        // would open the editor, not pick an option.
        val typed = question.replace("3. Type your own answer", "3. MariaDB")
        assertTrue(card(typed, "opencode").terminalReview)
        // A ✓ marks an answer already picked.
        assertTrue(card(question.replace("2. Postgres", "2. Postgres ✓"), "opencode").terminalReview)
    }

    @Test fun theOpenAnswerRowIsRecognisedForTheSameQuestion() {
        val editing = question.replace("  ┃  ↑↓ select  enter submit  esc dismiss", "  ┃  enter submit  esc close")
        assertEquals("Which database should the demo use?", openCodeQuestionEditing(editing))
        assertEquals("Which database should the demo use?", openCodeQuestionEditing(editing.replace("3. Type your own answer", "3.")))
        assertNull(openCodeQuestionEditing(question))
        assertNull(openCodeQuestionEditing(editing.replace("3. Type your own answer", "3. half typed")))
    }

    @Test fun theQuestionPromotesToNeedsYouWhileHerdrSaysDoneOrIdle() {
        for (herdrSays in listOf(Status.DONE, Status.IDLE)) {
            val p = Pane("w1:p1", "w1:t1", "w1", "opencode", herdrSays, "", "/fixture", terminalId = "t1")
            val marked = markScreenNeedsYou(listOf(p), mapOf(p.id to question.lines().takeLast(80).joinToString("\n"))).single()
            assertEquals(Status.NEEDS_YOU, marked.status)
            assertEquals(herdrSays, marked.screenNeedsYouFrom)
            // The send route is herdr's, unchanged.
            assertEquals(herdrSays, marked.sendStatus)
        }
        assertEquals("a question dialog is open", openCodeNeedsYouReason(question))
    }

    // ---- the lane D fixture is a real card ----------------------------------------------------

    @Test fun theOpenCode232PermissionIsARealCardWithEveryButton() {
        val p = card(permission, "opencode", blocked = false)
        assertFalse(p.terminalReview)
        assertTrue(p.forCard().region != listOf(UNREAD_SCREEN))
        assertEquals(listOf("Allow once", "Always allow", "Reject"), p.choices.map { it.label })
        assertEquals(listOf("1", "2", "3"), p.choices.map { it.keys })
    }

    // ---- option sets per agent: alert ---------------------------------------------------------

    @Test fun openCodePermissionAlertOffersAllowOnceAlwaysAllowAndReject() {
        val p = card(permission, "opencode")
        // Reject is Esc in OpenCode, so it is the alert's Esc too: three buttons, all three options.
        assertEquals(listOf("Allow once", "Always allow", "Reject (Esc)"), titles(p))
        assertTrue(alertPlan(p).inApp.isEmpty())
        assertFalse(notificationOffersReply(p))
    }

    @Test fun alwaysAllowFromTheAlertMovesFromTheVisibleHighlight() {
        fun focused(focus: String) = listOf("Allow once", "Always allow", "Reject").fold(permission) { t, label ->
            t.replace("   $label", "   \u001B[${if (label == focus) 44 else 49}m$label\u001B[0m")
        }
        val shown = card(permission, "opencode")
        fun tap(c: Choice) = NotifyAction("s", "w1:p1", c.keys, false, shown.question, label = c.label, agent = "opencode",
            options = alertOptionsSig(shown.choices), identity = alertIdentity(shown))
        val always = shown.choices.single { it.label == "Always allow" }
        assertEquals(listOf("right", "enter"), openCodePermissionAlertKeys(card(focused("Allow once"), "opencode"), tap(always)))
        assertEquals(listOf("left", "enter"), openCodePermissionAlertKeys(card(focused("Reject"), "opencode"), tap(always)))
        // No highlight: nothing.
        assertNull(openCodePermissionAlertKeys(card(permission, "opencode"), tap(always)))
    }

    @Test fun aDifferentCommandWithTheSameHeadingFitsNoAlertButton() {
        val shown = card(permission, "opencode")
        val other = card(permission.replace("echo hi > ~/scratch/x", "echo bye > ~/scratch/y"), "opencode")
        assertEquals(shown.question, other.question)
        assertEquals(shown.choices, other.choices)
        for (c in shown.choices) {
            val tap = NotifyAction("s", "w1:p1", c.keys, false, shown.question, label = c.label, agent = "opencode",
                options = alertOptionsSig(shown.choices), identity = alertIdentity(shown))
            assertTrue(notificationAnswerStillFits(shown, tap))
            assertFalse(c.label, notificationAnswerStillFits(other, tap))
            assertNull(c.label, openCodePermissionAlertKeys(other, tap))
        }
        val esc = NotifyAction("s", "w1:p1", "esc", false, shown.question, agent = "opencode", isKey = true,
            options = alertOptionsSig(shown.choices), identity = alertIdentity(shown))
        assertFalse(notificationAnswerStillFits(other, esc))
    }

    @Test fun openCodeQuestionAlertShowsItsOptionsAndTheOwnAnswerRowGoesToTheCard() {
        val p = card(question, "opencode")
        assertTrue(notificationOffersReply(p))
        // Two options, the free-text row and Esc are four: Esc keeps its slot, the options fill
        // the rest in order, and the free-text row is left for the question card.
        val plan = alertPlan(p, reply = notificationOffersReply(p))
        assertEquals(listOf("SQLite (Recommended)", "Postgres", "Esc"), plan.buttons.map { it.title })
        assertEquals(listOf("Type your own answer"), plan.inApp)
        // With one option, all of it fits: the option, its free-text row (a Reply box) and Esc.
        val one = card(question.replace("  ┃  2. Postgres\n  ┃     A server database\n  ┃  3. Type", "  ┃  2. Type"), "opencode")
        assertEquals(listOf("SQLite (Recommended)", "Type answer", "Esc"), titles(one))
        assertTrue(alertPlan(one).buttons[1].reply)
    }

    @Test fun claudeAndCodexKeepEscOnTheirOwnEscOption() {
        val claude = card(res("/screens/claude/captured-2026-11-13/bash-permission.txt"), "claude")
        assertEquals(3, claude.choices.size)
        assertEquals(listOf("Yes", "Yes, and don't ask again", "No, and tell Claude what"), titles(claude))
        assertTrue(alertPlan(claude).buttons.last().choice!!.label.endsWith("(esc)"))
        val codex = card(res("/screens/codex/synthetic-1.4.2/network-approval.txt"), "codex")
        assertEquals(3, alertPlan(codex).buttons.size)
        assertTrue(alertPlan(codex).buttons.none { it.esc })
        assertTrue(alertPlan(codex).inApp.isEmpty())
    }

    @Test fun moreOptionsThanButtonsShowTheFirstInOrderAndLeaveTheRestToTheCard() {
        val codex = card(res("/screens/codex/derived-1.4.9/0203-mcp-app-tool-approval.txt"), "codex")
        assertEquals(listOf("Allow", "Allow for this session", "Allow and don't ask me again", "Cancel"), codex.choices.map { it.label })
        val plan = alertPlan(codex)
        assertEquals(listOf("Allow", "Allow for this session", "Esc"), plan.buttons.map { it.title })
        assertEquals(listOf("Allow and don't ask me again", "Cancel"), plan.inApp)
        // A made-up five-option menu: never more than three buttons, Esc always one of them.
        val five = Prompt("Pick one", (1..5).map { Choice("Option $it", "$it", false) }, selected = "1")
        assertEquals(listOf("Option 1", "Option 2", "Esc"), titles(five))
        assertEquals(listOf("Option 3", "Option 4", "Option 5"), alertPlan(five).inApp)
    }

    @Test fun museQuotaAndHermesClarifyAlertsOfferTheirCardsOptions() {
        val muse = card(res("/screens/muse/captured-2026-11-20/quota-fast.txt"), "muse", blocked = false)
        assertEquals(3, muse.choices.size)
        assertEquals(listOf(muse.choices[0].label.take(24), muse.choices[1].label.take(24), "Esc"), titles(muse))
        assertEquals(listOf(muse.choices[2].label), alertPlan(muse).inApp)
        assertFalse(notificationOffersReply(muse))
        val hermes = card(hermesClarify, "hermes", blocked = false)
        assertTrue(hermes.hermesClarify)
        // Its card has no Esc and no typed answer (Terminal does those), so neither has the alert.
        assertEquals(listOf("SQLite", "Postgres"), titles(hermes))
        assertTrue(notificationAnswerStillFits(hermes, NotifyAction("s", "p", "2", false, hermes.question, label = "Postgres",
            options = alertOptionsSig(hermes.choices), identity = alertIdentity(hermes))))
    }

    @Test fun questionsWithNoOptionsKeepEscAndReply() {
        assertEquals(listOf("Esc", "Reply"), titles(Prompt("What should the file be called?", emptyList()), reply = true))
        val sessions = Prompt("Claude's session is in the background", emptyList(), claudeBackground = true)
        assertEquals(listOf("Esc"), titles(sessions))
    }

    // ---- the posted notification --------------------------------------------------------------

    @Test fun thePostedAlertsCarryTheseButtonsAndTheCommand() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notify.channels(app)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val perm = card(permission, "opencode")
        Notify.needsYou(app, "s1", "Fixture", "w1:p1", "OpenCode", perm.question, perm.choices, "opencode",
            terminalReview = perm.terminalReview, reply = notificationOffersReply(perm), prompt = perm)
        val n = nm.allNotifications.single { it.actions.orEmpty().any { a -> a.title == "Allow once" } }
        assertEquals(listOf("Allow once", "Always allow", "Reject (Esc)"), n.actions.map { it.title.toString() })
        val body = n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(body, body.contains("echo hi > ~/scratch/x"))

        val q = card(question, "opencode")
        Notify.needsYou(app, "s1", "Fixture", "w1:p2", "OpenCode", q.question, q.choices, "opencode",
            terminalReview = q.terminalReview, reply = notificationOffersReply(q), prompt = q)
        val qn = nm.allNotifications.single { it.actions.orEmpty().any { a -> a.title == "Postgres" } }
        assertEquals(listOf("SQLite (Recommended)", "Postgres", "Esc"), qn.actions.map { it.title.toString() })
        val qBody = qn.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(qBody, qBody.contains("Type your own answer"))
    }
}
