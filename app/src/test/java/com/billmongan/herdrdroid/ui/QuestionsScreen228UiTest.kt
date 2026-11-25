package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.questions.EntryState
import com.billmongan.herdrdroid.questions.QuestionEntry
import com.billmongan.herdrdroid.questions.QuestionSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.2.8: the drawer's Questions item and badge, and the Questions view's entries. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1400dp")
class QuestionsScreen228UiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val now = 10_000_000L
    private fun pane(id: String, agent: String = "claude") =
        Pane(id, "tab-$id", "w1", agent, Status.NEEDS_YOU, "", "/work", tabLabel = id, terminalId = "t-$id")
    private val approval = Prompt("Run npm test?", listOf(Choice("Yes", "1", false), Choice("Yes, for this session", "2", false), Choice("No", "3", false)), selected = "1")
    private fun entry(server: String, id: String, current: Boolean, state: EntryState = EntryState.READY, target: Any? = Any(), since: Long = now - 240_000) =
        QuestionEntry(QuestionSource(server, server.replaceFirstChar { it.uppercase() }, current, pane(id)), state, since,
            prompt = approval.takeIf { state == EntryState.READY }, target = target.takeIf { state == EntryState.READY })

    @Test fun theDrawerItemShowsNoBadgeAtZeroAndTheCountOtherwise() {
        var count by mutableStateOf(0)
        var taps = 0
        rule.setContent { QuestionsDrawerItem(count, selected = false) { taps++ } }
        rule.onNodeWithText("Questions").assertIsDisplayed()
        rule.onNodeWithTag("drawer-questions-badge").assertDoesNotExist()
        rule.onNodeWithContentDescription("Questions, none open").assertIsDisplayed()
        count = 3
        rule.waitForIdle()
        rule.onNodeWithTag("drawer-questions-badge").assertIsDisplayed()
        rule.onNodeWithText("3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Questions, 3 open").performClick()
        rule.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun noEntriesSaysNoOpenQuestions() {
        rule.setContent { list(emptyList()) }
        rule.onNodeWithText("No open questions").assertIsDisplayed()
    }

    @Test fun anEntryShowsItsAgentServerWaitAndTheCardsOwnAnswers() {
        val e = entry("laptop", "claude-1", current = true)
        rule.setContent { list(listOf(e)) }
        rule.onNodeWithText("Laptop · waiting 4m").assertIsDisplayed()
        rule.onNodeWithText("Run npm test?").assertIsDisplayed()
        rule.onNodeWithText("Yes, for this session", substring = true).assertIsDisplayed()
        rule.onNodeWithText("No", substring = false).assertIsDisplayed()
        rule.onNodeWithText("No").performClick()
        rule.onNodeWithText("Esc").performClick()
        rule.onNodeWithText("Type a different answer").performClick()
        rule.onNodeWithContentDescription("claude-1 on Laptop", substring = true).performClick()
        rule.runOnIdle {
            assertEquals(listOf("choice:3", "key:esc", "type:null", "open:claude-1"), events)
        }
    }

    @Test fun anEntryOnAnotherServerSaysWhyAndOffersOpenPane() {
        val e = entry("desk", "codex-2", current = false, target = null)
        rule.setContent { list(listOf(e)) }
        rule.onNodeWithTag("question-why").assertIsDisplayed()
        rule.onNodeWithText("On Desk, which isn't the server open now", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Yes, for this session", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Open pane").performClick()
        rule.runOnIdle { assertEquals(listOf("open:codex-2"), events) }
    }

    @Test fun anAnsweredEntrySaysSoWithNoButtons() {
        rule.setContent { list(listOf(entry("laptop", "claude-1", current = true, state = EntryState.ANSWERED))) }
        rule.onNodeWithTag("question-answered").assertIsDisplayed()
        rule.onNodeWithText("Yes, for this session", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Open pane").assertDoesNotExist()
    }

    @Test fun entriesAreGroupedByServer() {
        rule.setContent { list(listOf(entry("laptop", "a", true), entry("desk", "b", false, target = null))) }
        rule.onNodeWithText("LAPTOP").assertIsDisplayed()
        rule.onNodeWithText("DESK").assertExists()
    }

    private val events = mutableListOf<String>()

    @androidx.compose.runtime.Composable
    private fun list(entries: List<QuestionEntry>) = Column {
        QuestionsList(
            entries, now, sending = { false },
            onOpen = { events += "open:${it.source.pane.id}" },
            onPress = { _, _ -> },
            onChoice = { _, _, c -> events += "choice:${c.keys}" },
            onKey = { _, _, k -> events += "key:$k" },
            onType = { _, _, c -> events += "type:${c?.keys}" },
        )
    }
}
