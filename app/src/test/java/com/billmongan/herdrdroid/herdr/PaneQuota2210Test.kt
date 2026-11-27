package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.store.Server
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneQuota2210Test {
    private val now = Instant.parse("2026-01-01T00:10:00Z")
    private val timestamp = "2026-01-01T00:05:00Z"
    private val valid = """{"p1":{"used_percent":12.5,"observed_at":"$timestamp"}}"""
    private val pane = Pane("p1", "t1", "w1", "demo", Status.IDLE, "", "", terminalId = "term1")

    @Test fun acceptsOnlyNumericPercentAndUsableTimestamp() {
        assertEquals(mapOf("p1" to PaneQuotaReading(12.5, Instant.parse(timestamp))), parsePaneQuotas(valid, now))
        listOf(
            "garbage", "[]", valid + "{}", valid.replace("12.5", "\"12.5\""),
            valid.replace("12.5", "true"), valid.replace("12.5", "1e400"), valid.replace("12.5", "-1"),
            valid.replace("12.5", "101"), valid.replace(timestamp, "yesterday"),
            valid.replace(timestamp, "2026-01-01T02:00:00Z"), valid.replace("\"$timestamp\"", "123"),
        ).forEach { assertTrue(it, parsePaneQuotas(it, now).isEmpty()) }
    }

    @Test fun disabledSourceDoesNotRunAnythingAndConnectionChangeDropsResults() = runBlocking {
        var connection = Any()
        var calls = 0
        val source = PaneQuotaSource({ connection }, { _, expected ->
            assertTrue(expected === connection)
            calls++
            connection = Any()
            0 to valid.toByteArray()
        }, wallTime = { now })
        assertNull(source.decorate(listOf(pane), null, connection).single().quotaReading)
        assertEquals(0, calls)
        assertNull(source.decorate(listOf(pane), "quota-source", connection).single().quotaReading)
        assertEquals(1, calls)
    }

    @Test fun cachesPerOccupantAndCommandAndHidesFailedSamples() = runBlocking {
        val connection = Any()
        var nano = 0L
        var calls = 0
        var result = 0 to valid.toByteArray()
        val source = PaneQuotaSource({ connection }, { _, _ -> calls++; result }, { nano }, { now })
        suspend fun shown(p: Pane = pane, command: String = "quota-source") =
            source.decorate(listOf(p), command, connection).single().quotaReading?.usedPercent

        assertEquals(12.5, shown())
        assertEquals(12.5, shown())
        assertNull(shown(pane.copy(terminalId = "term2")))
        assertNull(shown(pane.copy(tabLabel = "probe-quota")))
        assertNull(shown(pane.copy(id = "p2")))
        assertEquals(1, calls)

        result = 1 to valid.toByteArray()
        nano = 30_000_000_000L
        assertNull(shown())
        assertNull(shown())
        assertEquals(2, calls)

        result = 0 to ByteArray(QUOTA_OUTPUT_LIMIT + 1)
        assertNull(shown(command = "different-quota-source"))
        assertEquals(3, calls)
    }

    @Test fun serverConfigurationIsOptionalAndRoundTrips() {
        val saved = Server(name = "", host = "example.invalid", port = 22, user = "example", herdrPath = "")
            .copy(paneQuotaCommand = "quota-source")
        val json = saved.toJson()
        assertEquals("quota-source", Server.fromJson(json).paneQuotaCommand)
        json.remove("paneQuotaCommand")
        assertNull(Server.fromJson(json).paneQuotaCommand)
    }
}
