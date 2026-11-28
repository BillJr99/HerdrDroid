package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePermission2211Test {
    private val screen = checkNotNull(javaClass.getResource(
        "/screens/opencode/derived-2.2.11/permission-after-tool.txt"
    )).readText()
    private val liveTitle = screen.lastIndexOf("  ┃  △ Permission required")

    @Test fun sidebarAndRunningToolDoNotClipTheNewestFourRowCommand() {
        val prompt = checkNotNull(detectPrompt(screen, blocked = false, agent = "opencode"))
        assertFalse(prompt.terminalReview)
        assertEquals(listOf("Allow once", "Always allow", "Reject"), prompt.choices.map { it.label })
        assertNull(prompt.selected)
        val command = prompt.shownParts().command.orEmpty()
        assertTrue(command.contains("-c 12345"))
        assertTrue(command.contains("--apply --file"))
        assertTrue(command.endsWith("dates.json"))
        assertFalse(command.contains("earlier sample"))
        assertFalse(command.contains("running tool"))
        assertFalse(command.contains("Session details"))
    }

    @Test fun olderPermissionDoesNotSurviveTheSubsequentRunningTool() {
        assertNull(detectPrompt(screen.substring(0, liveTitle), blocked = false, agent = "opencode"))
    }

    @Test fun aRealClippedLiveCommandStillRequiresTerminalReview() {
        val clipped = screen.replace("┃  json", "┃  jso…")
        val prompt = checkNotNull(detectPrompt(clipped, blocked = false, agent = "opencode"))
        assertTrue(prompt.terminalReview)
        assertTrue(prompt.choices.isEmpty())
    }
}
