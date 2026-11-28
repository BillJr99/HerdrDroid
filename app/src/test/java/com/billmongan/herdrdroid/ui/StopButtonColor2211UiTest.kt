package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.OUTERMOST_RULE
import com.billmongan.herdrdroid.IdleMainLooperRule
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2.2.11 (Bill: "have the stop button be some other color besides the same yellow as the question
 * cards, maybe a light red"): the chat Stop button draws with its own light red tokens, the
 * question cards keep their orange, and Stop's label meets WCAG AA in every palette.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StopButtonColor2211UiTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @get:Rule val rule = createComposeRule()

    @After fun resetPalette() {
        Palette.dark = false; Palette.variant = DarkPalette.STANDARD; Palette.lightVariant = LightPalette.STANDARD
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble(); val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Runs [check] in every light and dark palette, with [Palette] set the way [HerdrTheme] sets it. */
    private fun everyPalette(check: (String) -> Unit) {
        Palette.dark = false
        LightPalette.entries.forEach { Palette.lightVariant = it; check("light ${it.label}") }
        Palette.dark = true
        DarkPalette.entries.forEach { Palette.variant = it; check("dark ${it.label}") }
    }

    @Test fun stopChipShowsAndStops() {
        var stops = 0
        rule.setContent { HerdrTheme(dark = false) { StopChip(onStop = { stops++ }) } }
        rule.onNodeWithText("Stop").assertIsDisplayed().performClick()
        assertEquals(1, stops)
    }

    @Test fun stopUsesTheStopTokens() = everyPalette { where ->
        assertEquals(where, Palette.StopSoft, StopLook.container)
        assertEquals(where, Palette.StopEdge, StopLook.edge)
        assertEquals(where, Palette.StopInk, StopLook.ink)
        // Not the question cards' colors.
        assertNotEquals(where, Palette.OrangeSoft, StopLook.container)
        assertNotEquals(where, Palette.OrangeEdge, StopLook.edge)
        assertNotEquals(where, Palette.Orange, StopLook.edge)
        assertNotEquals(where, Palette.OrangeInk, StopLook.ink)
    }

    @Test fun stopValuesAreLightRed() {
        Palette.dark = false
        assertEquals(Color(0xFFFDE2E1), Palette.StopSoft)
        assertEquals(Color(0xFF8C1D18), Palette.StopInk)
        assertEquals(Color(0xFFF2B8B5), Palette.StopEdge)
        Palette.dark = true
        assertEquals(Color(0xFF4A1F1F), Palette.StopSoft)
        assertEquals(Color(0xFFFFB4AB), Palette.StopInk)
        assertEquals(Color(0xFF7A3434), Palette.StopEdge)
    }

    @Test fun stopIsRedAndReadableInEveryPalette() = everyPalette { where ->
        val soft = Palette.StopSoft
        assertTrue("$where: container should lean red, got $soft", soft.red > soft.green && soft.red > soft.blue)
        val ratio = contrast(StopLook.ink, StopLook.container)
        assertTrue("$where: Stop label contrast $ratio < 4.5", ratio >= 4.5)
    }

    /** The question cards' own colors are unchanged by 2.2.11. */
    @Test fun questionCardsKeepTheirOrange() {
        Palette.dark = false
        assertEquals(Color(0xFFE8833A), Palette.Orange)
        assertEquals(Color(0xFF9A3412), Palette.OrangeInk)
        assertEquals(Color(0xFFFFF3EA), Palette.OrangeSoft)
        assertEquals(Color(0xFFF2C9A8), Palette.OrangeEdge)
        Palette.dark = true
        assertEquals(Color(0xFFF0A060), Palette.Orange)
        assertEquals(Color(0xFFFFB27A), Palette.OrangeInk)
        assertEquals(Color(0xFF2E1F14), Palette.OrangeSoft)
        assertEquals(Color(0xFF5C3A20), Palette.OrangeEdge)
    }

    @Test fun questionBarStillShowsOnItsCard() {
        rule.setContent {
            HerdrTheme(dark = false) {
                CardOrTerminalBar(showingTerminal = true, agent = "claude", onBackToQuestion = {}, card = {})
            }
        }
        rule.onNodeWithText("claude is waiting for your answer").assertIsDisplayed()
        rule.onNodeWithText("Stop").assertDoesNotExist()
    }
}
