package com.billmongan.herdrdroid.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.IdleMainLooperRule
import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.board.BoardCard
import com.billmongan.herdrdroid.board.BoardState
import com.billmongan.herdrdroid.board.BoardStatus
import com.billmongan.herdrdroid.board.ServerBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1100dp")
class BoardScreen2210UiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    // Robolectric cannot settle dialogs with text fields on wider viewports.
    @Test
    @Config(qualifiers = "w320dp-h1000dp")
    fun createsPhoneCardWithoutAServer() {
        var savedServer: String? = "not called"
        var savedTitle = ""
        rule.setContent {
            BoardScreen(
                state = BoardState(),
                servers = emptyList(),
                onMenu = {}, onBack = {},
                onRefresh = {}, onRetry = {},
                onSave = { target, card -> savedServer = target; savedTitle = card.title },
                onMovePhoneCard = { _, _ -> }, onAddNote = { _, _, _ -> }, onResolve = { _, _, _, _ -> },
            )
        }
        rule.onNodeWithText("New card").performClick()
        rule.onNodeWithTag("board-card-title").performTextInput("Sketch outline")
        rule.onNodeWithText("Save card").performClick()
        rule.runOnIdle {
            assertNull(savedServer)
            assertEquals("Sketch outline", savedTitle)
        }
    }

    @Test fun selectsServerAndShowsNoAnswerWithManualRetry() {
        val source = BoardSource("remote", "Shared")
        val waiting = BoardCard("a", "Await review", BoardStatus.WAITING)
        val done = BoardCard("b", "Ready to archive", BoardStatus.DONE)
        val events = mutableListOf<String>()
        rule.setContent {
            BoardScreen(
                state = BoardState(servers = mapOf(source.id to ServerBoard(
                    entries = mapOf(waiting.id to waiting, done.id to done), noAnswer = true,
                ))),
                servers = listOf(source),
                onMenu = {}, onBack = {},
                onRefresh = { events += "refresh:${it.joinToString()}" }, onRetry = { events += "retry:$it" },
                onSave = { _, _ -> }, onMovePhoneCard = { _, _ -> }, onAddNote = { _, _, _ -> },
                onResolve = { _, _, _, _ -> },
            )
        }
        rule.onNodeWithText("Shared").performClick()
        rule.onNodeWithText("No answer. Showing the cached board.").assertExists()
        rule.onNodeWithText("Retry").performClick()
        rule.onNodeWithContentDescription("Refresh board").performClick()
        rule.onNodeWithTag("board-filter-waiting").performClick()
        rule.onNodeWithText("Await review").assertExists()
        rule.onNodeWithText("Ready to archive").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("refresh:remote", "retry:remote", "refresh:remote"), events) }
    }

    @Test fun conflictShowsBothValuesAndSendsTheChosenResolution() {
        val source = BoardSource("remote", "Shared")
        val card = BoardCard("a", "Plan", next = "Check draft")
        val remote = card.copy(next = "Review changes")
        val board = ServerBoard(
            entries = mapOf(card.id to card),
            baseline = mapOf(card.id to remote),
            conflicted = mapOf(card.id to setOf("next")),
        )
        val state = mutableStateOf(BoardState(servers = mapOf(source.id to board)))
        var chosen: List<Any> = emptyList()
        rule.setContent {
            BoardScreen(
                state = state.value,
                servers = listOf(source),
                onMenu = {}, onBack = {}, onRefresh = {}, onRetry = {},
                onSave = { _, _ -> }, onMovePhoneCard = { _, _ -> }, onAddNote = { _, _, _ -> },
                onResolve = { server, id, field, keepPhone -> chosen = listOf(server, id, field, keepPhone) },
            )
        }
        rule.onNodeWithText("Shared").performClick()
        rule.onNodeWithText("Plan").performClick()
        rule.onNodeWithText("Phone: Check draft").assertExists()
        rule.onNodeWithText("Server: Review changes").assertExists()
        rule.onNodeWithText("Edit card").assertIsNotEnabled()
        rule.onNodeWithText("Keep server").performClick()
        rule.runOnIdle {
            assertEquals(listOf("remote", "a", "next", false), chosen)
            state.value = BoardState(servers = mapOf(source.id to board.copy(
                entries = mapOf(card.id to remote),
                conflicted = emptyMap(),
            )))
        }
        rule.onNodeWithText("Edit card").assertIsEnabled()
    }
}
