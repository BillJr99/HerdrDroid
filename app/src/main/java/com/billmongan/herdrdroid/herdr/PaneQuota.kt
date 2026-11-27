package com.billmongan.herdrdroid.herdr

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONTokener

data class PaneQuotaReading(val usedPercent: Double, val observedAt: Instant)

internal const val QUOTA_COMMAND_LIMIT = 1024
internal const val QUOTA_OUTPUT_LIMIT = 64 * 1024
internal const val QUOTA_TIMEOUT_SECONDS = 3L
private const val POLL_INTERVAL_NANOS = 30_000_000_000L

internal fun parsePaneQuotas(text: String, now: Instant): Map<String, PaneQuotaReading> {
    val root = try {
        val tokens = JSONTokener(text)
        (tokens.nextValue() as? JSONObject)?.takeIf { tokens.nextClean() == '\u0000' }
    } catch (_: org.json.JSONException) {
        null
    } ?: return emptyMap()
    return buildMap {
        root.keys().forEach { id ->
            val row = root.optJSONObject(id) ?: return@forEach
            val percent = (row.opt("used_percent") as? Number)?.toDouble() ?: return@forEach
            if (!percent.isFinite() || percent !in 0.0..100.0) return@forEach
            val timestamp = row.opt("observed_at") as? String ?: return@forEach
            val observed = try {
                Instant.parse(timestamp)
            } catch (_: java.time.format.DateTimeParseException) {
                return@forEach
            }
            if (observed.epochSecond < 0 || observed > now.plusSeconds(300)) return@forEach
            put(id, PaneQuotaReading(percent, observed))
        }
    }
}

internal class PaneQuotaSource(
    private val currentConnection: () -> Any?,
    private val read: suspend (String, Any) -> Pair<Int, ByteArray>,
    private val monotonicTime: () -> Long = System::nanoTime,
    private val wallTime: () -> Instant = Instant::now,
) {
    private data class Sample(
        val connection: Any,
        val command: String,
        val at: Long,
        val readings: Map<Occupant, PaneQuotaReading>,
    )

    private val lock = Mutex()
    private var cached: Sample? = null

    suspend fun decorate(panes: List<Pane>, command: String?, connection: Any?): List<Pane> = lock.withLock {
        val configured = command?.takeIf { it.isNotBlank() && it.length <= QUOTA_COMMAND_LIMIT && it.none(Char::isISOControl) }
        if (configured == null || connection == null || currentConnection() !== connection) {
            cached = null
            return@withLock panes
        }
        val now = monotonicTime()
        val previous = cached
        val sample = if (previous != null && previous.connection === connection &&
            previous.command == configured && now - previous.at < POLL_INTERVAL_NANOS
        ) previous else {
            val readings = try {
                val (exit, bytes) = read(configured, connection)
                if (exit == 0 && bytes.size <= QUOTA_OUTPUT_LIMIT) parsePaneQuotas(bytes.toString(Charsets.UTF_8), wallTime())
                else emptyMap()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                emptyMap()
            }
            Sample(connection, configured, now, panes.filterNot(Pane::isProbe).mapNotNull { pane ->
                readings[pane.id]?.let { Occupant.of(pane) to it }
            }.toMap()).also { cached = it }
        }
        if (currentConnection() !== connection) return@withLock panes
        panes.map { pane ->
            // When the host cannot identify the occupant, do not carry a sample to a later list.
            val occupant = Occupant.of(pane)
            val quota = sample.readings[occupant]?.takeIf { !pane.isProbe && (sample !== previous || occupant.stable) }
            if (quota == null) pane else pane.copy(quotaReading = quota)
        }
    }
}
