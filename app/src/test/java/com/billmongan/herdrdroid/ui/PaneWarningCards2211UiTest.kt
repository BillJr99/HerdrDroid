package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w320dp-h1000dp")
class PaneWarningCards2211UiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun badgeOpensItsWarningAndDismissReducesTheCount() {
        val warning = "A diagnostic record could not be written."
        val details = mutableStateOf(emptyList<AppViewModel.PaneWarningDetail>())
        val count = mutableStateOf(1)
        val review = mutableStateOf(false)
        var copied = ""
        rule.setContent {
            Column {
                if (count.value > 0) PaneWarningBadge(count.value, onClick = {
                    details.value = listOf(AppViewModel.PaneWarningDetail(warning, canDiagnose = false))
                    review.value = true
                })
                if (review.value) PaneWarningCards(
                    AppViewModel.PaneWarningReview("pane", warnings = details.value),
                    onClose = {}, onTerminal = {}, onCopy = { copied = it },
                    onDismiss = { text ->
                        details.value = details.value.filterNot { it.text == text }
                        count.value = details.value.size
                    },
                    onDiagnose = {}, onRestart = {},
                )
            }
        }
        rule.onNodeWithText(warning).assertDoesNotExist()
        rule.onNodeWithContentDescription("1 warning").performClick()
        rule.onNodeWithText(warning).assertExists()
        rule.onNodeWithText("Show in terminal").assertExists()
        rule.onNodeWithText("Restart pane").assertDoesNotExist()
        rule.onNodeWithText("Run codex doctor").assertDoesNotExist()
        rule.onNodeWithText("Copy text").performClick()
        rule.runOnIdle { assertEquals(warning, copied) }
        rule.onNodeWithText("Dismiss").performClick()
        rule.onNodeWithContentDescription("1 warning").assertDoesNotExist()
        rule.onNodeWithText("No new warnings").assertExists()
    }

    @Test fun aKnownWarningRequiresASeparateRestartConfirmation() {
        val confirm = mutableStateOf(false)
        var restarts = 0
        rule.setContent {
            PaneWarningCards(
                AppViewModel.PaneWarningReview("pane", warnings = listOf(
                    AppViewModel.PaneWarningDetail("Couldn't save diagnostic logs to its local database.", canDiagnose = true),
                )),
                onClose = {}, onTerminal = {}, onCopy = {}, onDismiss = {},
                onDiagnose = {}, onRestart = { confirm.value = true },
            )
            if (confirm.value) {
                WarningRestartDialog(onConfirm = { restarts++; confirm.value = false }, onCancel = { confirm.value = false })
            }
        }
        rule.onNodeWithText("Run codex doctor").assertExists()
        rule.onNodeWithText("Restart pane").performClick()
        rule.runOnIdle { assertEquals(0, restarts) }
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(0, restarts) }
        rule.onNodeWithText("Restart pane").performClick()
        rule.onNodeWithText("Continue in terminal").performClick()
        rule.runOnIdle { assertEquals(1, restarts) }
    }
}
