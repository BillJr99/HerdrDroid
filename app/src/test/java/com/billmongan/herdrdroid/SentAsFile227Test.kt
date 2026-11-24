package com.billmongan.herdrdroid

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.LongText
import com.billmongan.herdrdroid.herdr.SentFiles
import com.billmongan.herdrdroid.herdr.UploadReceipt
import com.billmongan.herdrdroid.ui.StayInApp
import com.billmongan.herdrdroid.ui.UserBubble
import com.billmongan.herdrdroid.ui.crossServerFileNote
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 2.2.7: a message that went as a file shows so in the chat; the wide main screen keeps Back in the app. */
@RunWith(AndroidJUnit4::class)
class SentAsFile227Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun aMessageSentAsAFileShowsALabelAndTheOriginalCollapsed() {
        val original = (1..40).joinToString("\n") { "row $it of the pasted log" }
        val receipt = UploadReceipt("/home/fixture/.herdrdroid/inbox/20261123-000000-pasted-text.txt", 57_037, "0".repeat(64))
        SentFiles.remember(receipt.path, original)
        val line = LongText.fileLine(receipt)
        assertEquals("Pasted text (55.7 KB) attached: ${receipt.path}", line)
        rule.setContent { UserBubble(line, 15f, onQuote = {}) }
        rule.onNodeWithText("sent as a file (55.7 KB)").assertExists()
        rule.onNodeWithText("row 1 of the pasted log", substring = true).assertExists()
        rule.onNodeWithText(line).assertDoesNotExist()
    }

    @Test fun anOrdinaryMessageHasNoLabel() {
        rule.setContent { UserBubble("Check the build", 15f, onQuote = {}) }
        rule.onNodeWithText("Check the build").assertExists()
        rule.onNodeWithText("sent as a file", substring = true).assertDoesNotExist()
    }

    @Test fun theApprovalSaysWhenAMessageGoesAsAFile() {
        assertNull(crossServerFileNote("Check the build"))
        val note = crossServerFileNote("x".repeat(55_000))
        assertTrue(note.orEmpty(), note.orEmpty().startsWith("53.7 KB, sent as a file"))
    }

    @Test fun backOnTheWideMainScreenNeverClosesTheApp() {
        rule.setContent { StayInApp() }
        rule.runOnIdle { assertTrue(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertFalse(rule.activity.isFinishing) }
    }
}
