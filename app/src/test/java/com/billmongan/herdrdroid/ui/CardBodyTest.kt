package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.QBlock
import com.billmongan.herdrdroid.herdr.QKind
import com.billmongan.herdrdroid.herdr.QuestionParts
import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 2.1.1: the card body on screen: code (2.2.8: never folded), Show raw, option descriptions. */
@RunWith(AndroidJUnit4::class)
class CardBodyTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    /** 2.2.8: a long script shows every line (no fold), and Show raw gives the parser's text. */
    @Test fun longCodeShowsEveryLineAndShowRawGivesTheParserText() {
        val script = (1..12).joinToString("\n") { "echo line$it" }
        val parts = QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, "Run this?"), QBlock(QKind.COMMAND, script)))
        rule.setContent { CardBody(parts, null) }
        rule.onNodeWithTag("card-headline").assertIsDisplayed()
        rule.onNodeWithText("echo line12", substring = true).assertExists()
        rule.onAllNodesWithText("Show all 12 lines").assertCountEquals(0)
        rule.onNodeWithText("Show raw").performClick()
        rule.onAllNodesWithTag("card-raw", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText(script).assertExists()
        rule.onNodeWithText("Show formatted").performClick()
        rule.onAllNodesWithTag("card-raw", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun anEditShowsAsADiff() {
        val s = requireNotNull(javaClass.getResource("/screens/grok/derived-1.4.9/0302-file-edit-approval.txt")).readText()
        val p = requireNotNull(detectPrompt(s, true, "grok"))
        rule.setContent { CardBody(p.shownParts(), s) }
        rule.onNodeWithTag("card-diff").assertIsDisplayed()
        rule.onNodeWithText("+    return f\"Hello, {name}!\"", substring = true).assertIsDisplayed()
    }

    @Test fun optionDescriptionsShowUnderTheirLabels() {
        val s = requireNotNull(javaClass.getResource("/screens/claude/captured-2026-11-13/multi-question.txt")).readText()
        val p = requireNotNull(detectPrompt(s, true, "claude"))
        val picked = mutableListOf<String>()
        rule.setContent {
            PromptCard(p, agent = "Claude", onChoice = { picked += it.keys }, onKey = {}, onTypeAnswer = {},
                optionNotes = CardFormat.optionNotes(s, p.choices))
        }
        rule.onNodeWithText("Mark 537 resolved now.").assertIsDisplayed()
        rule.onNodeWithText("Leave it for a later pass.").assertIsDisplayed()
        rule.onNodeWithText("Keep it open").performClick()
        assertEquals(listOf("2"), picked)
    }
}
