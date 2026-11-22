package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Backend
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.ExecResult
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 2.1.4 (Bill, 2026-11-21: the drawer's yellow "waiting for you" badge lit late). Pulling the
 * drawer out reads the open server's list at once, so a Claude question herdr still calls working
 * shows its badge there without waiting for the next poll; staying open reads nothing extra.
 */
@RunWith(AndroidJUnit4::class)
class DrawerBadge214Test {
    private val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    private val server = Server(id = "fixture-d", name = "FixtureD", host = "d.invalid", port = 22, user = "fixd", herdrPath = "/fixture/herdr")
    private val question = checkNotNull(javaClass.getResource("/screens/claude/captured-2.1.329/single-question.txt")).readText()

    @After fun close() {
        vm.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun openingTheDrawerReadsTheListAndLightsTheBadge() {
        val fake = Fake()
        install(fake)
        fake.screen = "❯ \n"
        vm.drawerOpen = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Status.WORKING, badge())
        val lists = fake.lists
        vm.drawerOpen = false
        // The question appears; the next pull of the drawer shows it at once.
        fake.screen = question
        vm.drawerOpen = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(lists + 1, fake.lists)
        assertEquals(Status.NEEDS_YOU, badge())
        // Already open: no read of its own.
        vm.drawerOpen = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(lists + 1, fake.lists)
    }

    @Test fun openingTheDrawerBehindReadsNothing() {
        val fake = Fake()
        install(fake)
        vm.onBackground()
        vm.drawerOpen = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, fake.lists)
        assertTrue(vm.sessions().isEmpty())
    }

    private fun badge(): Status = vm.sessions().single { it.pane.id == "c1" }.status

    private fun install(fake: Fake) {
        field("herdr").set(vm, fake.client)
        state("current", server)
        field("lastWorkAt").setLong(vm, Long.MAX_VALUE)
        field("lastPreviewsAt").setLong(vm, Long.MAX_VALUE)
    }

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) { (field("$name\$delegate").get(vm) as MutableState<Any?>).value = value }

    private class Fake : Herdr.Commands {
        val ssh = HerdrSsh("fake.invalid", 22, "fake", Credentials(null, null), null, { null })
        val client = Herdr(ssh, "/fixture/herdr", Backend.TUI, null, this)
        override var connection: Any? = Any()
        @Volatile var screen = "❯ \n"
        @Volatile var lists = 0

        override suspend fun bulk(command: String): ExecResult = when {
            "'pane' 'list'" in command -> {
                lists++
                ExecResult(0, """{"result":{"panes":[{"pane_id":"c1","workspace_id":"w1","tab_id":"t1","agent":"claude","agent_status":"working","cwd":"/fixture","terminal_id":"term1","scroll":{"offset_from_bottom":0}}]}}
@@herdrdroid-tabs@@
{"result":{"tabs":[{"tab_id":"t1","label":"claude"}]}}""", "")
            }
            "@@herdrdroid@@" in command -> ExecResult(0, "@@herdrdroid@@c1\n$screen", "")
            else -> ExecResult(1, "", "not in the fake")
        }

        override suspend fun immediate(command: String, timeoutSeconds: Long, expectedConnection: Any?): ExecResult =
            ExecResult(1, "", "not in the fake")
    }
}
