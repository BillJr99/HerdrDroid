package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkIdentity228Test {
    private val pane = Pane(
        "pane", "tab", "workspace", "codex", Status.IDLE, "Agent", "/workspace",
        terminalId = "terminal", sessionId = "session",
    )

    @Test fun replacingTheTerminalOrMovingThePaneInvalidatesItsInventory() {
        assertFalse(pane.sameWorkIdentity(pane.copy(terminalId = "replacement")))
        assertFalse(pane.sameWorkIdentity(pane.copy(sessionId = "replacement")))
        assertFalse(pane.sameWorkIdentity(pane.copy(workspaceId = "different-workspace")))
        assertFalse(pane.sameWorkIdentity(pane.copy(tabId = "different-tab")))
    }

    @Test fun statusChangesPreserveInventoryButProbeLabelsDoNot() {
        assertTrue(pane.sameWorkIdentity(pane.copy(status = Status.WORKING)))
        assertFalse(pane.sameWorkIdentity(pane.copy(tabLabel = "probe-pill")))
    }
}
