package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.herdr.Reflow
import com.billmongan.herdrdroid.herdr.ScreenTimes
import com.billmongan.herdrdroid.herdr.Seg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2.0: what a long press copies from a box: the text it shows, no gutters, wrap marks or colour codes. */
class BoxCopy220Test {
    @Test fun aBoxLosesItsEdgesButKeepsItsColumns() {
        val rows = listOf("│ name  │ size │", "│ alpha │ 1    │", "│ beta  │ 2    │")
        assertEquals("name  │ size\nalpha │ 1\nbeta  │ 2", BoxCopy.clean(rows))
    }

    @Test fun wrapGuttersAndWrapMarksAreRemovedAndTheCommandIsJoined() {
        val rows = listOf("│ cp notes/first.txt notes/second.txt·", "┆ backup/", "│ ls backup")
        assertEquals("cp notes/first.txt notes/second.txt backup/\nls backup", BoxCopy.clean(rows))
    }

    @Test fun colourCodesAndPipeGuttersAreRemoved() {
        val rows = listOf("| \u001B[32mecho hello\u001B[0m", "|   echo world")
        assertEquals("echo hello\n  echo world", BoxCopy.clean(rows))
    }

    @Test fun anOrdinaryCommandIsLeftAsItIs() {
        assertEquals("grep -n main | sort", BoxCopy.clean(listOf("  grep -n main | sort")))
    }

    @Test fun theLabelIsTheFirstLineShortened() {
        assertEquals("first line", BoxCopy.label("\n  first line\nsecond"))
        val long = "x".repeat(60)
        val label = BoxCopy.label(long)
        assertEquals(40, label.length)
        assertTrue(label.endsWith("…"))
    }

    @Test fun markdownBoxesAreTheFencedBlocksExactly() {
        val text = "Run these:\n\n```sh\nmake build\nmake test\n```\n\nThen:\n\n```\necho done\n```\n"
        assertEquals(listOf("make build\nmake test", "echo done"), BoxCopy.markdownBoxes(text))
        assertTrue(BoxCopy.markdownBoxes("No boxes here.").isEmpty())
    }

    @Test fun theScreenViewCopiesTheWholeReplyAndEachOfItsBoxes() {
        val screen = "Here is the table you asked for.\n\n│ name  │ size │\n│ alpha │ 1    │\n\nThat is all.\n"
        val segs = Reflow.parse(screen)
        val copies = ScreenCopy(segs, ScreenTimes.messages(segs))
        val para = segs.indexOfFirst { it is Seg.Para }
        val box = segs.indexOfFirst { it is Seg.Code }
        assertTrue(para >= 0 && box > para)
        val whole = checkNotNull(copies.whole(para))
        assertTrue(whole, whole.startsWith("Here is the table you asked for."))
        assertTrue(whole, whole.contains("name  │ size\nalpha │ 1"))
        assertTrue(whole, !whole.contains("│ name"))
        assertEquals(listOf("name  │ size\nalpha │ 1"), copies.boxes(para))
        assertEquals(copies.boxes(para), copies.boxes(box))
        // A gap has nothing to copy.
        segs.indexOfFirst { it == Seg.Gap }.takeIf { it >= 0 }?.let { assertNull(copies.whole(it)) }
    }
}
