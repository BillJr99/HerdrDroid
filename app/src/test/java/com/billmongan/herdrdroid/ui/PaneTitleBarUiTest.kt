package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2.1.4: the pane screen's three-line title (name, server, model). Robolectric-only with native
 * graphics: legacy graphics measures text one pixel per character with fixed font metrics, so
 * truncation and line heights can't be checked there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaneTitleBarUiTest {
    @get:Rule val rule = createComposeRule()

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.first()
    }

    @Test fun showsNameServerAndModelOnThreeLines() {
        var models = 0
        rule.setContent {
            MaterialTheme {
                PaneTitleLines("grok", "Laptop2", "Grok 4.7 (high)", Modifier.testTag("title"),
                    status = "Idle", statusInk = Palette.Green, onModel = { models++ })
            }
        }
        rule.onNodeWithText("grok").assertIsDisplayed()
        rule.onNodeWithText("Laptop2 · Idle").assertIsDisplayed()
        rule.onNodeWithText("Grok 4.7 (high)").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pane grok").assertIsDisplayed()
        rule.onNodeWithContentDescription("Server Laptop2, Idle").assertIsDisplayed()
        rule.onNodeWithContentDescription("Model Grok 4.7 (high), tap to change").performClick()
        rule.runOnIdle { assertEquals(1, models) }
        // Each line is smaller than the one above it.
        val name = rule.onNodeWithText("grok").layout().layoutInput.style.fontSize.value
        val server = rule.onNodeWithText("Laptop2 · Idle").layout().layoutInput.style.fontSize.value
        val model = rule.onNodeWithText("Grok 4.7 (high)").layout().layoutInput.style.fontSize.value
        assertTrue("$name > $server > $model", name > server && server > model)
    }

    @Test fun narrowWidthTruncatesEachLineToOneLine() {
        val longName = "opencode-college · a very long session name that cannot fit"
        val longServer = "laptop2.home.example.lan, the agent server in the closet"
        val longModel = "gemini-pro-orchestrator-neon · extra long model descriptor text"
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(140.dp)) {
                    PaneTitleLines(longName, longServer, longModel, Modifier.testTag("title"), status = "Working", statusInk = Palette.Blue)
                }
            }
        }
        for (t in listOf(longName, "$longServer · Working", longModel)) {
            val l = rule.onNodeWithText(t).layout()
            assertEquals(t, 1, l.lineCount)
            assertTrue("$t should be cut with an ellipsis", l.isLineEllipsized(0))
        }
        // Never taller than three short lines.
        rule.onNodeWithTag("title").getBoundsInRoot().let { b -> assertTrue("title height ${b.bottom - b.top}", b.bottom - b.top <= 60.dp) }
    }

    @Test fun unknownModelLeavesTheThirdLineOff() {
        rule.setContent {
            MaterialTheme {
                PaneTitleLines("claude", "Laptop2", null, Modifier.testTag("title"), status = "Done", statusInk = Palette.Green)
            }
        }
        rule.onNodeWithText("claude").assertIsDisplayed()
        rule.onNodeWithText("Laptop2 · Done").assertIsDisplayed()
        rule.onNodeWithContentDescription("Model", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("title").getBoundsInRoot().let { b -> assertTrue("title height ${b.bottom - b.top}", b.bottom - b.top <= 44.dp) }
    }

    @Test fun keyboardUpShowsTheNameOnly() {
        rule.setContent {
            MaterialTheme {
                PaneTitleLines("codex", "Laptop2", "GPT-6.2-Sol · default", compact = true, status = "Idle")
            }
        }
        rule.onNodeWithText("codex").assertIsDisplayed()
        rule.onNodeWithText("Laptop2 · Idle").assertDoesNotExist()
        rule.onNodeWithText("GPT-6.2-Sol · default").assertDoesNotExist()
    }
}
