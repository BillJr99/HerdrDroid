package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 2.2.7: cross-server messages and notices take the same long-text route as the message box. */
class LongRelay227Test {
    private val pane = Pane("pane-1", "tab-1", "ws-1", "codex", Status.IDLE, "Codex", "/project", scrollOffset = 0, terminalId = "term-1", sessionId = "session-1")

    private class Host(var pane: Pane?) : PromptRelayPort {
        val connection: Any = Any()
        var failUpload = false
        val writes = mutableListOf<String>()
        val files = mutableListOf<ByteArray>()
        override val connectionIdentity get() = connection
        override suspend fun pane(id: String, connection: Any) = pane?.takeIf { it.id == id }
        override suspend fun hasWork(pane: Pane, connection: Any) = false
        override suspend fun viewport(id: String, connection: Any) = "❯ "
        override suspend fun send(pane: Pane, text: String, connection: Any) { writes += text }
        override suspend fun upload(name: String, bytes: ByteArray, connection: Any): UploadReceipt {
            if (failUpload) throw UploadMismatch("its checksum didn't match")
            files += bytes
            return UploadReceipt("/home/fixture/.herdrdroid/inbox/20261123-000000-$name", bytes.size.toLong(), sha256Hex(bytes))
        }
    }

    private fun log(chars: Int) = buildString { var i = 0; while (length < chars) append("row ${i++} it's the log\n") }.take(chars)

    private val remote = Host(pane)
    private val relay = PromptRelay(PaneDispatch(), mapOf("remote" to remote)::get) { _, _ -> true }

    @Test fun aShortRelayIsTypedAsIs() = runBlocking {
        val target = checkNotNull(relay.bind("remote", pane))
        assertEquals(RelayResult.SENT, relay.send(target, "Check the build") { true })
        assertEquals(listOf("Check the build"), remote.writes)
        assertTrue(remote.files.isEmpty())
    }

    @Test fun aLongRelayGoesAsACheckedFileWithOneLineUnderOneApproval() = runBlocking {
        for (size in listOf(55_710, 500_000)) {
            remote.writes.clear(); remote.files.clear()
            val text = log(size)
            val target = checkNotNull(relay.bind("remote", pane))
            assertEquals(RelayResult.SENT, relay.send(target, text) { true })
            assertArrayEquals(text.toByteArray(), remote.files.single())
            val line = remote.writes.single()
            val (shownSize, path) = checkNotNull(LongText.parseFileLine(line))
            assertEquals(LongText.size(text.toByteArray().size.toLong()), shownSize)
            assertEquals(text, SentFiles.original(path))
        }
    }

    @Test fun aLongRelayThatCannotBeFiledIsRefusedNotCut() = runBlocking {
        remote.failUpload = true
        var why: RelayRefusal? = null
        val target = checkNotNull(relay.bind("remote", pane))
        assertEquals(RelayResult.REFUSED, relay.send(target, log(30_000), refused = { why = it }) { true })
        assertEquals(RelayRefusal.UPLOAD, why)
        assertTrue("nothing typed", remote.writes.isEmpty())
    }

    private class Local(val pane: Pane) : LocalPromptSend.Port {
        val writes = mutableListOf<String>()
        val files = mutableListOf<ByteArray>()
        override suspend fun pane(id: String) = pane
        override suspend fun viewport(id: String) = "❯ "
        override fun blocked(fresh: Pane, viewport: String) = false
        override suspend fun send(fresh: Pane, text: String, permit: WritePermit) { permit.admit(); writes += text }
        override suspend fun upload(name: String, bytes: ByteArray): UploadReceipt {
            files += bytes
            return UploadReceipt("/home/fixture/.herdrdroid/inbox/x-$name", bytes.size.toLong(), sha256Hex(bytes))
        }
    }

    @Test fun aLongNoticeOrVoiceMessageOnThisServerGoesAsAFileToo() = runBlocking {
        val dispatch = PaneDispatch()
        val send = LocalPromptSend(dispatch, WriteGate())
        val port = Local(pane)
        val text = log(55_710)
        val r = send.send("local", Occupant.of(pane), text, { true }, port)
        assertEquals(LocalPromptSend.Outcome.SENT, r.outcome)
        assertArrayEquals(text.toByteArray(), port.files.single())
        assertTrue(port.writes.single().startsWith("Pasted text ("))
        val short = Local(pane)
        assertEquals(LocalPromptSend.Outcome.SENT, send.send("local", Occupant.of(pane), "hello", { true }, short).outcome)
        assertEquals(listOf("hello"), short.writes)
    }
}
