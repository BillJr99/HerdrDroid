package com.billmongan.herdrdroid.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.clearForTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class SessionDrawer2211UiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test
    @Config(qualifiers = "w320dp-h320dp")
    fun boardAndQuestionsScrollWithSettingsOnASmallScreen() = checkDestinations()

    @Test
    @Config(qualifiers = "w640dp-h280dp-land")
    fun boardAndQuestionsRemainReachableInLandscape() = checkDestinations()

    private fun checkDestinations() {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            rule.setContent { SessionDrawer(vm) }
            val drawerList = rule.onNodeWithTag("session-drawer-list")
            drawerList.performScrollToKey("settings")
            rule.onNodeWithText("Settings").assertIsDisplayed()
            drawerList.performScrollToKey("board")
            rule.onNodeWithTag("drawer-board").assertIsDisplayed().performClick()
            rule.runOnIdle { assertEquals(Screen.Board, vm.screen) }
            drawerList.performScrollToKey("questions")
            rule.onNodeWithTag("drawer-questions").assertIsDisplayed().performClick()
            rule.runOnIdle { assertEquals(Screen.Questions, vm.screen) }
            drawerList.performScrollToIndex(0)
            rule.onNodeWithTag("drawer-board").assertIsNotDisplayed()
            rule.onNodeWithTag("drawer-questions").assertIsNotDisplayed()
        } finally {
            vm.clearForTest()
        }
    }
}
