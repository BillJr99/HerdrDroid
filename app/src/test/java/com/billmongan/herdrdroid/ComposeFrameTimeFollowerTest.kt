package com.billmongan.herdrdroid

import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * The Compose test that ComposeFrameTimeOrderTest runs right after a test that leaves Compose's
 * main-thread dispatcher in a bad state. A rule outside IdleMainLooperRule reads the frame time that
 * the test before left on Compose's kept Choreographer, so this fails when that test did not reset
 * it. The render then fails after 60 seconds if the dispatcher was left waiting and not repaired,
 * and checks that one idle does not move the clock. Gradle also runs it on its own.
 */
@RunWith(AndroidJUnit4::class)
class ComposeFrameTimeFollowerTest {
    private val kept = object : ExternalResource() {
        var millis: Long? = null
        override fun before() { millis = IdleMainLooperRule.keptComposeFrameTimeMillis() }
    }
    @get:Rule(order = OUTERMOST_RULE - 1) val keptFrameTime: ExternalResource = kept
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun aComposeTestFindsNoStaleFrameTimeAndItsClockStaysPut() {
        val left = kept.millis ?: 0
        assertTrue("the test before this one left Compose's frame time at $left ms of its clock", left < 1_000)
        val start = SystemClock.uptimeMillis()
        val shown = mutableStateOf(0)
        rule.setContent { Text("follower ${shown.value}") }
        rule.runOnIdle { shown.value = 1 }
        rule.onNodeWithText("follower 1").assertExists()
        val moved = SystemClock.uptimeMillis() - start
        assertTrue("one render moved the clock $moved ms", moved < 1_000)
    }

}
