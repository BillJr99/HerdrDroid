package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.QBlock
import com.billmongan.herdrdroid.herdr.QKind
import com.billmongan.herdrdroid.herdr.QuestionParts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

/** 2.2.0: long-press a chat message for Copy and Copy box; long-press a box to copy just it. */
@RunWith(AndroidJUnit4::class)
class LongPressCopy220UiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val screen = "Here is the table you asked for.\n\n│ name  │ size │\n│ alpha │ 1    │\n\nThat is all.\n"

    private fun clip(): String? {
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test fun longPressOnAScreenReplyOffersCopyAndCopyBox() {
        rule.setContent { ChatView(screen, null, 12.5f, ReadingFont.SANS) }
        rule.onNodeWithText("Here is the table you asked for.").performTouchInput { longClick() }
        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Select text").assertExists()
        rule.onNodeWithText("Copy box: name  │ size").performClick()
        assertEquals("name  │ size\nalpha │ 1", clip())
        assertEquals("Copied box", ShadowToast.getTextOfLatestToast())
    }

    @Test fun copyTakesTheWholeMessage() {
        rule.setContent { ChatView(screen, null, 12.5f, ReadingFont.SANS) }
        rule.onNodeWithText("Here is the table you asked for.").performTouchInput { longClick() }
        rule.onNodeWithText("Copy").performClick()
        val copied = checkNotNull(clip())
        assertEquals(true, copied.startsWith("Here is the table you asked for."))
        assertEquals(true, copied.contains("name  │ size"))
        assertEquals("Copied", ShadowToast.getTextOfLatestToast())
    }

    @Test fun longPressOnAScreenBoxCopiesJustTheBox() {
        rule.setContent { ChatView(screen, null, 12.5f, ReadingFont.SANS) }
        rule.onNodeWithText("│ name  │ size │", substring = true).performTouchInput { longClick() }
        assertEquals("name  │ size\nalpha │ 1", clip())
        rule.onNodeWithText("Select text").assertDoesNotExist()
    }

    @Test fun longPressOnAMarkdownCodeBlockCopiesJustThatBlock() {
        rule.setContent { Column { MarkdownText("Run this:\n\n```sh\nmake build\n```\n", 12.5f, ReadingFont.SANS.family) } }
        rule.onNodeWithText("make build", substring = true).performTouchInput { longClick() }
        assertEquals("make build", clip())
        assertEquals("Copied box", ShadowToast.getTextOfLatestToast())
    }

    @Test fun longPressOnACardsCommandCopiesTheWholeCommand() {
        val script = (1..12).joinToString("\n") { "echo line$it" }
        val parts = QuestionParts(emptyList(), listOf(QBlock(QKind.HEADING, "Run this?"), QBlock(QKind.COMMAND, script)))
        rule.setContent { CardBody(parts, null) }
        rule.onNodeWithText("echo line1", substring = true).performTouchInput { longClick() }
        // Every line, the folded ones too.
        assertEquals(script, clip())
        // The fold still works with a tap.
        rule.onNodeWithText("Show all 12 lines").performClick()
        rule.onNodeWithText("echo line12", substring = true).assertExists()
    }

    @Test fun tapsStillReachTheirTargetsAndALongPressOutsideThemOpensTheMenu() {
        var taps = 0
        var presses = 0
        rule.setContent {
            Column(Modifier.onLongPress("Copy options") { presses++ }) {
                BasicText("Message text")
                BasicText("Tap me", Modifier.clickable { taps++ })
            }
        }
        rule.onNodeWithText("Tap me").performClick()
        assertEquals(1, taps)
        assertEquals(0, presses)
        rule.onNodeWithText("Message text").performTouchInput { longClick() }
        assertEquals(1, presses)
    }
}
