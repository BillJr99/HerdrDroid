package com.billmongan.herdrdroid.transcript

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.4 (Bill: "Also for 2.1.4 grok isn't showing timestamps make sure all panes do in chat").
 * One synthetic reader output per agent with a log reader (chat-times/<agent>.json, in the shape its
 * reader emits): a read from the start with history that has no times, then a live read whose new
 * items have none. Every message must show a time; an agent's own time goes first; a time the app
 * made up stays the same after a reload. Antigravity has no log and is covered by ScreenTimes214Test.
 */
class ChatTimes214Test {
    private val agents = listOf("claude", "codex", "grok", "hermes", "copilot", "opencode", "opencode-work",
        "pi", "omp", "kiro", "vibe", "muse")

    private fun fixture(agent: String) = JSONObject(checkNotNull(javaClass.getResource("/chat-times/$agent.json")).readText())
    private fun read(o: JSONObject) = Transcript.parseRead(o.toString())

    /** The chat after the fixture's two reads, as the app keeps it, and the time it was read at. */
    private fun chat(agent: String): Pair<CachedChat, JSONObject> {
        val f = fixture(agent)
        val bulk = f.getJSONObject("bulk")
        val live = f.getJSONObject("live")
        val empty = CachedChat(emptyList(), "", false, null, agent = agent)
        val c = empty.withRead(read(bulk), bulk.getLong("now")).withRead(read(live), live.getLong("now"))
        return c to f
    }

    private fun shown(c: CachedChat) = Transcript.shownTimes(c.logTimes, c.shown)

    @Test fun everyMessageOfEveryAgentShowsATime() {
        for (agent in agents) {
            val (c, _) = chat(agent)
            // Four turns of a prompt, a thought, a tool call (its result folded in) and a reply.
            assertEquals(agent, 16, c.items.size)
            val times = shown(c)
            c.items.forEach { assertNotNull("$agent ${it.key} has no time", times[it.key]) }
        }
    }

    @Test fun anAgentsOwnTimeIsShownWhereItHasOne() {
        for (agent in agents) {
            val (c, f) = chat(agent)
            val raw = f.getJSONObject("bulk").getJSONArray("items")
            for (i in 0 until raw.length()) {
                val j = raw.getJSONObject(i)
                val own = j.optLong("ts", 0L).takeIf { it > 0 } ?: j.optLong("at", 0L).takeIf { it > 0 } ?: continue
                if (j.optString("t") == "result") continue
                assertEquals("$agent ${j.getString("k")}", own, shown(c)[j.getString("k")])
            }
        }
    }

    @Test fun grokAndKiroTimesAreLabelsOnlyAndDoNotMoveQuestions() {
        // "at" is shown, but questions are still placed by the log's own "ts" alone, as before 2.1.4.
        val (grok, _) = chat("grok")
        assertTrue(grok.items.none { grok.times[it.key] != null && it.key.startsWith("g0") })
        assertEquals(1_795_290_000_000L, grok.logTimes["g0"])
        val (kiro, _) = chat("kiro")
        assertEquals(1_795_290_004_000L, kiro.logTimes["k4"])
        assertEquals(null, kiro.times["k4"])
    }

    @Test fun historyWithNoTimeTakesTheTimeOfItsTurnAndANewItemTheTimeItArrived() {
        val (c, f) = chat("kiro")
        val times = shown(c)
        // k1 to k3 are inside the first timed turn: they show its prompt's time, not the read's.
        assertEquals(times["k0"], times["k1"])
        assertEquals(times["k0"], times["k2"])
        // History above every timed item, read in bulk: no later than the item under it.
        assertTrue(times.getValue("k100") < times.getValue("k0"))
        // The live read's items, which have no time of their own: when the app saw them. Items that
        // arrive together are kept in strict order a millisecond apart, so allow up to a second.
        val now = f.getJSONObject("live").getLong("now")
        for (k in listOf("k20", "k24")) assertTrue("$k ${times[k]} vs $now", (now - times.getValue(k)) in 0L..1_000L)
    }

    @Test fun aMadeUpTimeIsTheSameAfterAReloadAndALaterRead() {
        for (agent in agents) {
            val (c, f) = chat(agent)
            val before = shown(c)
            val back = checkNotNull(ChatCodec.decode(ChatCodec.encode(c)))
            assertEquals(agent, before, shown(back))
            // The same session read again from the start, an hour later: nothing already shown moves.
            val bulk = f.getJSONObject("bulk")
            val again = back.withRead(read(bulk), bulk.getLong("now") + 3_600_000L)
            before.forEach { (k, v) -> assertEquals("$agent $k", v, shown(again)[k]) }
        }
    }

    @Test fun anOwnTimeThatTurnsUpLaterReplacesTheMadeUpOne() {
        val items = listOf(UserItem("a", "hi"), TextItem("b", "hello"))
        val shown = Transcript.stampShown(emptyMap(), items, emptyMap(), emptyMap(), 5_000L)
        assertEquals(mapOf("a" to 5_000L, "b" to 5_000L), shown)
        // Grok's screen time for "b" turns up: it is shown, and the kept one stays as it was.
        val labels = mapOf("b" to 4_000L)
        assertEquals(shown, Transcript.stampShown(shown, items, labels, emptyMap(), 9_000L))
        assertEquals(mapOf("a" to 5_000L, "b" to 4_000L), Transcript.shownTimes(labels, shown))
    }

    @Test fun messagesKeepTheirOrder() {
        // The times are labels: the chat's items are in the order the reads gave them.
        val (c, _) = chat("grok")
        assertEquals(listOf("g100", "g101", "g102", "g104", "g0", "g1", "g2", "g4", "g10", "g11", "g12", "g14", "g20", "g21", "g22", "g24"),
            c.items.map { it.key })
    }
}
