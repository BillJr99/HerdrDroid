package com.billmongan.herdrdroid

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Ends with Compose's main-thread dispatcher waiting for a callback that Robolectric then drops, the
 * way the 2.2.0 CI run was left in CrossServerHermesDestinationTest: a state write lands after the
 * last drain of the main looper. Here a rule outside IdleMainLooperRule makes that write on purpose.
 * Without IdleMainLooperRule's repair, every later Compose test in the JVM that does not open a
 * dialog fails after 60 seconds with AppNotIdleException. ComposeFrameTimeOrderTest runs it right
 * before a Compose test; Gradle also runs it on its own, and the next class's rule repairs it.
 */
@RunWith(AndroidJUnit4::class)
class LostComposeCallbackTest {
    @get:Rule(order = OUTERMOST_RULE - 1) val writeAfterTheDrain = object : ExternalResource() {
        override fun after() {
            mutableStateOf(0).value = 1
            check(!shadowOf(Looper.getMainLooper()).isIdle) { "Compose posted no callback for the write" }
        }
    }
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun aStateWriteAfterTheLastDrainLeavesComposesCallbackToBeDropped() {
        rule.setContent { Text("lost callback") }
        rule.onNodeWithText("lost callback").assertExists()
    }
}
