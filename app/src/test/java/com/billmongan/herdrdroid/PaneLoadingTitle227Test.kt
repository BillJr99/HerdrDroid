package com.billmongan.herdrdroid

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ui.AgentScreen
import com.billmongan.herdrdroid.ui.LOADING_TITLE
import com.billmongan.herdrdroid.ui.PANE_IDENTITY_WAIT_MS
import com.billmongan.herdrdroid.ui.PaneIdentity
import com.billmongan.herdrdroid.ui.UNKNOWN_AGENT
import com.billmongan.herdrdroid.ui.paneIdentity
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.2.7: a pane whose agent is not known yet opens with "Loading…", never "?" or its raw id. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1000dp")
class PaneLoadingTitle227Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val id = "w2:p5"
    private val claude = Pane(id, "w2:t5", "w2", "claude", Status.IDLE, "", "/fixture")

    @After fun close() { vm.viewModelScope.cancel() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(property: String): MutableState<T> =
        AppViewModel::class.java.getDeclaredField("$property\$delegate").apply { isAccessible = true }.get(vm) as MutableState<T>

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size
    private fun spinner() = rule.onAllNodesWithContentDescription("Loading the pane").fetchSemanticsNodes().size

    /** Nothing wrong on screen: no "?" tile, no raw pane id anywhere. */
    private fun assertNoWrongFrame() {
        assertEquals("a '?' tile", 0, count("?"))
        assertEquals("the raw pane id", 0, rule.onAllNodesWithText(id, substring = true).fetchSemanticsNodes().size)
    }

    private fun openUnknown() {
        rule.runOnUiThread {
            state<List<Pane>>("panes").value = emptyList()
            vm.openPane(id)
        }
        rule.mainClock.autoAdvance = false
        rule.setContent { AgentScreen(vm, id, readScreen = {}) }
    }

    @Test fun anUnknownPaneShowsLoadingAndNoQuestionMarkOrRawId() {
        openUnknown()
        rule.mainClock.advanceTimeBy(100)
        assertTrue("the loading title", count(LOADING_TITLE) >= 1)
        assertEquals("the small progress indicator", 1, spinner())
        assertNoWrongFrame()
        assertEquals("no guessed agent controls", 0, count("Show terminal"))
    }

    @Test fun onceKnownTheRealTitleRendersWithNoWrongFrameBetween() {
        openUnknown()
        for (step in 0 until 5) {
            rule.mainClock.advanceTimeBy(200)
            assertNoWrongFrame()
            assertEquals(0, count("Claude"))
        }
        rule.runOnUiThread {
            state<List<Pane>>("panes").value = listOf(claude)
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(16)
        assertTrue("the real title", count("Claude") >= 1)
        assertTrue("the real tile", count("C") >= 1)
        assertEquals("no loading title left", 0, count(LOADING_TITLE))
        assertEquals(0, spinner())
        assertNoWrongFrame()
        // The wait running out later changes nothing once the agent is known.
        rule.mainClock.advanceTimeBy(PANE_IDENTITY_WAIT_MS + 500)
        assertEquals(0, count(UNKNOWN_AGENT))
        assertTrue(count("Claude") >= 1)
    }

    @Test fun aPaneKnownAtOpenRendersRightOnTheFirstFrame() {
        rule.runOnUiThread {
            state<List<Pane>>("panes").value = listOf(claude)
            vm.openPane(id)
        }
        rule.mainClock.autoAdvance = false
        rule.setContent { AgentScreen(vm, id, readScreen = {}) }
        assertTrue(count("Claude") >= 1)
        assertEquals(0, count(LOADING_TITLE))
        assertEquals(0, spinner())
        assertNoWrongFrame()
    }

    @Test fun afterTheWaitItFallsBackToUnknownAgentWithShowTerminal() {
        openUnknown()
        rule.mainClock.advanceTimeBy(PANE_IDENTITY_WAIT_MS - 500)
        assertEquals(0, count(UNKNOWN_AGENT))
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText(UNKNOWN_AGENT).assertExists()
        rule.onNodeWithText("Show terminal").assertExists()
        assertEquals(0, spinner())
        // The title is no longer "Loading…"; with no screen read in this fixture, the content keeps
        // its 1.4.2 note for a blank screen ("Loading…"), so at most that one remains.
        assertTrue(count(LOADING_TITLE) <= 1)
        assertNoWrongFrame()
    }

    @Test fun theFallbackUsesTheLastKnownLabelButNeverARawId() {
        assertEquals(PaneIdentity.Kind.LOADING, paneIdentity(null, id, false, "notes").kind)
        assertEquals(LOADING_TITLE, paneIdentity(null, id, false, "notes").title)
        assertEquals("notes", paneIdentity(null, id, true, "notes").title)
        assertEquals("Claude · review", paneIdentity(null, id, true, "Claude · review").title)
        for (raw in listOf(id, "w1:t3", "W10:P2", "", "  ", null))
            assertEquals("$raw", UNKNOWN_AGENT, paneIdentity(null, id, true, raw).title)
        val known = paneIdentity(claude.copy(tabLabel = "review"), id, false, null)
        assertEquals(PaneIdentity.Kind.KNOWN, known.kind)
        assertEquals("Claude · review", known.title)
        assertEquals("C", known.initial)
        // Every state's title and tile: never "?" and never the raw id.
        for (p in listOf(null, claude)) for (w in listOf(false, true)) {
            val i = paneIdentity(p, id, w, id)
            assertNotEquals("?", i.initial)
            assertNotEquals(id, i.title)
        }
    }
}
