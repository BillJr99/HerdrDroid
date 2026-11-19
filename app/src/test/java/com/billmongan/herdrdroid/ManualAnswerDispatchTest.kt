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
import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.store.Server
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ManualAnswerDispatchTest {
    @Test fun occupiedPaneShowsFailureForAnOrdinaryCardTapWithoutQueueing() {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val server = Server(id = "test", name = "Test", host = "unused.invalid", port = -1, user = "test", herdrPath = "herdr")
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
            vm.viewModelScope.cancel()
        }
    }

    private fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
}
