package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.7: Muse Code's command approval card, from a live capture (Muse Code 1.4.20, 2026-11-15). */
class Release147Test {
    private val screen = javaClass.getResource("/screens/muse/captured-2026-11-15/command-approval.txt")!!.readText()

    @Test fun musesApprovalIsACardWithItsOwnKeys() {
        for (agent in listOf("muse", null)) {
            val p = detectPrompt(screen, true, agent)
            assertNotNull(p)
            assertEquals(listOf("Allow this stage once (y)", "Always allow in this workspace: sudo -n chmod ... (p)", "Abort the entire command (esc)"), p!!.choices.map { it.label })
            assertEquals("1", p.selected)
            assertEquals("Would you like to run the following command?", p.parts?.heading ?: p.shownParts().heading ?: p.question)
            // Each option's own key, as the card names them; Esc for the abort.
            assertEquals(listOf("y"), menuKeys(p, p.choices[0]))
            assertEquals(listOf("p"), menuKeys(p, p.choices[1]))
            assertEquals(listOf("esc"), menuKeys(p, p.choices[2]))
        }
    }

    @Test fun theStandingPermissionCarriesTheCaution() {
        val p = detectPrompt(screen, true, "muse")!!
        assertFalse(grantsStandingPermission(p.choices[0].label))
        assertTrue(grantsStandingPermission(p.choices[1].label))
        assertFalse(grantsStandingPermission(p.choices[2].label))
    }

    @Test fun itIsAQuestionEvenBeforeHerdrSaysBlocked() {
        assertTrue(showsQuestionDialog(screen))
        assertNotNull(detectPrompt(screen, false, "muse"))
        // The command is shown with the card.
        val p = detectPrompt(screen, true, "muse")!!
        assertTrue((p.question + " " + (p.parts?.blocks?.joinToString(" ") { it.text } ?: "")).contains("sudo -n chmod -R 777"))
    }
}
