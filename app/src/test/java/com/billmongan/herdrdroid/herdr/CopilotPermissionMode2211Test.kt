package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotPermissionMode2211Test {
    private val screen = checkNotNull(javaClass.getResource(
        "/screens/copilot/derived-2.2.11/permission-mode.txt",
    )).readText().trimEnd()

    @Test fun nativePickerUsesFocusRatherThanCurrentMode() {
        val first = checkNotNull(detectPrompt(screen, false, "copilot"))
        assertEquals("Select Permission Mode", first.question)
        assertEquals(listOf("Manual Approval", "Assisted Permissions", "Allow All"), first.choices.map { it.label })
        assertEquals(listOf("down", "enter"), menuKeys(first, first.choices[1]))
        val moved = screen.replace("❯ 1.", "  1.").replace("  2.", "❯ 2.")
        val second = checkNotNull(detectPrompt(moved, false, "copilot"))
        assertTrue(cardAnswerStillFits(first, second))
        assertEquals(listOf("up", "enter"), menuKeys(second, second.choices[0]))
        val confirm = checkNotNull(approveAllAsk(second, second.choices[2]))
        assertEquals("Allow All", confirm.label)
        assertEquals(listOf("down", "enter"), confirm.keys)
        assertNull(confirm.reason)
    }

    @Test fun ambiguousFocusFallsBackToTerminalReview() {
        val review = checkNotNull(detectPrompt(screen.replace("❯ 1.", "  1."), false, "copilot"))
        assertTrue(review.terminalReview)
        assertTrue(review.choices.isEmpty())
    }

    @Test fun pickerAboveNewOutputIsNotLive() {
        assertNull(detectPrompt("$screen\n\n● Ready for another task.\n\n❯ ", false, "copilot"))
    }
}
