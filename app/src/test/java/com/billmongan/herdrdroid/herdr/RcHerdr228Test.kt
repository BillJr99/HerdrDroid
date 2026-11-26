package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RcHerdr228Test {
    @Test fun claudeScrollbackHintDoesNotMaskTheForegroundPermission() {
        val stale = "1 awaiting input\nYour session moved to the background (esc returns to it)"
        val permission = "$stale\n\nRun the sample?\n❯ 1. Yes\n  2. No\nEsc to cancel"
        assertNull(claudeBackgroundList(permission))
        assertEquals(0, claudeSessionsAwaiting(permission))
        assertFalse(detectPrompt(permission, true, "claude")?.claudeBackground == true)
        assertNull(claudeBackgroundList("$stale\n❯ "))
    }

    @Test fun titlelessOpenCodePermissionCannotFallThroughToGuessedFocus() {
        val screen = "┃  Allow once   Always allow   Reject\n┃  ⇆ select enter confirm"
        for (agent in listOf("opencode", "opencode-named")) {
            val card = checkNotNull(detectPrompt(screen, true, agent))
            assertTrue(card.terminalReview)
            assertTrue(card.choices.isEmpty())
        }
    }

    @Test fun standingPermissionTargetMayContainBlock() {
        assertTrue(grantsStandingPermission("Yes, allow all edits to block.kt during this session"))
        assertFalse(grantsStandingPermission("Block, do not allow these edits"))
    }

    @Test fun grokFolderTrustConfirmationKnowsItsNativeKey() {
        val screen = "Trust this folder?\ny  Yes, trust this folder\nn  No, quit\nEnter or y to trust · n or Esc to quit"
        val card = checkNotNull(detectLetterMenu(screen, true))
        val approval = approveAllAsk(card, card.choices.first())
        assertNotNull(approval)
        assertEquals(listOf("y"), approval?.keys)
    }
}
