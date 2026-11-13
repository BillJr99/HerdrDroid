package com.billmongan.herdrdroid

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private var lockMessage by androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.billmongan.herdrdroid.notify.Notify.channels(this)
        // Only a fresh launch acts on the notification or share that started the app. A rebuilt
        // screen (theme change, rotation, back from the background or from Recents) gets that same
        // old intent again, which used to jump to the notification's agent long after.
        val fromHistory = (intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) openFrom(intent)
        // Straight to the last agent (or the server screen when there are no servers yet).
        if (!vm.locked) vm.autoOpen()
        setContent {
            val dark = when (vm.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            HerdrTheme(dark, vm.darkPalette, vm.lightPalette) {
                SideEffect {
                    window.statusBarColor = Palette.Card.toArgb()
                    window.navigationBarColor = Palette.Card.toArgb()
                    window.decorView.setBackgroundColor(Palette.Ground.toArgb())
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                // The app lock: nothing shows and no server is contacted until it's unlocked.
                if (vm.locked) {
                    LaunchedEffect(Unit) { unlock { ok -> if (ok) vm.unlocked() } }
                    LockScreen(lockMessage) { unlock { ok -> if (ok) vm.unlocked() } }
                    return@HerdrTheme
                }
                // A tablet, an unfolded phone or a wide window shows the list and the open agent
                // side by side; a phone shows one at a time.
                BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ground)) {
                val wide = maxWidth >= 840.dp
                val s0 = vm.screen
                if (wide && s0 is Screen.Agent) {
                    // Wide: the drawer stays out beside the agent.
                    Row(Modifier.fillMaxSize()) {
                        com.billmongan.herdrdroid.ui.SessionDrawer(vm)
                        VerticalDivider(color = Palette.Line)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            androidx.compose.runtime.key(s0.paneId) { AgentScreen(vm, s0.paneId) }
                        }
                    }
                } else if (wide && s0 == Screen.Tabs) {
                    BackHandler { vm.disconnect() }
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(0.42f).fillMaxHeight()) { TabsScreen(vm) }
                        VerticalDivider(color = Palette.Line)
                        Box(Modifier.weight(0.58f).fillMaxHeight()) {
                            if (s0 is Screen.Agent) {
                                androidx.compose.runtime.key(s0.paneId) { AgentScreen(vm, s0.paneId) }
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Choose an agent on the left.", color = Palette.Muted, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                } else when (val s = vm.screen) {
                    Screen.Servers -> ServersScreen(vm)
                    is Screen.EditServer -> {
                        BackHandler { vm.screen = Screen.Servers }
                        EditServerScreen(vm, s.server)
                    }
                    Screen.Tabs -> {
                        BackHandler { vm.disconnect() }
                        TabsScreen(vm)
                    }
                    is Screen.Agent -> {
                        // The drawer is the way around: Back closes it, or opens it.
                        val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
                        LaunchedEffect(vm.drawerOpen) { if (vm.drawerOpen) drawer.open() else drawer.close() }
                        LaunchedEffect(drawer) {
                            androidx.compose.runtime.snapshotFlow { drawer.currentValue }.collect { vm.drawerOpen = it == androidx.compose.material3.DrawerValue.Open }
                        }
                        BackHandler { vm.drawerOpen = !vm.drawerOpen }
                        androidx.compose.material3.ModalNavigationDrawer(
                            drawerState = drawer,
                            drawerContent = { androidx.compose.material3.ModalDrawerSheet(drawerContainerColor = Palette.Ground) { com.billmongan.herdrdroid.ui.SessionDrawer(vm) } },
                        ) {
                            AgentScreen(vm, s.paneId)
                        }
                    }
                    Screen.Usage -> {
                        BackHandler { vm.goHome() }
                        com.billmongan.herdrdroid.ui.UsageScreen(vm)
                    }
                    is Screen.Files -> {
                        BackHandler { vm.screen = s.back }
                        com.billmongan.herdrdroid.ui.FilesScreen(vm, s.path, s.back)
                    }
                    is Screen.Settings -> {
                        BackHandler { vm.screen = s.back }
                        SettingsScreen(vm, s.back)
                    }
                    Screen.Search -> {
                        BackHandler { vm.goHome() }
                        com.billmongan.herdrdroid.ui.SearchScreen(vm)
                    }
                    is Screen.Work -> {
                        BackHandler { vm.screen = if (s.kind != null) Screen.Work(s.paneId) else Screen.Agent(s.paneId) }
                        com.billmongan.herdrdroid.ui.WorkScreen(vm, s)
                    }
                    is Screen.History -> {
                        BackHandler { vm.screen = if (s.session != null) Screen.History(s.paneId) else Screen.Agent(s.paneId) }
                        com.billmongan.herdrdroid.ui.HistoryScreen(vm, s.paneId, s.session)
                    }
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
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            vm.receiveShare(intent.getStringExtra(android.content.Intent.EXTRA_TEXT), uri)
            intent.action = null
            return
        }
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

    /** Asks for a fingerprint (or the phone's screen lock) when the app opens, if the app lock is on. */
    private fun unlock(done: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Backing out of the prompt is not an error worth a banner.
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    if (!cancelled) lockMessage = errString.toString()
                    done(false)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock HerdrDroid")
            .setSubtitle("Your servers' saved passwords and keys")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}

@androidx.compose.runtime.Composable
private fun LockScreen(message: String?, onUnlock: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(Palette.Ground).padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("HerdrDroid is locked", color = Palette.Ink, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Unlock to see your servers and agents.", color = Palette.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        androidx.compose.material3.Button(onClick = onUnlock) { Text("Unlock") }
        message?.let { Text(it, color = Palette.OrangeInk, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
    }
}
