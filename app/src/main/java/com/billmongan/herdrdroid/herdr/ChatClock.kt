package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.transcript.Item
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.UserItem
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The time Grok draws at the right end of each message's first row (2.1.4, Bill: "grok isn't
 * showing timestamps"). Grok 1.0.92 draws "4:05 PM" (its 12-hour form), "16:05" (24-hour), or a
 * date with the time for an older message ("Nov 20, 4:05pm"). The chat reads it off the row, shows
 * it as the message's time, and keeps it out of the message's text.
 */
object GrokClock {
    /** A row's time: the text before it (normalized, see [norm]) and the time as drawn. */
    data class Stamp(val prefix: String, val clock: String)

    private const val MONTHS = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec"
    private const val TIME = """\d{1,2}:\d{2}(?:\s?[AaPp][Mm])?"""
    private val DATED = """(?:(?:$MONTHS)\s{1,2}\d{1,2},?\s{1,2})"""
    /** A time at the right end of a row, after at least three spaces. */
    private val AT_END = Regex("""^(.*?\S)\s{3,}($DATED?$TIME)\s*[│┃]?\s*$""")
    private val TWELVE = Regex("""^(\d{1,2}):(\d{2})\s?([AaPp])[Mm]$""")
    private val TWENTY_FOUR = Regex("""^(\d{1,2}):(\d{2})$""")
    private val WITH_DATE = Regex("""^($MONTHS)\s{1,2}(\d{1,2}),?\s{1,2}(.+)$""")
    private val LEAD = Regex("""^[\s│┃]*(?:[❯›>]\s+)?""")
    private val MARKS = Regex("""[\s`*_#>~\[\]()|\\-]""")

    /** Text for matching a screen row against a message: no spaces or Markdown marks (the screen draws none). */
    fun norm(s: String): String = s.replace(MARKS, "")

    /** Grok draws its times against the right edge of the screen; a time ending before this column is text. */
    private const val MIN_WIDTH = 60

    private fun atEnd(line: String): MatchResult? {
        val l = line.trimEnd()
        if (l.length < MIN_WIDTH) return null
        val m = AT_END.matchEntire(l) ?: return null
        return if (parseClock(m.groupValues[2]) != null) m else null
    }

    /** [line] without a time Grok drew at its right end; anything else as it was. */
    fun strip(line: String): String = atEnd(line)?.groupValues?.get(1) ?: line

    /** Each row of [screen] that ends in a time, top to bottom. */
    fun stamps(screen: String): List<Stamp> = screen.lines().mapNotNull { line ->
        val m = atEnd(line) ?: return@mapNotNull null
        val clock = m.groupValues[2]
        val prefix = norm(m.groupValues[1].replace(LEAD, ""))
        if (prefix.length < 2) null else Stamp(prefix, clock)
    }

    private data class Clock(val time: LocalTime, val month: Int?, val day: Int?)

    private fun parseClock(s: String): Clock? {
        WITH_DATE.matchEntire(s.trim())?.let { m ->
            val month = MONTHS.split('|').indexOf(m.groupValues[1]) + 1
            val day = m.groupValues[2].toInt()
            val t = parseClock(m.groupValues[3]) ?: return null
            if (t.month != null || day !in 1..31) return null
            return Clock(t.time, month, day)
        }
        TWELVE.matchEntire(s.trim())?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h !in 1..12 || min !in 0..59) return null
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            return Clock(LocalTime.of((h % 12) + if (pm) 12 else 0, min), null, null)
        }
        TWENTY_FOUR.matchEntire(s.trim())?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h !in 0..23 || min !in 0..59) return null
            return Clock(LocalTime.of(h, min), null, null)
        }
        return null
    }

    /**
     * [clock] as epoch ms, read at [now] (epoch ms on the computer's clock) in the computer's zone
     * ([tzMinutes] east of UTC; the phone's zone when null). A time with no date is today's, or
     * yesterday's when it is later than now (a message sent before midnight, read after it). A date
     * with no year is this year's, or last year's when it is later than now. Null for anything else.
     */
    fun toEpoch(clock: String, now: Long, tzMinutes: Int?): Long? {
        val c = parseClock(clock) ?: return null
        val zone: ZoneId = tzMinutes?.let { ZoneOffset.ofTotalSeconds(it.coerceIn(-18 * 60, 18 * 60) * 60) } ?: ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // Up to two minutes ahead is still now: the computer's clock and the read can differ by that much.
        val slack = 2 * 60_000L
        fun at(d: LocalDate) = d.atTime(c.time).atZone(zone).toInstant().toEpochMilli()
        if (c.month == null || c.day == null) {
            val t = at(today)
            return if (t > now + slack) at(today.minusDays(1)) else t
        }
        val d = runCatching { LocalDate.of(today.year, c.month, c.day) }.getOrNull() ?: return null
        val t = at(d)
        if (t <= now + slack) return t
        return runCatching { at(LocalDate.of(today.year - 1, c.month, c.day)) }.getOrNull()
    }

    /**
     * The chat items Grok's [screen] shows a time for, by key, as epoch ms: each timed row is
     * matched to the newest message (yours or Grok's reply) whose text starts the way the row does,
     * newest first, so the rows and messages keep their order.
     */
    fun match(items: List<Item>, screen: String, now: Long, tzMinutes: Int?): Map<String, Long> {
        val stamps = stamps(screen)
        if (stamps.isEmpty()) return emptyMap()
        val texts = items.map { when (it) { is UserItem -> norm(it.text); is TextItem -> norm(it.text); else -> null } }
        val out = HashMap<String, Long>()
        var ptr = items.size
        for (s in stamps.asReversed()) {
            val want = s.prefix.take(30)
            if (want.length < 4) continue
            var i = ptr - 1
            while (i >= 0 && texts[i]?.startsWith(want) != true) i--
            if (i < 0) continue
            toEpoch(s.clock, now, tzMinutes)?.let { out[items[i].key] = it }
            ptr = i
        }
        return out
    }
}

/**
 * Times for the screen view of the chat (2.1.4): the messages on an agent's screen, each with the
 * time Grok drew on it or, for an agent that draws none (Antigravity), the time the app first saw
 * it, kept by the app so it stays the same on every redraw and after a reload.
 */
object ScreenTimes {
    /** One message: its pieces [first] to [last] in the parsed screen, the key its first-seen time is kept under, and Grok's time. */
    data class Msg(val first: Int, val last: Int, val key: String, val clock: String?)

    private fun key(s: String) = GrokClock.norm(s).take(60)

    /**
     * The messages among [segs]: each of yours, and each reply, from its first paragraph through the
     * paragraphs and blocks under it, up to the next message, rule, activity row or quiet line. On
     * Grok's screen each paragraph with a time ([stamps]) starts a message of its own. A key holds the
     * message's text and the start of the one above it, so two short replies such as "ok" don't share one.
     */
    fun messages(segs: List<Seg>, stamps: List<GrokClock.Stamp> = emptyList()): List<Msg> {
        val out = mutableListOf<Msg>()
        var open: Triple<Int, String, String?>? = null
        var last = -1
        var above = ""
        fun close() {
            open?.let { (first, text, clock) ->
                val k = "scr:" + above.take(20) + "|" + text
                out += Msg(first, last, k, clock)
                above = text
            }
            open = null
        }
        fun clockOf(text: String): String? {
            val n = GrokClock.norm(text)
            return stamps.lastOrNull { s -> s.prefix.length >= 4 && n.startsWith(s.prefix.take(30)) }?.clock
        }
        for ((i, s) in segs.withIndex()) {
            when (s) {
                is Seg.You -> { close(); open = Triple(i, "you:" + key(s.text), clockOf(s.text)); last = i; close() }
                is Seg.Para -> if (s.detail) close() else {
                    val clock = clockOf(s.text)
                    if (open == null || clock != null) { close(); open = Triple(i, key(s.text), clock) }
                    last = i
                }
                is Seg.Code -> if (open != null) last = i
                Seg.Gap -> {}
                else -> close()
            }
        }
        close()
        return out
    }

    /**
     * [seen] with a first-seen time for each of [msgs] that has none: [now], but never later than the
     * message below it, so one that scrolls into view above known ones (cut at the top of the screen)
     * stays older than them. A message whose message above scrolled away or was cut (the top one, or
     * the one under a new top) keeps the time it was kept under before; a new message under a known
     * one never takes an older one's time just because its text is the same ("ok" again). Times already
     * kept never change.
     */
    fun stamp(seen: Map<String, Long>, msgs: List<Msg>, now: Long): Map<String, Long> {
        if (msgs.all { seen[it.key] != null }) return seen
        val out = LinkedHashMap(seen)
        // Only messages above every known one (at the top of the screen) can have lost the message above them.
        val firstKnown = msgs.indexOfFirst { seen[it.key] != null }.let { if (it < 0) msgs.size else it }
        var next = Long.MAX_VALUE
        for ((i, m) in msgs.withIndex().reversed()) {
            val t = out[m.key]
            if (t != null) { next = minOf(next, t); continue }
            val text = m.key.substringAfter('|')
            val before = if (i < firstKnown) out.entries.filter { it.key.substringAfter('|') == text && it.value <= minOf(now, next) }.maxOfOrNull { it.value } else null
            val mine = before ?: if (next == Long.MAX_VALUE) now else minOf(now, next - 1)
            out[m.key] = mine
            next = mine
        }
        // The newest [MAX] are kept.
        return if (out.size <= MAX) out else out.entries.sortedByDescending { it.value }.take(MAX).associate { it.key to it.value }
    }

    const val MAX = 600
}
