package com.billmongan.herdrdroid.herdr

import org.json.JSONObject

/**
 * 2.1.4 (Bill: "Herdrdroid should tell the panes about the instructions and other panes and
 * servers when the cross server feature is enabled"). One short, informational notice per agent:
 * how to propose a message, which agents on other servers the phone can reach (by name and
 * server label only), and that the phone user approves every delivery. 2.1.5: the agent proposes
 * only when the user asks it in chat, with a code it picks, and the user confirms by typing that
 * code back in the pane's chat on the phone.
 */
internal object CrossServerNotice {
    const val MAX_CHARS = 1_200

    /** The notice for one agent, or null when no agent on another server is reachable. */
    fun text(others: List<String>): String? {
        val names = others.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return null
        return """HerdrDroid notice, for information only. No reply is needed; if you must answer, reply only "ok".
When the user asks you in chat to send a message to an agent on another server, do not send it yourself. Render only this frame in a plain fenced code block, then wait:
HDMSG2 <code>
HDTO <destination>
HD> <standard padded Base64 of your UTF-8 message, chunks of at most 40 characters, each with its own HD> prefix>
HDEND <code>
<code> is a new code you pick for each frame: 6 characters from ${CrossServerCode.ALPHABET}. <destination> is one name from the list below, exactly as written. The message must be nonblank, at most 1024 UTF-8 bytes, with no controls except line feeds and no secrets. Do not run a remote command.
Agents on other servers: ${names.joinToString(", ")}. The phone shows your frame to the user on a card, and it is delivered only if the user types the code back on the phone; you will not see that reply."""
    }

    /** Whether the known usage of an agent is at or above [threshold] percent. 100 never skips; unknown never skips. */
    fun quotaSkips(reading: QuotaReading?, threshold: Int): Boolean {
        if (threshold >= 100 || reading == null || reading.items.isEmpty()) return false
        return reading.items.maxOf { it.usedPercent } >= threshold
    }

    const val DEFAULT_QUOTA_THRESHOLD = 95

    /** The setting's allowed values: 50 to 100 in steps of 5. */
    fun clampThreshold(v: Int): Int = (((v.coerceIn(50, 100) + 2) / 5) * 5).coerceIn(50, 100)
}

/**
 * What was sent, keyed by server and pane, with the agent session and the notice's fingerprint, so
 * an app restart does not send it again. Stored as JSON through [load] and [save].
 */
internal class NoticeLedger(private val load: () -> String, private val save: (String) -> Unit) {
    data class Entry(val session: String, val fingerprint: String, val at: Long)

    private val entries: MutableMap<String, Entry> = runCatching {
        val o = JSONObject(load().ifBlank { "{}" })
        o.keys().asSequence().associateWith { k ->
            o.getJSONObject(k).let { Entry(it.getString("s"), it.getString("f"), it.getLong("at")) }
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    fun get(key: String): Entry? = entries[key]

    fun put(key: String, entry: Entry, now: Long) {
        entries[key] = entry
        // Old panes are forgotten after 30 days.
        entries.entries.removeAll { now - it.value.at > 30L * 24 * 3_600_000 }
        save(JSONObject().apply {
            entries.forEach { (k, e) -> put(k, JSONObject().put("s", e.session).put("f", e.fingerprint).put("at", e.at)) }
        }.toString())
    }
}

/**
 * The rules for the notice (2.1.4): at most once per pane per agent session and reachable set,
 * never more than once an hour per pane unless forced ("Tell agents now" bypasses only the hourly
 * limit), held while the pane works, has a draft or is over quota, and given up after [maxTries]
 * refused attempts.
 */
internal class CrossServerNotifier(
    private val ledger: NoticeLedger,
    private val clock: () -> Long,
    val maxTries: Int = 5,
    private val retryGapMs: Long = 120_000,
    private val hourMs: Long = 3_600_000,
) {
    data class Candidate(val server: String, val pane: Pane, val text: String, val hold: Boolean) {
        val key get() = "$server|${pane.id}"
        val session get() = pane.sessionId ?: pane.terminalId.orEmpty()
        val fingerprint get() = fingerprint(text)
    }

    enum class Decision { SEND, ALREADY_SENT, HOURLY, HOLD, RETRY_LATER, GAVE_UP }

    private data class Tries(val session: String, val fingerprint: String, val count: Int, val last: Long)
    private val tries = mutableMapOf<String, Tries>()
    private val gaveUp = mutableMapOf<String, Pair<String, String>>()

    fun decide(c: Candidate, forced: Boolean): Decision {
        val now = clock()
        val rec = ledger.get(c.key)
        if (rec != null && rec.session == c.session && rec.fingerprint == c.fingerprint) return Decision.ALREADY_SENT
        if (!forced && gaveUp[c.key] == (c.session to c.fingerprint)) return Decision.GAVE_UP
        if (c.hold) return Decision.HOLD
        if (!forced && rec != null && now - rec.at < hourMs) return Decision.HOURLY
        val t = tries[c.key]?.takeIf { it.session == c.session && it.fingerprint == c.fingerprint }
        if (!forced && t != null && now - t.last < retryGapMs) return Decision.RETRY_LATER
        return Decision.SEND
    }

    /** Records a sent (or possibly sent) notice so it is never sent twice. */
    fun sent(c: Candidate) {
        val now = clock()
        tries.remove(c.key)
        gaveUp.remove(c.key)
        ledger.put(c.key, NoticeLedger.Entry(c.session, c.fingerprint, now), now)
    }

    /** Counts a refused attempt. True when this was the last try, so the pane gets a notice. */
    fun refused(c: Candidate, forced: Boolean): Boolean {
        val now = clock()
        val prior = tries[c.key]?.takeIf { it.session == c.session && it.fingerprint == c.fingerprint }
        val count = if (forced) 1 else (prior?.count ?: 0) + 1
        tries[c.key] = Tries(c.session, c.fingerprint, count, now)
        if (count < maxTries) return false
        gaveUp[c.key] = c.session to c.fingerprint
        tries.remove(c.key)
        return true
    }

    companion object {
        fun fingerprint(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12)
                .joinToString("") { "%02x".format(it) }
    }
}
