package com.billmongan.herdrdroid.update

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.idleMainLooperRule
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 2.1.4: the Updates part of Settings and the update dialog. */
@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = idleMainLooperRule()
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val release = ReleaseInfo(
        AppVersion(2, 1, 5), "v2.1.5", "HerdrDroid-2.1.5.apk",
        "https://github.com/BillJr99/HerdrDroid/releases/download/v2.1.5/HerdrDroid-2.1.5.apk", 57_000_000,
    )

    @Test fun settingsShowTheSwitchTheButtonTheVersionAndTheLastCheck() {
        var onLaunch by mutableStateOf(true)
        var checks = 0
        rule.setContent {
            Column {
                UpdateSettings("2.1.4", onLaunch, 0L, checking = false, note = "HerdrDroid 2.1.4 is up to date.", onCheckOnLaunch = { onLaunch = it }, onCheckNow = { checks++ })
            }
        }
        rule.onNodeWithText("Installed: HerdrDroid 2.1.4").assertIsDisplayed()
        rule.onNodeWithText("Last checked: Never").assertIsDisplayed()
        rule.onNodeWithText("HerdrDroid 2.1.4 is up to date.").assertIsDisplayed()
        rule.onNodeWithText("Check for updates on launch").assertIsOn()
        rule.onNodeWithText("Check for updates on launch").performClick()
        rule.onNodeWithText("Check for updates on launch").assertIsOff()
        rule.runOnIdle { assertEquals(false, onLaunch) }
        rule.onNodeWithText("Check for updates now").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun theButtonIsOffWhileACheckRuns() {
        rule.setContent {
            Column { UpdateSettings("2.1.4", true, 1_800_000_000_000L, checking = true, note = null, onCheckOnLaunch = {}, onCheckNow = {}) }
        }
        rule.onNodeWithText("Checking…").assertIsNotEnabled()
        rule.onNodeWithText(lastCheckLabel(1_800_000_000_000L), substring = true).assertIsDisplayed()
    }

    @Test fun theDialogOffersUpdateAndNotNow() {
        val taps = mutableListOf<String>()
        rule.setContent {
            UpdateDialog(
                UpdateDialogState.Available(release),
                onUpdate = { taps += "update" }, onNotNow = { taps += "notnow" },
                onCancel = { taps += "cancel" }, onOpenSettings = { taps += "settings" }, onClose = { taps += "close" },
            )
        }
        rule.onNodeWithText("HerdrDroid 2.1.5 is available").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("Update").performClick()
        rule.runOnIdle { assertEquals(listOf("notnow", "update"), taps) }
    }

    @Test fun theDownloadShowsProgressAndCancelAndAFailureShowsWhy() {
        var state by mutableStateOf<UpdateDialogState>(UpdateDialogState.Downloading(release, 28_500_000, 57_000_000))
        var cancels = 0
        rule.setContent {
            UpdateDialog(state, onUpdate = {}, onNotNow = {}, onCancel = { cancels++ }, onOpenSettings = {}, onClose = {})
        }
        rule.onNodeWithText("Downloading HerdrDroid 2.1.5").assertIsDisplayed()
        rule.onNodeWithText("of 54.4 MB", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, cancels) }
        rule.runOnUiThread { state = UpdateDialogState.NeedsPermission(release) }
        rule.onNodeWithText("Open settings").assertIsDisplayed()
        rule.runOnUiThread { state = UpdateDialogState.Failed("The downloaded APK isn't signed with this app's key. It wasn't installed.") }
        rule.onNodeWithText("Update not installed").assertIsDisplayed()
        rule.onNodeWithText("The downloaded APK isn't signed with this app's key. It wasn't installed.").assertIsDisplayed()
    }
}
