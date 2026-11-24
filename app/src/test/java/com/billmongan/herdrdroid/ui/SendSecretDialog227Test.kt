package com.billmongan.herdrdroid.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.herdr.SecretTarget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.2.7 Send a secret's dialog on its own: the screen is secured while it is open, the box is masked. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class SendSecretDialog227Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()
    private val here = SecretTarget("s1", "p1", "Mini", "Claude")
    private val there = SecretTarget("s2", "p9", "Lab", "Hermes")

    // Known gap (2.2.7): Robolectric never goes idle with this dialog's text fields shown (lanes
    // hd227s12b-s17); kept for when that is fixed. The phone check covers it meanwhile.
    @org.junit.Ignore("Robolectric never idles with this dialog's text fields; checked on the phone")
    @Test fun theScreenIsSecureWhileOpenAndTheChosenTargetGetsTheBytes() {
        var act: Activity? = null
        var open by mutableStateOf(true)
        var sent: Triple<SecretTarget, String?, ByteArray>? = null
        rule.setContent {
            act = LocalContext.current as? Activity
            if (open) SendSecretDialog(listOf(here, there), here, { t, l, b -> sent = Triple(t, l, b.copyOf()) }, { _, _, _ -> }, { open = false })
        }
        fun secure() = act!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        rule.runOnIdle { assertTrue("secured while open", secure()) }
        rule.onNode(hasContentDescription("Secret text") and hasSetTextAction()).performTextInput("s3cr3t-value")
        rule.onAllNodes(SemanticsMatcher("shows the secret") { n ->
            n.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text?.contains("s3cr3t-value") == true
        }, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText("For: Claude on Mini", substring = true).performClick()
        rule.onNodeWithText("Hermes on Lab").performClick()
        rule.onNodeWithText("For: Hermes on Lab", substring = true).assertExists()
        rule.onNodeWithText("Save on server").performClick()
        rule.runOnIdle {
            assertEquals(there, sent!!.first)
            assertNull(sent!!.second)
            assertArrayEquals("s3cr3t-value".toByteArray(), sent!!.third)
            assertFalse(open)
            assertFalse("set back once closed", secure())
        }
    }
}
