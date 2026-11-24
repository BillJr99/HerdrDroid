package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.2.7 (Codex's review of 5b66c71): a secret's path goes into the open message box only while
 * the server's connection and the pane's occupant are still the ones the save was checked on.
 */
@RunWith(AndroidJUnit4::class)
class SecretInsert227Test {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-insert", name = "Fixture", host = "127.0.0.1", port = 1, user = "u", herdrPath = "herdr")
    private val claude = Pane("p1", "tab-p1", "w1", "claude", Status.IDLE, "", "/fixture", terminalId = "t-p1", sessionId = "sess-A")

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class Quiet : Herdr.Commands {
        override val connection: Any? = Any()
        override suspend fun bulk(command: String) = ExecResult(1, "", "fixture")
        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?) = ExecResult(1, "", "fixture")
    }

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value
    }

    private fun connect(c: Quiet) = field("herdr").set(vm,
        Herdr(HerdrSsh("127.0.0.1", 1, "u", Credentials(null, null), null, { null }), "herdr", Backend.TUI, null, c))

    private fun waiting(connection: Any?) = state("secretInsert",
        AppViewModel.SecretInsert(server.id, "p1", "/home/u/.herdrdroid/secrets/x.txt", 7L, connection, claude))

    @Test fun thePathGoesInOnlyOnTheSameConnectionAndOccupant() {
        val first = Quiet()
        connect(first)
        state("current", server)
        state("link", Link.CONNECTED)
        state("panes", listOf(claude))
        waiting(first.connection)
        assertEquals(7L to "/home/u/.herdrdroid/secrets/x.txt", vm.secretInsertFor("p1"))
        assertNull("another pane's box", vm.secretInsertFor("p2"))

        state("panes", listOf(claude.copy(sessionId = "sess-B")))
        assertNull("another session in the pane", vm.secretInsertFor("p1"))
        state("panes", listOf(claude.copy(terminalId = "t-other")))
        assertNull("another terminal in the pane", vm.secretInsertFor("p1"))

        state("panes", listOf(claude))
        connect(Quiet())
        assertNull("a reconnect since the save", vm.secretInsertFor("p1"))
    }

    /**
     * Codex's review of 69943fc: the composer asks again as it inserts the path. A change between the
     * screen being drawn and that moment (a reconnect, another occupant) inserts nothing.
     */
    @Test fun aChangeBetweenDrawingAndInsertingInsertsNothing() {
        val first = Quiet()
        connect(first)
        state("current", server)
        state("link", Link.CONNECTED)
        state("panes", listOf(claude))
        waiting(first.connection)
        val drawn = checkNotNull(vm.secretInsertFor("p1")).first
        connect(Quiet())
        assertNull("reconnected before the insert", vm.takeSecretInsert("p1", drawn))
        assertNotNull("still waiting, never inserted", vm.secretInsert)
        connect(first)
        state("panes", listOf(claude.copy(sessionId = "sess-B")))
        assertNull("another session before the insert", vm.takeSecretInsert("p1", drawn))
        state("panes", listOf(claude))
        assertNull("another insert's id", vm.takeSecretInsert("p1", drawn + 1))
        assertEquals("/home/u/.herdrdroid/secrets/x.txt", vm.takeSecretInsert("p1", drawn))
        assertNull("taken once", vm.secretInsert)
        assertNull(vm.takeSecretInsert("p1", drawn))
    }
}
