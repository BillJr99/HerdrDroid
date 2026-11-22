package com.billmongan.herdrdroid

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AgentMessagingLifecycleTest {
    @get:Rule val idleMainLooper = IdleMainLooperRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val models = mutableListOf<AppViewModel>()

    private fun model(adapter: Adapter) = AppViewModel(app, adapter::run).also { models += it }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After fun cleanUp() {
        models.forEach { it.viewModelScope.cancel() }
        idle()
    }

    @Test fun cleanInstallAndUpgradeDoNotStartAnyAgentMessagingWork() {
        val adapter = Adapter()
        repeat(2) { version ->
            if (version == 1) prefs.edit().putBoolean("watching", false).putString("theme", "DARK").commit()
            val vm = model(adapter)
            assertFalse(vm.agentMessagingEnabled)
            vm.onForeground()
            vm.onBackground()
            vm.onForeground()
            idle()
            assertTrue(adapter.effects.isEmpty())
        }
        assertFalse(prefs.contains("agentMessagingEnabled"))
    }

    @Test fun savedOptInStartsOnlyWhenForegroundAndUnlockedAfterRecreation() {
        val adapter = Adapter()
        val first = model(adapter)
        first.changeAgentMessaging(true)
        first.changeAppLock(true)
        first.viewModelScope.cancel()
        val next = model(adapter)
        assertTrue(next.agentMessagingEnabled)
        assertTrue(next.locked)
        assertTrue(adapter.effects.isEmpty())
        next.onForeground()
        idle()
        assertTrue(adapter.effects.isEmpty())
        next.unlocked()
        idle()
        assertEquals(Adapter.startEffects, adapter.effects)
        next.onBackground()
        assertFalse(adapter.session!!.isActive)
        idle()
        assertEquals(Adapter.startEffects + "stop", adapter.effects)
        next.onForeground()
        next.onForeground()
        idle()
        assertEquals(Adapter.startEffects + "stop" + Adapter.startEffects, adapter.effects)
        next.viewModelScope.cancel()
        assertFalse(adapter.session!!.isActive)
    }

    @Test fun disablingCancelsOutstandingWorkAndReenableWaitsForCleanupWithoutReplay() {
        val adapter = Adapter()
        val vm = model(adapter)
        vm.onForeground()
        vm.changeAgentMessaging(true)
        idle()
        val original = checkNotNull(adapter.session)
        val validated = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        try {
            adapter.finishCleanup = cleanup
            val request = checkNotNull(adapter.scope).launch {
                adapter.effects += "validating"
                withContext(NonCancellable) { validated.await() }
                ensureActive()
                adapter.effects += "submit"
            }
            idle()
            assertEquals(Adapter.startEffects + "validating", adapter.effects)
            vm.changeAgentMessaging(false)
            assertFalse(original.isActive)
            assertTrue(request.isCancelled)
            assertFalse(prefs.getBoolean("agentMessagingEnabled", true))
            vm.changeAgentMessaging(true)
            vm.changeAgentMessaging(false)
            vm.changeAgentMessaging(true)
            idle()
            assertEquals(Adapter.startEffects + "validating", adapter.effects)
            validated.complete(Unit)
            idle()
            assertEquals(Adapter.startEffects + "validating", adapter.effects)
            cleanup.complete(Unit)
            idle()
            assertEquals(Adapter.startEffects + "validating" + "stop" + Adapter.startEffects, adapter.effects)
            assertNotSame(original, adapter.session)
            assertFalse(original.isActive)
        } finally {
            validated.complete(Unit)
            cleanup.complete(Unit)
        }
    }

    private class Adapter {
        val effects = mutableListOf<String>()
        var scope: CoroutineScope? = null
        val session get() = scope?.coroutineContext?.get(Job)
        var finishCleanup: CompletableDeferred<Unit>? = null

        suspend fun run(scope: CoroutineScope) {
            this.scope = scope
            effects += startEffects
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { finishCleanup?.await() }
                effects += "stop"
            }
        }

        companion object {
            val startEffects = listOf("start", "listen", "advertise", "poll")
        }
    }
}
