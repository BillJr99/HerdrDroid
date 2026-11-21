package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.ThinkItem
import com.billmongan.herdrdroid.transcript.UserItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneOffset

/**
 * 2.1.4 (Bill: "Also for 2.1.4 grok isn't showing timestamps make sure all panes do in chat").
 * Grok 1.0.92 draws each message's time at the right end of its first row. The live fixture is the
 * grok pane's own screen (2026-11-20, sanitized); synthetic-2.1.4/clock-forms.txt has its 24-hour
 * and dated forms and a turn across midnight.
 */
class ChatClock214Test {
    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()
    private val live by lazy { resource("/screens/grok/live-2026-11-20/idle-after-review.txt") }
    private val forms by lazy { resource("/screens/grok/synthetic-2.1.4/clock-forms.txt") }
    private val agy by lazy { resource("/screens/agy/live-2026-11-20/idle-after-work.txt") }

    /** Eastern standard time, the computer's zone in the fixtures, in minutes east of UTC. */
    private val est = -300
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.ofHours(-5)).toInstant().toEpochMilli()

    @Test fun twelveHourTimeIsTodaysOnTheComputersClock() {
        val now = at(2026, 11, 21, 16, 20)
        assertEquals(at(2026, 11, 21, 16, 5), GrokClock.toEpoch("4:05 PM", now, est))
        assertEquals(at(2026, 11, 21, 0, 5), GrokClock.toEpoch("12:05 AM", now, est))
        assertEquals(at(2026, 11, 21, 12, 0), GrokClock.toEpoch("12:00 PM", now, est))
        assertEquals(at(2026, 11, 21, 9, 8), GrokClock.toEpoch("9:08am", now, est))
    }

    @Test fun aTimeLaterThanNowIsYesterdays() {
        // Read at 00:10: a message drawn "11:58 PM" (or "23:58") was sent before midnight.
        val now = at(2026, 11, 22, 0, 10)
        assertEquals(at(2026, 11, 21, 23, 58), GrokClock.toEpoch("11:58 PM", now, est))
        assertEquals(at(2026, 11, 21, 23, 58), GrokClock.toEpoch("23:58", now, est))
        assertEquals(at(2026, 11, 22, 0, 1), GrokClock.toEpoch("00:01", now, est))
        // A minute ahead of the computer's clock is still now, not yesterday.
        assertEquals(at(2026, 11, 22, 0, 11), GrokClock.toEpoch("12:11 AM", now, est))
    }

    @Test fun aDatedTimeTakesItsDateAndAFutureOneLastYears() {
        val now = at(2026, 11, 21, 16, 20)
        assertEquals(at(2026, 11, 20, 16, 5), GrokClock.toEpoch("Nov 20,  4:05pm", now, est))
        assertEquals(at(2026, 11, 20, 16, 5), GrokClock.toEpoch("Nov 20, 16:05", now, est))
        assertEquals(at(2025, 12, 31, 23, 0), GrokClock.toEpoch("Dec 31, 11:00pm", at(2026, 1, 1, 0, 30), est))
        assertNull(GrokClock.toEpoch("Feb 30, 1:00pm", now, est))
        assertNull(GrokClock.toEpoch("25:00", now, est))
        assertNull(GrokClock.toEpoch("13:05 PM", now, est))
    }

    @Test fun theComputersZoneIsUsedNotThePhones() {
        val now = at(2026, 11, 21, 16, 20)
        // The same "4:05 PM" on a computer in UTC is five hours earlier.
        assertEquals(at(2026, 11, 21, 11, 5), GrokClock.toEpoch("4:05 PM", now, 0))
    }

    @Test fun everyTimedRowOfTheLiveScreenIsRead() {
        val stamps = GrokClock.stamps(live)
        assertEquals(listOf("9:08 PM", "9:08 PM", "9:10 PM", "9:15 PM"), stamps.map { it.clock }.take(4))
        assertTrue(stamps.first().prefix.startsWith("Fromclaude"))
        assertTrue(stamps[1].prefix.startsWith("Thisisthefinal2.1.1candidate"))
    }

    @Test fun theTimesAreOutOfTheMessageText() {
        val segs = Reflow.parse(forms, grok = true)
        val text = segs.filterIsInstance<Seg.Para>().map { it.text } + segs.filterIsInstance<Seg.You>().map { it.text }
        assertTrue(text.contains("I am checking the build now."))
        assertTrue(text.contains("The build passed."))
        assertTrue(text.contains("A note from an older turn, drawn with its date."))
        assertTrue(text.any { it == "From claude (Herdr on minipc2; tab: claude). Check the build before midnight." })
        text.forEach { assertFalse(it, Regex("""\d{1,2}:\d{2}""").containsMatchIn(it)) }
        // And the 12-hour form, which Reflow already cut, is still cut.
        Reflow.parse(live, grok = true).filterIsInstance<Seg.Para>().forEach { assertFalse(it.text, it.text.endsWith(" PM")) }
    }

    @Test fun aTimeInsideTextOrAShortRowIsNotTakenForGroksClock() {
        assertEquals("     run at 10:30   then", GrokClock.strip("     run at 10:30   then"))
        assertEquals("     meet at       10:30", GrokClock.strip("     meet at       10:30"))
        assertTrue(GrokClock.stamps("     meet at       10:30").isEmpty())
    }

    @Test fun theScreensTimesGoToTheirMessagesAcrossMidnight() {
        val items = listOf(
            UserItem("g1", "From claude (Herdr on minipc2; tab: claude). Check the build before midnight."),
            ThinkItem("g2", "Checking.", null),
            TextItem("g3", "I am checking the build now."),
            TextItem("g4", "The **build** passed."),
            TextItem("g5", "A note from an older turn, drawn with its date."),
        )
        val got = GrokClock.match(items, forms, at(2026, 11, 22, 0, 3), est)
        assertEquals(mapOf(
            "g1" to at(2026, 11, 21, 23, 58),
            "g3" to at(2026, 11, 21, 23, 59),
            "g4" to at(2026, 11, 22, 0, 1),
            "g5" to at(2026, 11, 20, 16, 5),
        ), got)
    }

    @Test fun aRepeatedReplyTakesTheNewestRowsTime() {
        val screen = listOf("     done" + " ".repeat(100) + "3:00 PM", "", "     done" + " ".repeat(100) + "4:00 PM").joinToString("\n")
        val items = listOf(TextItem("a", "done"), UserItem("b", "again"), TextItem("c", "done"))
        val got = GrokClock.match(items, screen, at(2026, 11, 21, 16, 30), est)
        assertEquals(mapOf("a" to at(2026, 11, 21, 15, 0), "c" to at(2026, 11, 21, 16, 0)), got)
    }

    @Test fun grokScreenViewMessagesCarryTheirClock() {
        val segs = Reflow.parse(forms, grok = true)
        val msgs = ScreenTimes.messages(segs, GrokClock.stamps(forms))
        assertEquals(listOf("23:58", "23:59", "00:01", "Nov 20,  4:05pm"), msgs.map { it.clock })
    }

    @Test fun antigravityMessagesEachGetAFirstSeenTimeThatStays() {
        val segs = Reflow.parse(agy, antigravity = true, agent = "agy")
        val msgs = ScreenTimes.messages(segs)
        assertTrue(msgs.size >= 2)
        assertTrue(msgs.first().key.contains("you:"))
        val seen = ScreenTimes.stamp(emptyMap(), msgs, 1_000L)
        // Seen at once: each a moment older than the one below it, so they keep their order.
        msgs.forEach { assertTrue(seen.getValue(it.key) in (1_000L - msgs.size)..1_000L) }
        assertEquals(1_000L, seen[msgs.last().key])
        // Redrawn a minute later, and after a reload (the kept map): the same times.
        assertTrue(ScreenTimes.stamp(seen, msgs, 61_000L) === seen)
        assertEquals(seen, ScreenTimes.stamp(HashMap(seen), msgs, 61_000L))
    }

    @Test fun aMessageThatScrollsIntoViewAboveKnownOnesStaysOlder() {
        val one = listOf(Seg.You("first question"), Seg.Para(0, null, "first answer"), Seg.You("second question"), Seg.Para(0, null, "second answer"))
        val seen = ScreenTimes.stamp(emptyMap(), ScreenTimes.messages(one), 1_000L)
        // The screen scrolled: the first question is gone, and the top answer is cut to its last line.
        val two = listOf(Seg.Para(0, null, "answer, its last line"), Seg.You("second question"), Seg.Para(0, null, "second answer"), Seg.You("third"))
        val msgs = ScreenTimes.messages(two)
        val next = ScreenTimes.stamp(seen, msgs, 9_000L)
        assertTrue(next.getValue(msgs[0].key) < 1_000L)
        assertEquals(9_000L, next[msgs[3].key])
        // "second question" lost the message above it, and keeps the time it was first seen at.
        assertEquals(seen.getValue(ScreenTimes.messages(one)[2].key), next[msgs[1].key])
    }

    @Test fun aNewShortReplyDoesNotTakeAnOlderOnesTime() {
        val one = listOf(Seg.You("ping one"), Seg.Para(0, null, "ok"))
        val seen = ScreenTimes.stamp(emptyMap(), ScreenTimes.messages(one), 1_000L)
        val two = one + listOf(Seg.You("ping two"), Seg.Para(0, null, "ok"))
        val msgs = ScreenTimes.messages(two)
        val next = ScreenTimes.stamp(seen, msgs, 5_000L)
        assertEquals(1_000L, next[msgs[1].key])
        assertEquals(5_000L, next[msgs[3].key])
    }
}
