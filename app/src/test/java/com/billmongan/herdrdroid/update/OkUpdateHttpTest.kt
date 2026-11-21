package com.billmongan.herdrdroid.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** 2.1.4: what the update check sends, against a local fake server (no network). */
class OkUpdateHttpTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }

    @After fun stop() = server.shutdown()

    @Test fun theCheckSendsOnlyAGenericUserAgentAndNoCredentials() {
        server.enqueue(MockResponse().setBody("{}"))
        val body = OkUpdateHttp(requireHttps = false).getText(server.url("/repos/BillJr99/HerdrDroid/releases/latest").toString(), "HerdrDroid/2.1.4")
        assertEquals("{}", body)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("HerdrDroid/2.1.4", r.getHeader("User-Agent"))
        assertNull(r.getHeader("Authorization"))
        assertNull(r.getHeader("Cookie"))
        assertEquals(0L, r.bodySize)
        val names = r.headers.names().map { it.lowercase() }.toSet()
        assertEquals(setOf("host", "connection", "accept-encoding", "user-agent", "accept"), names)
    }

    @Test fun anErrorAnswerFailsTheCheck() {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            OkUpdateHttp(requireHttps = false).getText(server.url("/latest").toString(), "HerdrDroid/2.1.4")
            fail("expected an IOException")
        } catch (_: IOException) {}
    }

    @Test fun plainHttpIsRefusedBeforeAnyRequest() {
        try {
            OkUpdateHttp().getText(server.url("/latest").toString(), "HerdrDroid/2.1.4")
            fail("expected an IOException")
        } catch (_: IOException) {}
        assertEquals(0, server.requestCount)
    }

    @Test fun theDownloadReportsProgressAndRefusesMoreThanTheReleaseLists() {
        val apk = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val dest = tmp.newFile("a.apk")
        var seen = 0L
        OkUpdateHttp(requireHttps = false).download(server.url("/a.apk").toString(), "HerdrDroid/2.1.4", dest, apk.size.toLong(), { seen = it }, { false })
        assertTrue(dest.readBytes().contentEquals(apk))
        assertEquals(apk.size.toLong(), seen)

        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        try {
            OkUpdateHttp(requireHttps = false).download(server.url("/a.apk").toString(), "HerdrDroid/2.1.4", tmp.newFile("b.apk"), 1000, {}, { false })
            fail("expected an IOException")
        } catch (_: IOException) {}
    }

    @Test fun aCancelledDownloadStops() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(500_000))))
        val dest = tmp.newFile("c.apk")
        OkUpdateHttp(requireHttps = false).download(server.url("/c.apk").toString(), "HerdrDroid/2.1.4", dest, 500_000, {}, { true })
        assertFalse(dest.length() == 500_000L)
    }
}
