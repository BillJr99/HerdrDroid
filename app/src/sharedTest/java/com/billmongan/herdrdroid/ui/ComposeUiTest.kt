package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.QuotaItem
import com.billmongan.herdrdroid.herdr.QuotaReading
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.UserItem
import com.billmongan.herdrdroid.transcript.rowsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests. They run on the JVM under Robolectric with the unit tests, and the same file
 * runs on an emulator in CI (src/sharedTest is in both source sets).
 */
@RunWith(AndroidJUnit4::class)
class ComposeUiTest {
    @get:Rule val rule = createComposeRule()

    private fun pane(status: Status, agent: String = "claude") =
        Pane("w1:p1", "w1:t1", "w1", agent, status, "", "/home/user/agents/claude")

    @Test fun statusPillsShowEachState() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                Status.entries.forEach { StatusPill(it) }
            }
        }
        listOf("Needs you", "Working", "Done", "Idle", "Shell").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun quotaChipsShowEveryLimitWithItsPercentAndReset() {
        val r = QuotaReading(0, listOf(
            QuotaItem("Current session", 12.0, "3pm"),
            QuotaItem("Current week (all models)", 24.0, "Nov 19, 7:59am"),
        ))
        rule.setContent { QuotaChips(r) }
        rule.onNodeWithText("24%").assertIsDisplayed()
        rule.onNodeWithText("12%").assertIsDisplayed()
        rule.onNodeWithText("Nov 19, 7:59am").assertIsDisplayed()
    }

    @Test fun paneCardShowsTitleModelAndUsageAndOpens() {
        var opened = 0
        val q = QuotaReading(0, listOf(QuotaItem("Current week (all models)", 24.0, null)))
        rule.setContent {
            PaneCard(pane(Status.WORKING), preview = "", model = "Opus 5.5 · high", quota = q, checking = false) { opened++ }
        }
        rule.onNodeWithText("Working").assertIsDisplayed()
        rule.onNodeWithText("Opus 5.5 · high").assertIsDisplayed()
        rule.onNodeWithText("24%").assertIsDisplayed()
        rule.onNodeWithText("Opus 5.5 · high").performClick()
        assertEquals(1, opened)
    }

    @Test fun promptCardShowsTheWholeQuestionAndSendsTheChoiceTapped() {
        val long = "Which of these should the deploy use? " + "It decides where the build runs and what it costs. ".repeat(6)
        val p = Prompt(long.trim(), listOf(Choice("Staging", "1", false), Choice("Production", "2", false), Choice("Skip it", "3", false)), selected = "1")
        val picked = mutableListOf<String>()
        rule.setContent { PromptCard(p, agent = "Claude", onChoice = { picked += it.keys }, onKey = {}, onTypeAnswer = {}) }
        rule.onNodeWithText(long.trim()).assertExists()
        rule.onNodeWithText("Production").performClick()
        assertEquals(listOf("2"), picked)
    }

    @Test fun pullingDownRefreshes() {
        var refreshed = 0
        rule.setContent {
            PullRefresh({ refreshed++ }, Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) { items(30) { Text("Row $it", Modifier.height(40.dp)) } }
            }
        }
        rule.onRoot().performTouchInput { swipeDown(startY = top + 5f, endY = bottom - 5f, durationMillis = 600) }
        rule.waitForIdle()
        assertTrue("pull to refresh ran", refreshed >= 1)
    }

    @Test fun openingAChatLandsAtTheBottom() {
        val items = (1..60).flatMap { listOf(UserItem("u$it", "Question $it"), TextItem("t$it", "Answer $it")) }
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                TranscriptChat(
                    rows = rowsOf(items), working = false, workingLabel = "", agentName = "Claude",
                    pending = emptyList(), more = false, size = 12.5f, family = FontFamily.Default,
                    actions = ChatActions(onQuote = {}, onStop = null),
                    state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Answer 60").assertIsDisplayed()
        rule.onNodeWithText("Question 1").assertDoesNotExist()
    }

    @Test fun chatStaysAtTheBottomWhenMoreArrives() {
        val items = androidx.compose.runtime.mutableStateOf((1..30).flatMap { listOf(UserItem("u$it", "Question $it"), TextItem("t$it", "Answer $it")) })
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                TranscriptChat(
                    rows = rowsOf(items.value), working = false, workingLabel = "", agentName = "Claude",
                    pending = emptyList(), more = false, size = 12.5f, family = FontFamily.Default,
                    actions = ChatActions(onQuote = {}, onStop = null),
                    state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        // A fresh read adds a long stretch at once, as after the cached chat on opening.
        items.value = items.value + (31..70).flatMap { listOf(UserItem("u$it", "Question $it"), TextItem("t$it", "Answer $it\n" + "more text\n".repeat(4))) }
        rule.waitForIdle()
        rule.onNodeWithText("Answer 70", substring = true).assertIsDisplayed()
    }
}
