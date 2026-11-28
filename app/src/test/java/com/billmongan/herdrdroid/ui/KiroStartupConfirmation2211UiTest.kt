package com.billmongan.herdrdroid.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.clearForTest
import com.billmongan.herdrdroid.herdr.approveAllAsk
import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class KiroStartupConfirmation2211UiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun startupReminderHasScopedConfirmationAndTheNativeKeySequence() {
        val screen = checkNotNull(javaClass.getResource("/screens/kiro/derived-2.2.11/v3-migration.txt")).readText()
        val prompt = checkNotNull(detectPrompt(screen, false, "kiro"))
        val choice = prompt.choices.last()
        val ask = checkNotNull(approveAllAsk(prompt, choice))
        assertEquals(listOf("down", "down", "enter"), ask.keys)
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val pending = AppViewModel.ApproveAllPending("pane", null, prompt, choice, null, ask, null)
            rule.setContent { ApproveAllDialog(vm, pending, "Kiro") }
            rule.onNodeWithText("Stay on V2 and stop Kiro's startup reminder?").assertExists()
            rule.onNodeWithText("Kiro will stay on V2 and stop showing this reminder at startup.").assertExists()
            rule.onNodeWithText("Approve everything from now on?").assertDoesNotExist()
            rule.onNodeWithText("Confirm").assertExists()
        } finally {
            vm.clearForTest()
        }
    }
}
