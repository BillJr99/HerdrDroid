package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.detectPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 2.2.0 (Bill: on a Claude question with numbered choices "I could see the vertical pipe and the sort
 * of greater than sign that picks the currently chosen answer from the terminal so I'd like that to
 * render more seamlessly in chat"): the screen card shows Claude's rows without the "│" gutter and
 * the "❯" marker, with the focused row marked by the card. The keys it sends are unchanged.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1200dp")
class ClaudeGutterCard220Test {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    private fun screen(path: String) = requireNotNull(javaClass.getResource("/screens/claude/$path")).readText()
    private val fixture = "derived-2.2.0-gutter/next-focus-gutter.txt"

    @Test fun theScreenCardReadsTheGutterAndTheMarkerAsTheyAre() {
        val p = requireNotNull(detectPrompt(screen(fixture), true, "claude"))
        val region = requireNotNull(p.region)
        assertTrue(p.choices.isEmpty())
        // The card's identity keeps the rows as read: the display is what changes.
        assertTrue(region.any { it.trimStart().startsWith("│ The sample suite") })
        assertTrue(region.any { it.trim() == "❯ Next" })
    }

    @Test fun regionViewDropsGutterAndMarkerAndKeepsTheFocus() {
        val p = requireNotNull(detectPrompt(screen(fixture), true, "claude"))
        val view = CardFormat.regionView(p.region!!)
        assertTrue(view.rows.none { r -> r.any { it in "│┃❯›" } })
        assertEquals("Next", view.rows[view.focused!!].trim())
        assertNull(view.focusedKey)
        assertTrue(view.rows.any { it.startsWith("The sample suite can run") })
        assertTrue(view.rows.any { it.trim() == "1. [ ] pytest" })
    }

    @Test fun aFocusedNumberedRowNamesItsKey() {
        val view = CardFormat.regionView(listOf("│ Which one?", "", "❯ 1. Yes", "  2. No", "> 3. Later"))
        assertEquals(listOf("Which one?", "", "1. Yes", "  2. No", "3. Later"), view.rows)
        // The last marker wins, as on screen only one row is focused; a lone ">" counts only before a number.
        assertEquals("3", view.focusedKey)
        val quote = CardFormat.regionView(listOf("> a quoted line", "  1. Yes"))
        assertEquals("> a quoted line", quote.rows[0])
        assertNull(quote.focused)
        // The echoed prompt above a dialog is not the focus.
        val echo = CardFormat.regionView(listOf("❯ please ask", "Which?", "  1. Yes", "  2. No"))
        assertNull(echo.focused)
        assertEquals("❯ please ask", echo.rows[0])
    }

    @Test fun noClaudeScreenCardInTheLibraryShowsAGutterOrMarker() {
        val root = File(requireNotNull(javaClass.classLoader?.getResource("screens/claude")).toURI())
        var cards = 0
        for (f in root.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }) {
            val p = detectPrompt(f.readText(), true, "claude") ?: continue
            val region = p.region ?: continue
            if (p.choices.isNotEmpty()) continue
            cards++
            val view = CardFormat.regionView(region)
            assertFalse(f.path, view.rows.any { r -> r.trimStart().firstOrNull()?.let { it in "│┃" } == true })
        }
        assertTrue(cards >= 1)
    }

    @Test fun theCardOnScreenHasNoGutterOrMarkerAndSendsTheSameKeys() {
        val p = requireNotNull(detectPrompt(screen(fixture), true, "claude"))
        val sent = mutableListOf<String>()
        rule.setContent {
            Column { PromptCard(p, agent = "Claude", onChoice = { sent += it.keys }, onKey = { sent += it }, onTypeAnswer = {}) }
        }
        rule.onNodeWithText("The sample suite can run", substring = true).assertIsDisplayed()
        rule.onNodeWithText("│", substring = true).assertDoesNotExist()
        rule.onNodeWithText("❯", substring = true).assertDoesNotExist()
        rule.onNodeWithText("2").performClick()
        rule.onNodeWithText("Enter").performClick()
        rule.runOnIdle { assertEquals(listOf("2", "enter"), sent) }
    }
}
