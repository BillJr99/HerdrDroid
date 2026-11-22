package com.billmongan.herdrdroid

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.codexFreeformQuestion
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.ui.AgentScreen
import com.billmongan.herdrdroid.ui.PromptCard
import com.billmongan.herdrdroid.ui.PromptQuestion
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class SelectedCardLatencyTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @Test fun claudeQuestionAppearsBeforeTheNextSlowChatPoll() =
        verifyAppearance("claude", "/screens/claude/captured-2026-11-13/multi-question.txt")

    @Test fun codexApprovalAppearsBeforeTheNextSlowChatPoll() =
        verifyAppearance("codex", "/screens/codex/captured-2026-11-13/run-command.txt")

    @Test fun nativeCodexQuestionOffersTerminalWithoutSynthesizingInput() {
        val prompt = checkNotNull(detectPrompt(codexFreeformQuestion, false, "codex"))
        var opened = false
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                PromptQuestion(prompt, agent = "Codex")
                PromptCard(prompt, agent = "Codex", onChoice = { error("Sent a choice") },
                    onKey = { error("Synthesized input") }, onTypeAnswer = { error("Opened a draft") },
                    onShowTerminal = { opened = true })
            }
        }
        rule.onNodeWithText("Share details.").assertIsDisplayed()
        rule.onNodeWithText("Answer this Codex question in Terminal.").assertIsDisplayed()
        rule.onNodeWithText("Enter").assertDoesNotExist()
        rule.onNodeWithText("Show terminal").performClick()
        rule.runOnIdle { assertTrue(opened) }
    }

    @Test fun idleCadenceIsUnchangedAndOnlyTheForegroundSelectionIsRead() {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val target = Pane("w1:p1", "w1:t1", "w1", "claude", Status.IDLE, "", "/fixture")
        var reads = 0
        try {
            rule.runOnUiThread {
                state<List<Pane>>(vm, "panes").value = listOf(target)
                vm.openPane(target.id)
                state<TranscriptMode>(vm, "transcriptState").value = TranscriptMode.ON
            }
            rule.mainClock.autoAdvance = false
            rule.setContent { AgentScreen(vm, target.id, readScreen = { reads++ }) }
            rule.mainClock.advanceTimeBy(1_000)
            rule.runOnUiThread {
                assertEquals(1, reads)
                vm.onBackground()
                Snapshot.sendApplyNotifications()
            }
            rule.mainClock.advanceTimeBy(2_500)
            rule.runOnUiThread {
                assertEquals(1, reads)
                vm.onForeground()
                assertTrue(vm.inFront && !vm.locked && vm.link == Link.CONNECTED)
                Snapshot.sendApplyNotifications()
            }
            rule.mainClock.advanceTimeBy(100)
            rule.runOnUiThread {
                assertEquals(2, reads)
                vm.screen = Screen.Agent("another-pane")
                Snapshot.sendApplyNotifications()
            }
            rule.mainClock.advanceTimeBy(2_500)
            rule.runOnUiThread { assertEquals(2, reads) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun verifyAppearance(agent: String, fixture: String) {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val target = Pane("w1:p1", "w1:t1", "w1", agent, Status.WORKING, "", "/fixture")
        var remoteScreen = "Working"
        var reads = 0
        val promptScreen = checkNotNull(javaClass.getResource(fixture)).readText()
        assertTrue("fixture must parse without the blocked status", detectPrompt(promptScreen, false, agent)?.choices.orEmpty().isNotEmpty())
        val transcriptMode = state<TranscriptMode>(vm, "transcriptState")
        try {
            rule.runOnUiThread {
                state<List<Pane>>(vm, "panes").value = listOf(target)
                vm.openPane(target.id)
                transcriptMode.value = TranscriptMode.ON
            }
            rule.mainClock.autoAdvance = false
            rule.setContent {
                AgentScreen(vm, target.id, readScreen = { id ->
                    assertEquals(target.id, id)
                    reads++
                    state<String>(vm, "paneText").value = remoteScreen
                })
            }
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithText("Your answer").assertDoesNotExist()
            val begins = rule.mainClock.currentTime
            rule.runOnUiThread {
                assertEquals(1, reads)
                // A new card is emitted just after a read. The pane status stays Working.
                remoteScreen = promptScreen
            }
            fun cards() = rule.onAllNodesWithText("Your answer").fetchSemanticsNodes()
            while (rule.mainClock.currentTime - begins < 2_400 && cards().isEmpty()) {
                rule.mainClock.advanceTimeByFrame()
            }
            val latency = rule.mainClock.currentTime - begins
            assertEquals("compose has exactly one actionable card", 1, cards().size)
            rule.onNodeWithText("Your answer").assertIsDisplayed()
            println("$agent fixture screen-to-card latency: $latency ms (Compose clock)")
            assertTrue("$agent card appeared after $latency ms", latency <= 750)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(vm: AppViewModel, property: String): MutableState<T> =
        AppViewModel::class.java.getDeclaredField("$property\$delegate")
            .apply { isAccessible = true }.get(vm) as MutableState<T>
}
