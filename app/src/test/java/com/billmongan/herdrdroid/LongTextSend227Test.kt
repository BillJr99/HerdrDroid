package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.LongText
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.sha256Hex
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * 2.2.7, Bill: "make sure larger pastes become file attachments automatically at the appropriate
 * size so we never actually truncate anything". Every send path (the message box, the idle queue,
 * Send now; voice uses the same send) delivers all of it: as one message up to 20 KB quoted, or as
 * a file whose SHA-256 the computer confirms.
 */
@RunWith(AndroidJUnit4::class)
class LongTextSend227Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()

    private companion object {
        val idle = checkNotNull(LongTextSend227Test::class.java.getResource("/screens/claude/derived-2.1.330-shells/working-3-shells.ansi"))
            .readText().replace("\r", "").replace("esc to interrupt", "")
        /** Log-like rows with no single quotes (the fake reads the shell-quoted argument back as is). */
        fun paste(chars: Int) = buildString {
            var i = 0
            while (length < chars) { append("17:49:14.842 [ssh] row ").append(i++).append(" connect failed after 8000ms ECONNABORTED\n") }
        }.take(chars)
    }

    private fun fixture(test: Fixture.() -> Unit) {
        val f = Fixture()
        try { f.test() } finally {
            f.vm.clearForTest()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Fixture : Herdr.Commands {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val server = Server(id = "fixture", name = "Fixture", host = "127.0.0.1", port = 1, user = "fixture", herdrPath = "/fixture/herdr")
        private val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        override var connection: Any? = Any()
        /** Messages as the agent got them, in order. */
        val prompts = mutableListOf<String>()
        /** Files written to the inbox: the bytes that arrived. */
        val uploads = mutableListOf<ByteArray>()
        val uploadNames = mutableListOf<String>()
        var corruptUploads = false
        var noSum = false
        var diskFull = false

        init {
            field("herdr").set(vm, Herdr(ssh, server.herdrPath, Backend.TUI, null, this))
            state("current", server)
            state("link", Link.CONNECTED)
            state("panes", listOf(Pane("p1", "tab", "w1", "claude", Status.IDLE, "", "/fixture", terminalId = "t1")))
            field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
            field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
            vm.screen = Screen.Agent("p1")
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun pane() = checkNotNull(vm.pane("p1"))

        fun drainUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 20_000_000_000L
            do {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                if (done()) return
                Thread.sleep(1)
            } while (System.nanoTime() < deadline)
            fail("did not finish; prompts=${prompts.size} uploads=${uploads.size} queued=${vm.queued.size}")
        }

        override suspend fun bulk(command: String): ExecResult = ok("""{"result":{"panes":[$pane]}}
            |@@herdrdroid-tabs@@
            |{"result":{"tabs":[]}}
        """.trimMargin())

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult = when {
            command.endsWith("'pane' 'get' 'p1'") -> ok("""{"result":{"pane":$pane}}""")
            command.contains("'pane' 'read' 'p1'") -> ok(idle)
            command.contains("'agent' 'prompt' 'p1' ") || command.contains("'pane' 'send-text' 'p1' ") -> {
                val text = command.substringAfter("'p1' ").removeSurrounding("'")
                if (command.contains("'agent' 'prompt'")) prompts += text
                ok("")
            }
            else -> ok("")
        }

        override suspend fun input(command: String, input: ByteArray, expectedConnection: Any): ExecResult {
            assertTrue(command, command.contains(".herdrdroid/inbox") && command.contains("sha256sum"))
            uploads += input
            val sha = if (corruptUploads) "0".repeat(64) else sha256Hex(input)
            // A shell banner first, then the marker and the results (pre-review M4).
            val name = command.substringAfter(".herdrdroid/inbox/").substringBefore("'").removeSuffix(".part")
            uploadNames += name
            if (diskFull) return ExecResult(1, "", "cat: write error: No space left on device")
            return ok("Welcome to fixture\n${com.billmongan.herdrdroid.herdr.UPLOAD_MARKER}\n/home/fixture/.herdrdroid/inbox/$name\n${input.size}\n${if (noSum) "nosum" else sha}\n")
        }

        private val pane = """{"pane_id":"p1","workspace_id":"w1","tab_id":"tab","agent":"claude","agent_status":"idle","cwd":"/fixture","terminal_id":"t1","scroll":{"offset_from_bottom":0}}"""
        private fun ok(out: String) = ExecResult(0, out, "")
        fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }
    }

    @Test fun aPasteUnder20KbArrivesWholeAsOneMessage() = fixture {
        for (size in listOf(8_795, 19_000)) {
            val text = paste(size)
            val sent = prompts.size
            vm.sendMessage(pane(), text)
            drainUntil { prompts.size == sent + 1 }
            assertEquals(text, prompts.last())
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("nothing sent twice", 2, prompts.size)
        assertTrue(uploads.isEmpty())
    }

    @Test fun apostrophesNear20KbGoAsAFileSinceTheyGrowWhenQuoted() = fixture {
        val words = "it's Bill's log, don't cut it\n"
        val text = words.repeat(19 * 1024 / words.length)
        vm.sendMessage(pane(), text)
        drainUntil { prompts.size == 1 }
        assertArrayEquals(text.toByteArray(), uploads.single())
        assertTrue(prompts.single().startsWith("Pasted text ("))
    }

    @Test fun pastesOf55kAnd500kGoAsCheckedFilesFromEveryPath() = fixture {
        val paths = listOf<(String) -> Unit>(
            { t -> vm.sendMessage(pane(), t) },
            { t -> vm.enqueue("p1", t); vm.sendQueuedImmediately(vm.queued.last()) },
            { t -> vm.enqueue("p1", t); vm.sendQueuedNow(vm.queued.last()) },
        )
        for (size in listOf(55_710, 500_000)) for ((k, send) in paths.withIndex()) {
            val text = paste(size)
            val before = uploads.size
            val sent = prompts.size
            send(text)
            drainUntil { prompts.size == sent + 1 }
            assertEquals("path $k, $size: one file", before + 1, uploads.size)
            assertArrayEquals("path $k, $size: the whole text", text.toByteArray(), uploads.last())
            assertEquals(sha256Hex(text.toByteArray()), sha256Hex(uploads.last()))
            assertTrue(prompts.last(), prompts.last().startsWith("Pasted text (") && prompts.last().contains("attached: /home/fixture/.herdrdroid/inbox/"))
            drainUntil { vm.queued.isEmpty() }
        }
    }

    @Test fun aFileThatDoesNotArriveWholeIsRetriedOnceThenTheTextWaitsUncut() = fixture {
        corruptUploads = true
        val text = paste(55_710)
        vm.sendMessage(pane(), text)
        drainUntil { vm.queued.isNotEmpty() }
        assertEquals("written, checked, written again", 2, uploads.size)
        assertTrue("no message points at a partial file", prompts.isEmpty())
        assertEquals(text, vm.queued.single().text)
        assertTrue(vm.queued.single().note.orEmpty().contains("couldn't be put on the computer whole"))
    }

    /** Pre-review H1: two long texts in the same second each get their own file, and each message points at its own. */
    @Test fun twoLongTextsAtOnceNeverShareAFile() = fixture {
        val a = paste(30_000).replace("row", "first")
        val b = paste(30_000).replace("row", "second")
        vm.sendMessage(pane(), a)
        vm.sendMessage(pane(), b)
        drainUntil { prompts.size == 2 }
        assertEquals(2, uploadNames.toSet().size)
        val paths = prompts.map { checkNotNull(LongText.parseFileLine(it)).second }
        assertEquals(2, paths.toSet().size)
        assertEquals(a, com.billmongan.herdrdroid.herdr.SentFiles.original(paths[0]))
        assertEquals(b, com.billmongan.herdrdroid.herdr.SentFiles.original(paths[1]))
    }

    @Test fun withNoChecksumToolTheSizeIsCheckedAndItStillGoes() = fixture {
        noSum = true
        vm.sendMessage(pane(), paste(30_000))
        drainUntil { prompts.size == 1 }
        assertEquals(1, uploads.size)
    }

    /** Pre-review M4: a full disk is not retried every few seconds; the text waits for a tap, uncut. */
    @Test fun aFailedUploadWaitsForATapInsteadOfRetryingForever() = fixture {
        diskFull = true
        val text = paste(30_000)
        vm.sendMessage(pane(), text)
        drainUntil { vm.queued.isNotEmpty() }
        val left = vm.queued.single()
        assertTrue(left.needsTap)
        assertEquals(text, left.text)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        assertEquals("no retries by itself", 1, uploadNames.size)
        // A tap tries again, and with room on the disk it goes.
        diskFull = false
        vm.sendQueuedNow(vm.queued.single())
        drainUntil { prompts.size == 1 && vm.queued.isEmpty() }
    }
}
