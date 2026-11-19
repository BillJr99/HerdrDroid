package com.billmongan.herdrdroid

import android.app.Application
import android.app.KeyguardManager
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.billmongan.herdrdroid.store.AppPrefs
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandsFreeAppLockTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
    private lateinit var vm: AppViewModel

    @Before fun setUp() {
        keyguard.setIsDeviceSecure(false)
        AppPrefs(app).appLock = true
        vm = AppViewModel(app)
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
    }

    @Test fun launchStillRequiresAuthentication() {
        assertTrue(vm.locked)
        vm.onForeground(handsFreeActive = true)
        assertTrue(vm.locked)
    }

    @Test fun activeSessionDefersAppPromptUntilStopped() {
        vm.unlocked()
        vm.onBackground()
        requireVaultAuthentication()
        vm.onForeground(handsFreeActive = true)
        assertFalse(vm.locked)
        assertTrue(vm.appLock)

        vm.onHandsFreeEnded()
        assertTrue(vm.locked)
    }

    @Test fun sessionEndingInBackgroundRestoresTheNextForegroundCheck() {
        vm.unlocked()
        vm.onBackground()
        requireVaultAuthentication()
        vm.onHandsFreeEnded()
        vm.onForeground()
        assertTrue(vm.locked)
    }

    @Test fun stopDoesNotForceReauthenticationWhileVaultIsAvailable() {
        vm.unlocked()
        vm.onHandsFreeEnded()
        assertFalse(vm.locked)
        val recreated = AppViewModel(app)
        try {
            assertTrue(recreated.locked)
        } finally {
            recreated.viewModelScope.cancel()
        }
    }

    private fun requireVaultAuthentication() {
        // Robolectric cannot expire an Android Keystore auth token. A secure device with
        // unmigrated keys drives the same production vaultNeedsUnlock() branch instead.
        keyguard.setIsDeviceSecure(true)
    }
}
