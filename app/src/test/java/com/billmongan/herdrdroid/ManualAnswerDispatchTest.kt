package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.PaneDispatch
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.KeySource
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ManualAnswerDispatchTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @Test fun dispatchChecksLiveCardWithoutWaitingForBulkAndRefusesChangedApproval() = runBlocking {
        val fixture = System.getenv("HD_CARD_FIXTURE_DIR")?.let(::File)
        assumeTrue("no isolated approval SSH fixture", fixture != null)
        val dir = checkNotNull(fixture)
        val cli = File(dir, "fixture-cli").path
        val server = Server(id = "test", name = "Test", host = "127.0.0.1", port = 2222, user = "dev", herdrPath = cli)
        val ssh = HerdrSsh(server.host, server.port, server.user,
            Credentials(KeySource.Imported(File(dir, "client-key").readText(), null), null),
            File(dir, "pin").readText().trim(), { null })
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val h = Herdr(ssh, cli)
        val trace = File(dir, "trace")
        val release = File(dir, "release")
        val bulkStarted = File(dir, "bulk-started")
        fun spin(done: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000
            while (!done() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
            assertTrue("fixture timed out", done())
        }
        try {
            assertEquals(0, ssh.execNow("true").exit)
            val connection = checkNotNull(h.connectionIdentity)
            field("herdr").set(vm, h)
            state<Server?>(vm, "current").value = server
            vm.screen = Screen.Agent("p1")
            val bulk = async(Dispatchers.IO) { ssh.exec("'$cli' bulk", timeoutSeconds = 40) }
            try {
                spin { bulkStarted.exists() }
                for (agent in listOf("claude", "codex")) {
                    File(dir, "agent").writeText(agent)
                    val pane = checkNotNull(h.cardPane("p1", connection))
                    state<List<com.billmongan.herdrdroid.herdr.Pane>>(vm, "panes").value = listOf(pane)
                    val shown = checkNotNull(detectPrompt(File(dir, "$agent.txt").readText(), true, agent)).forCard()
                    val target = checkNotNull(vm.cardTarget("p1"))
                    if (agent == "codex") {
                        trace.writeText("")
                        File(dir, "changed").writeText("")
                        var delivered = false
                        vm.answerTyped(target, shown, null, "sample answer") { delivered = true }
                        spin { !vm.answerInFlight("p1") }
                        assertFalse(delivered)
                        assertEquals(listOf("get", "visible"), trace.readLines().map { it.substringBefore(' ') })
                        assertEquals(0, vm.queuedCount)
                        assertTrue(File(dir, "changed").delete())
                    }
                    trace.writeText("")
                    val start = System.nanoTime()
                    vm.answerChoice("p1", shown, shown.choices.first(), target = target)
                    assertTrue(vm.answerInFlight("p1"))
                    vm.answerChoice("p1", shown, shown.choices.first(), target = target)
                    spin { !vm.answerInFlight("p1") }
                    val events = trace.readLines().map { it.split(' ') }
                    assertEquals(listOf("get", "visible", "key"), events.take(3).map { it[0] })
                    assertEquals(1, events.count { it[0] == "key" })
                    assertFalse(bulk.isCompleted)
                    val snapshot = events[1][1].toLong()
                    val key = events[2][1].toLong()
                    println("card-fixture $agent tap_to_snapshot_ms=${(snapshot - start) / 1_000_000.0} " +
                        "snapshot_to_key_ms=${(key - snapshot) / 1_000_000.0} tap_to_key_ms=${(key - start) / 1_000_000.0}")
                }
            } finally {
                release.writeText("")
                bulk.await()
            }
        } finally {
            vm.clearForTest()
            ssh.close()
        }
    }

    @Test fun occupiedPaneShowsFailureForAnOrdinaryCardTapWithoutQueueing() {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val server = Server(id = "test", name = "Test", host = "127.0.0.1", port = -1, user = "test", herdrPath = "herdr")
        // Install an unopened client to reach the busy-lane branch without a connection attempt.
        val ssh = HerdrSsh(server.host, server.port, server.user, Credentials(null, null), null, { null })
        field("herdr").set(vm, Herdr(ssh, server.herdrPath))
        field("current\$delegate").get(vm).let {
            @Suppress("UNCHECKED_CAST")
            (it as MutableState<Server?>).value = server
        }
        val dispatch = field("paneDispatch").get(vm) as PaneDispatch
        val paneId = "test-pane"
        val key = server.id to paneId
        val release = dispatch.tryAcquire(key)!!
        val choice = Choice("Yes", "1", true)
        val prompt = Prompt("Continue?", listOf(choice))
        try {
            try {
                vm.answerChoice(paneId, prompt, choice) // The card supplies no feedback callback.
                assertEquals("Another input is already being sent to this pane.", vm.notice)
                assertFalse(vm.answerInFlight(paneId))
                assertEquals(0, vm.queuedCount)
                assertNull(dispatch.tryAcquire(key))
            } finally {
                release()
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Another input is already being sent to this pane.", vm.notice)
            assertFalse(vm.answerInFlight(paneId))
            dispatch.tryAcquire(key)!!.invoke()
        } finally {
            vm.clearForTest()
        }
    }

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(vm: AppViewModel, name: String) = field("$name\$delegate").get(vm) as MutableState<T>
}
