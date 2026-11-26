package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.QBlock
import com.billmongan.herdrdroid.herdr.QKind
import com.billmongan.herdrdroid.herdr.QuestionParts
import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.2.2 (Bill: "Claude is still showing like the greater than sign that it shows as the little
 * arrow for choices and questions"): a Claude numbered question as the phone draws it.
 */
@RunWith(AndroidJUnit4::class)
class ClaudeQuestionCard222UiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val marks = listOf("❯", "›", "│", "┃", "▶", "⟩")

    private fun show(p: Prompt, screen: String?, picked: MutableList<Choice>) {
        rule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PromptQuestion(p, agent = "Claude", screen = screen)
                PromptCard(p, agent = "Claude", onChoice = { picked += it }, onKey = {}, onTypeAnswer = {},
                    optionNotes = CardFormat.optionNotes(screen, p.choices))
            }
        }
    }

    @Test fun aCapturedClaudeQuestionShowsNoMarkerOrGutterAndTheFocusedChoiceIsTheBlueOne() {
        val s = requireNotNull(javaClass.getResource("/screens/claude/captured-2.1.329/single-question.txt")).readText()
        val p = requireNotNull(detectPrompt(s, true, "claude"))
        val picked = mutableListOf<Choice>()
        show(p, s, picked)
        rule.onNodeWithTag("card-headline").assertTextEquals("The sender is on a stale hold for one of the helpers, which is also " +
            "waiting on a permission prompt for a local test. Its lane is a test I already finished in a throwaway container. " +
            "How should I redirect it?")
        for (m in marks) rule.onAllNodesWithText(m, substring = true).assertCountEquals(0)
        // The row the terminal marked with its arrow is the card's own highlighted button.
        rule.onNodeWithTag("choice-label-focused", useUnmergedTree = true).assertTextEquals("Cancel and stop the lane (Recommended)")
        rule.onAllNodesWithTag("choice-label-focused", useUnmergedTree = true).assertCountEquals(1)
        // Each description sits under its label, as one paragraph.
        rule.onNodeWithText("Do nothing for now.").assertExists()
        rule.onAllNodesWithTag("choice-note", useUnmergedTree = true).assertCountEquals(3)
        rule.onNodeWithText("Leave it").performScrollTo().performClick()
        assertEquals(listOf(p.choices[2]), picked)
    }

    @Test fun markersLeftInTheTextAreNotShownAndTheAnswerKeepsItsLabelAndKeys() {
        val raw = listOf(Choice("❯ 1. Keep a > b as it is", "1", false), Choice("› 2. Use `make test` · Enter to select", "2", false))
        val parts = QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, "│ Which  way should I go?"),
            QBlock(QKind.TEXT, "│ The work- flow runs `make test` first.")))
        val p = Prompt("Which way should I go?", raw, selected = "1", parts = parts)
        val picked = mutableListOf<Choice>()
        show(p, null, picked)
        rule.onNodeWithTag("card-headline").assertTextEquals("Which way should I go?")
        rule.onNodeWithText("The work-flow runs make test first.").assertExists()
        rule.onNodeWithTag("choice-label-focused", useUnmergedTree = true).assertTextEquals("Keep a > b as it is")
        rule.onNodeWithText("Use make test").assertExists()
        for (m in marks) rule.onAllNodesWithText(m, substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Enter to select", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Use make test").performScrollTo().performClick()
        assertEquals(listOf(raw[1]), picked)
    }

    @Test fun aNativeCheckboxCardShowsTheCheckedRowsAndSendsTheOriginalChoice() {
        val screen = requireNotNull(javaClass.getResource(
            "/screens/claude/captured-2.1.332-four-questions/q4-toggled.txt"
        )).readText()
        val p = requireNotNull(detectPrompt(screen, true, "claude"))
        val picked = mutableListOf<Choice>()
        show(p, screen, picked)

        rule.onAllNodesWithTag("claude-choice-checkbox", useUnmergedTree = true).assertCountEquals(4)
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Checked"))
            .assertCountEquals(1)
        rule.onNodeWithText("Option 4.2").performScrollTo().performClick()
        assertEquals(listOf(p.choices.single { it.label == "[✔] Option 4.2" }), picked)
    }

    @Test fun aCommandInTheQuestionTextShowsAsItsOwnMonospaceBlock() {
        val parts = QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, "Should I run the cleanup now?"),
            QBlock(QKind.TEXT, "Run these first:\n\$ cd /work/repo\n\$ make test | tee out.log\nThen I go on.")))
        rule.setContent { CardBody(parts, null) }
        rule.onNodeWithTag("card-code").assertExists()
        rule.onNodeWithText("\$ cd /work/repo\n\$ make test | tee out.log").assertExists()
        rule.onNodeWithText("Run these first:").assertExists()
        rule.onNodeWithText("Then I go on.").assertExists()
        rule.onAllNodesWithText("Run these first: \$ cd", substring = true).assertCountEquals(0)
    }
}
