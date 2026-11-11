package com.billmongan.herdrdroid

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.billmongan.herdrdroid.store.Server
import com.billmongan.herdrdroid.ui.AgentScreen
import com.billmongan.herdrdroid.ui.EditServerScreen
import com.billmongan.herdrdroid.ui.HerdrTheme
import com.billmongan.herdrdroid.ui.ServersScreen
import com.billmongan.herdrdroid.ui.TabsScreen

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HerdrTheme {
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
                }
            }
        }
    }

    /** Asks for a fingerprint (or the phone's screen lock) before a saved password is used. */
    private fun unlock(server: Server, done: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    vm.error = errString.toString()
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
