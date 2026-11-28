package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KiroStartup2211Test {
    private val screen = checkNotNull(javaClass.getResource(
        "/screens/kiro/derived-2.2.11/v3-migration.txt",
    )).readText().trimEnd()

    @Test fun migrationDialogCanBeAnsweredFromVerifiedFocus() {
        val dialog = checkNotNull(detectPrompt(screen, false, "kiro"))
        assertEquals("Kiro CLI 3.0 is here", dialog.question)
        assertEquals(listOf("Continue with V3", "Stay on V2, remind me later", "Stay on V2, don't ask again"),
            dialog.choices.map { it.label })
        assertEquals(listOf("enter"), menuKeys(dialog, dialog.choices[0]))
        assertEquals(listOf("down", "enter"), menuKeys(dialog, dialog.choices[1]))
        assertEquals(listOf("down", "down", "enter"), menuKeys(dialog, dialog.choices[2]))

        val moved = screen.replace("❯ Continue", "  Continue").replace("  Stay on V2, remind", "❯ Stay on V2, remind")
        val fresh = checkNotNull(detectPrompt(moved, false, "kiro-cli"))
        assertTrue(cardAnswerStillFits(dialog, fresh))
        assertEquals(listOf("up", "enter"), menuKeys(fresh, fresh.choices[0]))
    }

    @Test fun migrationDialogWithoutFocusOffersTerminalReview() {
        val dialog = checkNotNull(detectPrompt(screen.replace("❯ Continue", "  Continue"), false, "kiro"))
        assertTrue(dialog.terminalReview)
        assertTrue(dialog.choices.isEmpty())
    }

    @Test fun returnedComposerSupersedesOldMigrationDialog() {
        assertNull(detectPrompt("$screen\n\n❯ Ask a question or describe a task ↵", false, "kiro"))
    }
}
