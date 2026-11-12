package com.billmongan.herdrdroid

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.ui.AgentScreen
import com.billmongan.herdrdroid.ui.EditServerScreen
import com.billmongan.herdrdroid.ui.HerdrTheme
import com.billmongan.herdrdroid.ui.Palette
import com.billmongan.herdrdroid.ui.ServersScreen
import com.billmongan.herdrdroid.ui.SettingsScreen
import com.billmongan.herdrdroid.ui.TabsScreen
import com.billmongan.herdrdroid.ui.ThemeMode

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.billmongan.herdrdroid.notify.Notify.channels(this)
        openFrom(intent)
        setContent {
            val dark = when (vm.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            HerdrTheme(dark, vm.darkPalette) {
                SideEffect {
                    window.statusBarColor = Palette.Card.toArgb()
                    window.navigationBarColor = Palette.Card.toArgb()
                    window.decorView.setBackgroundColor(Palette.Ground.toArgb())
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                when (val s = vm.screen) {
                    Screen.Servers -> ServersScreen(vm, ::unlock)
                    is Screen.EditServer -> {
                        BackHandler { vm.screen = Screen.Servers }
                        EditServerScreen(vm, s.server)
                    }
                    Screen.Tabs -> {
                        BackHandler { vm.disconnect() }
                        TabsScreen(vm)
                    }
                    is Screen.Agent -> {
                        BackHandler { vm.screen = Screen.Tabs }
                        AgentScreen(vm, s.paneId)
                    }
                    is Screen.Files -> {
                        BackHandler { vm.screen = s.back }
                        com.billmongan.herdrdroid.ui.FilesScreen(vm, s.path, s.back)
                    }
                    Screen.Dispatch -> {
                        BackHandler { vm.screen = Screen.Tabs }
                        com.billmongan.herdrdroid.ui.DispatchScreen(vm)
                    }
                    Screen.Compare -> {
                        BackHandler { vm.screen = Screen.Tabs }
                        com.billmongan.herdrdroid.ui.CompareScreen(vm)
                    }
                    is Screen.Settings -> {
                        BackHandler { vm.screen = s.back }
                        SettingsScreen(vm, s.back)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A tapped alert carries the server and pane to open. */
    private fun openFrom(intent: android.content.Intent?) {
        val server = intent?.getStringExtra(com.billmongan.herdrdroid.notify.Notify.EXTRA_SERVER) ?: return
        val pane = intent.getStringExtra(com.billmongan.herdrdroid.notify.Notify.EXTRA_PANE) ?: return
        intent.removeExtra(com.billmongan.herdrdroid.notify.Notify.EXTRA_SERVER)
        vm.openFromNotification(server, pane)
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    /** Asks for a fingerprint (or the phone's screen lock) before a saved password or key is used. */
    private fun unlock(server: Server, done: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Backing out of the prompt is not an error worth a banner.
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    if (!cancelled) vm.error = errString.toString()
                    done(false)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock ${server.label}")
            .setSubtitle("${server.user}@${server.host}")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}
