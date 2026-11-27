package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.notify.alertPlan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgyNativeClipped229Test {
    private fun frame(suffix: String): String = checkNotNull(
        javaClass.getResource("/screens/agy/captured-1.5.18-tall/clipped-$suffix.txt")
    ).readText()

    @Test fun bothNativeApprovalsRequireReviewOfTheirOwnClippedCommand() {
        // Recent and recent-unwrapped 2,000-line reads showed the same eleven command tokens.
        for (suffix in listOf("a", "b")) {
            val screen = frame(suffix)
            val approval = checkNotNull(detectPrompt(screen, blocked = true, agent = "agy"))
            assertTrue(approval.terminalReview)
            assertTrue(approval.choices.isEmpty())
            assertTrue(alertPlan(approval).buttons.isEmpty())
            val command = checkNotNull(approval.parts).blocks.first { it.kind == QKind.COMMAND }.text
            assertTrue(command.contains("fixture-$suffix-001"))
            assertTrue(command.endsWith("fixture-$suffix-011..."))
            assertFalse(command.contains("fixture-$suffix-420"))
        }
    }

    @Test fun nativeEditHintAloneDoesNotBlockACompleteCommand() {
        val screen = frame("a").replace("fixture-a-011...", "fixture-a-011")
        val approval = checkNotNull(detectPrompt(screen, blocked = true, agent = "agy"))
        assertFalse(approval.terminalReview)
        assertTrue(approval.choices.any { it.label == "Yes, run command" })
    }
}
