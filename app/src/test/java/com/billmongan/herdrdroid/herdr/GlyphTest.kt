package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphTest {
    @Test
    fun claudeModeGlyphsBecomePlainText() {
        assertEquals("  \u00BB auto mode on (shift+tab to cycle)", forPhone("  \u23F5\u23F5 auto mode on (shift+tab to cycle)"))
        assertEquals("  \u2016 plan mode on", forPhone("  \u23F8 plan mode on"))
        assertEquals("\u203A accept", forPhone("\u001B[1m\u23F5\u001B[0m accept"))
    }

    @Test
    fun footerStillFoundAfterMapping() {
        val screen = listOf("\u25CF Done.", "", "\u2500".repeat(60), "\u276F ", "\u2500".repeat(60),
            "  \u23F5\u23F5 auto mode on (shift+tab to cycle)").joinToString("\n")
        val footer = Reflow.parse(forPhone(screen)).last() as Seg.Footer
        assertEquals(listOf("\u00BB auto mode on (shift+tab to cycle)"), footer.lines)
    }
}
