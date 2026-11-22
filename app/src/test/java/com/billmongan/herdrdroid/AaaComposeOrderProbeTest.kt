package com.billmongan.herdrdroid

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A guard on test order. Gradle runs the classes in one JVM in name order, so this Compose test runs
 * first and starts Compose's global snapshot manager before the view-model-only classes run. If one
 * of those leaves the main thread's Compose dispatcher stuck, every later Compose test hangs with
 * AppNotIdleException ("Compose did not get idle"). IdleMainLooperRule explains the mechanism and the
 * fix. With this class first, such a test makes the next Compose class fail at once, whatever its name.
 */
@RunWith(AndroidJUnit4::class)
class AaaComposeOrderProbeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aTrivialComposableRenders() {
        rule.setContent { Text("order probe") }
        rule.onNodeWithText("order probe").assertIsDisplayed()
    }
}
