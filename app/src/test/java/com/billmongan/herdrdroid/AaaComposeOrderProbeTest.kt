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
 * A smoke sentinel for the full suite's usual order. Gradle has run the classes in one JVM in name
 * order, so this Compose test runs first and starts Compose's global snapshot manager before the
 * view-model-only classes run. If one of those leaves Compose's main-thread dispatcher stuck, the next
 * Compose test fails ("Compose did not get idle"). Name order is not guaranteed, and a filtered run
 * may skip this class: ComposeMainLooperRegressionTest checks the hazard in a fixed order.
 * IdleMainLooperRule explains the mechanism and the fix.
 */
@RunWith(AndroidJUnit4::class)
class AaaComposeOrderProbeTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun aTrivialComposableRenders() {
        rule.setContent { Text("order probe") }
        rule.onNodeWithText("order probe").assertIsDisplayed()
    }
}
