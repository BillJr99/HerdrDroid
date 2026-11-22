package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.idleMainLooperRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 2.2.0: an agent's error is its own card: Acknowledge first, Retry and the rest only when given, no answer keys. */
@RunWith(AndroidJUnit4::class)
class AgentErrorCardUiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = idleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun theErrorCardShowsTheErrorAndItsActionsButNoAnswers() {
        var acked = 0
        var retried = 0
        var models = 0
        var terminal = 0
        rule.setContent {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AgentErrorCardView(
                    title = "llmproxy: no model available",
                    summary = "llmproxy has no available model for this tier right now",
                    message = "Error: No 'flagship' models are currently available.",
                    onAcknowledge = { acked++ }, onRetry = { retried++ },
                    onSwitchModel = { models++ }, onShowTerminal = { terminal++ },
                )
            }
        }
        rule.onNodeWithText("llmproxy: no model available").assertIsDisplayed()
        rule.onNodeWithContentDescription("Error").assertIsDisplayed()
        rule.onNodeWithText("Error: No 'flagship' models are currently available.").assertIsDisplayed()
        rule.onNodeWithText("Llmproxy has no available model for this tier right now.").assertIsDisplayed()
        // Not a question card: no "Your answer", no "waiting for you", no keys.
        rule.onAllNodesWithText("Your answer").assertCountEquals(0)
        rule.onAllNodesWithText("waiting for you", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Enter").assertCountEquals(0)
        rule.onNodeWithText("Acknowledge").performScrollTo().performClick()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        rule.onNodeWithText("Switch model").performScrollTo().performClick()
        rule.onNodeWithText("Show terminal").performScrollTo().performClick()
        assertEquals(listOf(1, 1, 1, 1), listOf(acked, retried, models, terminal))
    }

    @Test fun anErrorWithNoSafeActionOffersOnlyAcknowledgeAndTheTerminal() {
        rule.setContent {
            AgentErrorCardView("Claude error", "the agent crashed; the agent exited", null,
                onAcknowledge = {}, onRetry = null, onSwitchModel = null, onShowTerminal = {})
        }
        rule.onNodeWithText("Acknowledge").assertIsDisplayed()
        rule.onNodeWithText("Show terminal").assertIsDisplayed()
        rule.onAllNodesWithText("Retry").assertCountEquals(0)
        rule.onAllNodesWithText("Switch model").assertCountEquals(0)
    }
}
