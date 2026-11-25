package com.billmongan.herdrdroid

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billmongan.herdrdroid.herdr.Herdr
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.KeySource
import com.billmongan.herdrdroid.store.Server
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Opt-in, loopback-only SSH to a synthetic CLI, with no real terminal behind it. */
@RunWith(AndroidJUnit4::class)
class UsageRecoveryTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()
    @Test fun preExistingVisibleUsageRequiresOneExplicitTapAndRestoresTheDraft() = runBlocking {
        val port = System.getenv("HD_USAGE_FIXTURE_PORT")?.toIntOrNull()
        assumeTrue("no synthetic usage recovery fixture", port != null)
        val dir = File(checkNotNull(System.getenv("HD_USAGE_FIXTURE_DIR")))
        val server = Server(id = "test", name = "Test", host = "127.0.0.1", port = port!!,
            user = "dev", herdrPath = "/fixture/herdr")
        val ssh = HerdrSsh(server.host, server.port, server.user,
            Credentials(KeySource.Imported(File(dir, "client").readText(), null), null),
            File(dir, "pin").readText().trim(), { null })
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val pane = Pane("w1:p1", "w1:t1", "w1", "claude", Status.IDLE, "", "/fixture", scrollOffset = 0)
        val draft = "❯ synthetic existing draft"
        suspend fun fixture(command: String) = ssh.execNow("/fixture/herdr $command").let {
            assertEquals(it.err, 0, it.exit)
            it.out.trim()
        }
        fun state(name: String, value: Any?) {
            val field = AppViewModel::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(vm) as MutableState<Any?>).value = value
        }
        fun awaitDismissal() {
            val looper = shadowOf(Looper.getMainLooper())
            val deadline = System.nanoTime() + 10_000_000_000L
            while (vm.usageDismissInFlight(pane.id) && System.nanoTime() < deadline) {
                looper.idleFor(Duration.ofMillis(25))
                Thread.sleep(2)
            }
            assertFalse("dismissal timed out", vm.usageDismissInFlight(pane.id))
        }
        try {
            fixture("reset")
            AppViewModel::class.java.getDeclaredField("herdr").apply { isAccessible = true }
                .set(vm, Herdr(ssh, server.herdrPath))
            state("current", server)
            state("panes", listOf(pane))
            state("locked", false)
            vm.screen = Screen.Agent(pane.id)

            vm.readPane(pane.id)
            val shown = vm.usageOverlayFor(pane.id)
            assertNotNull("live modal was missing from the recent capture", shown)
            assertEquals("", fixture("keys"))

            // Same pane ID, but an approval is now visible. In particular, do not send Escape.
            fixture("approval")
            vm.dismissUsage(shown!!)
            awaitDismissal()
            assertNull(vm.usageOverlayFor(pane.id))
            assertEquals("", fixture("keys"))

            fixture("reset")
            vm.readPane(pane.id)
            val current = checkNotNull(vm.usageOverlayFor(pane.id))
            assertEquals("", fixture("keys"))
            vm.dismissUsage(current)
            vm.dismissUsage(current)
            awaitDismissal()
            assertEquals("esc", fixture("keys"))
            assertNull(vm.usageOverlayFor(pane.id))
            assertEquals(draft, vm.paneText.trim())
            assertEquals(draft, fixture("draft"))
            assertEquals(0, vm.queuedCount)
            vm.dismissUsage(current)
            assertEquals("esc", fixture("keys"))
        } finally {
            vm.clearForTest()
            ssh.close()
        }
    }
}
