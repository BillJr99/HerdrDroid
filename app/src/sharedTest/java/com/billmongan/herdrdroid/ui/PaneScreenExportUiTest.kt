package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.idleMainLooperRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.diag.PaneScreenExport
import com.billmongan.herdrdroid.diag.PaneScreenExports
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 2.2.7: Export pane screen shows the size and the warning, and shares only on Share. */
@RunWith(AndroidJUnit4::class)
class PaneScreenExportUiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = idleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val export = PaneScreenExport("herdrdroid-pane-p1-20261123-120000.txt", "x".repeat(2048))

    @Test fun nothingIsSharedUntilShareIsTapped() {
        val shared = mutableListOf<PaneScreenExport>()
        var cancelled = 0
        rule.setContent { PaneScreenExportDialog(export, null, onShare = { shared += it }, onCancel = { cancelled++ }) }
        rule.onNodeWithText("Size: 2.0 KB").assertIsDisplayed()
        rule.onNodeWithText(PaneScreenExports.WARNING).assertIsDisplayed()
        rule.waitForIdle()
        assertEquals("opening the dialog shares nothing", 0, shared.size)
        rule.onNodeWithText("Share").performClick()
        assertEquals(listOf(export), shared)
        assertEquals(0, cancelled)
    }

    @Test fun cancelSharesNothingAndShareWaitsForTheRead() {
        val shared = mutableListOf<PaneScreenExport>()
        var cancelled = 0
        rule.setContent { PaneScreenExportDialog(null, null, onShare = { shared += it }, onCancel = { cancelled++ }) }
        rule.onNodeWithText("Reading the screen…").assertIsDisplayed()
        rule.onNodeWithText("Share").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, shared.size)
    }
}
