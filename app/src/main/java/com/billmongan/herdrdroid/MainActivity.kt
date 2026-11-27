package com.billmongan.herdrdroid

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.billmongan.herdrdroid.voice.HandsFreeService
import com.billmongan.herdrdroid.voice.LocalVoiceUi
import com.billmongan.herdrdroid.voice.VoiceControls
import com.billmongan.herdrdroid.voice.VoiceUi
import com.billmongan.herdrdroid.voice.VoiceRoute
import com.billmongan.herdrdroid.voice.voiceRoute
import kotlinx.coroutines.launch
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

    private val voicePrefs by lazy { getSharedPreferences("voice_control", MODE_PRIVATE) }
    private var voiceEnabled by mutableStateOf(false)
    private var selectedVoiceRoute by mutableStateOf(VoiceRoute.PHONE)

    private fun chooseVoiceRoute(route: VoiceRoute) {
        if (selectedVoiceRoute == route) return
        stopHandsFree()
        com.billmongan.herdrdroid.ui.Speaker.stop()
        voicePrefs.edit().putString("audio_route", route.name).apply()
        selectedVoiceRoute = route
    }
    private val voice by lazy { VoiceControls(applicationContext, vm) }
    private var handsFreeStartRequested = false
    private val command: (String) -> Unit = { dispatchVoice(it, handsFree = false) }
    private val handsFreeCommand: (String) -> Unit = { dispatchVoice(it, handsFree = true) }

    private fun dispatchVoice(text: String, handsFree: Boolean) {
        fun allowed() = voiceEnabled && !vm.locked && (!handsFree || HandsFreeService.active)
        if (allowed()) lifecycleScope.launch {
            if (allowed()) voice.handle(text)
        }
    }

    private fun mayStartHandsFree() = voiceEnabled && !vm.locked && !HandsFreeService.active &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private val voicePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (missingVoicePermissions().isEmpty()) startHandsFree()
        else android.widget.Toast.makeText(this, "Hands-free needs microphone access and a visible Stop notification.", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun missingVoicePermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun changeVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        voicePrefs.edit().putBoolean("enabled", voiceEnabled).apply()
        if (!voiceEnabled) stopHandsFree()
    }

    private fun stopHandsFree() {
        handsFreeStartRequested = false
        voice.clear()
        HandsFreeService.stop(this)
    }

    private fun requestHandsFree() {
        if (!mayStartHandsFree() || handsFreeStartRequested) return
        val missing = missingVoicePermissions()
        if (missing.isNotEmpty()) voicePermission.launch(missing) else startHandsFree()
    }

    private fun startHandsFree() {
        if (!mayStartHandsFree() || handsFreeStartRequested || missingVoicePermissions().isNotEmpty()) return
        voice.clear()
        handsFreeStartRequested = true
        runCatching { ContextCompat.startForegroundService(this, Intent(this, HandsFreeService::class.java)) }
            .onFailure {
                handsFreeStartRequested = false
                android.widget.Toast.makeText(this, "Could not start hands-free voice.", android.widget.Toast.LENGTH_LONG).show()
            }
    }

    override fun onDestroy() {
        if (HandsFreeService.command === handsFreeCommand) {
            stopHandsFree()
            HandsFreeService.command = null
            HandsFreeService.admitStart = null
            HandsFreeService.sessionEnded = null
            HandsFreeService.confirmationGrammar = null
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceEnabled = voicePrefs.getBoolean("enabled", false)
        selectedVoiceRoute = voiceRoute(this)
        // An activity may resume its own session, but a replacement must not inherit one.
        if (HandsFreeService.active) HandsFreeService.stop(this)
        HandsFreeService.command = handsFreeCommand
        HandsFreeService.admitStart = {
            val requested = handsFreeStartRequested
            handsFreeStartRequested = false
            requested && mayStartHandsFree() && missingVoicePermissions().isEmpty()
        }
        HandsFreeService.sessionEnded = {
            voice.clear()
            vm.onHandsFreeEnded()
        }
        val controls = voice
        HandsFreeService.confirmationGrammar = controls::confirmationGrammar
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
                LaunchedEffect(vm.locked) { if (vm.locked) stopHandsFree() }
                LaunchedEffect(HandsFreeService.active) { if (HandsFreeService.active) voice.announceReplies() }
                // The app lock: nothing shows and no server is contacted until it's unlocked.
                if (vm.locked) {
                    LaunchedEffect(Unit) { unlock { ok -> if (ok) vm.unlocked() } }
                    LockScreen(lockMessage) { unlock { ok -> if (ok) vm.unlocked() } }
                    return@HerdrTheme
                }
                // 2.1.4: check for updates at launch once unlocked, and its prompt over any screen.
                LaunchedEffect(Unit) { vm.updates.checkAtLaunch() }
                com.billmongan.herdrdroid.update.UpdatePrompt(vm.updates)
                // A tablet, an unfolded phone or a wide window shows the list and the open agent
                // side by side; a phone shows one at a time.
                LaunchedEffect(HandsFreeService.problem) {
                    HandsFreeService.problem?.let { android.widget.Toast.makeText(this@MainActivity, it, android.widget.Toast.LENGTH_LONG).show() }
                }
                CompositionLocalProvider(LocalVoiceUi provides VoiceUi(
                    voiceEnabled, ::changeVoiceEnabled, ::requestHandsFree, ::stopHandsFree, command, selectedVoiceRoute, ::chooseVoiceRoute,
                )) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ground)) {
                val wide = maxWidth >= 840.dp
                val s0 = vm.screen
                if (wide && (s0 is Screen.Agent || s0 == Screen.Home || s0 == Screen.Questions)) {
                    // Wide: the drawer stays out beside the agent.
                    // 2.2.7: Back on the wide main screen (agent or Home) stays in the app: the drawer is
                    // already out here, so there is nothing to open or close, and the app never closes from it.
                    com.billmongan.herdrdroid.ui.StayInApp()
                    Row(Modifier.fillMaxSize()) {
                        com.billmongan.herdrdroid.ui.SessionDrawer(vm)
                        VerticalDivider(color = Palette.Line)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (s0 is Screen.Agent) androidx.compose.runtime.key(vm.chatServerId, s0.paneId) { AgentScreen(vm, s0.paneId) }
                            else if (s0 == Screen.Questions) com.billmongan.herdrdroid.ui.QuestionsScreen(vm)
                            else com.billmongan.herdrdroid.ui.HomeScreen(vm)
                        }
                    }
                } else if (wide && s0 == Screen.Tabs) {
                    BackHandler { vm.disconnect() }
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(0.42f).fillMaxHeight()) { TabsScreen(vm) }
                        VerticalDivider(color = Palette.Line)
                        Box(Modifier.weight(0.58f).fillMaxHeight()) {
                            if (s0 is Screen.Agent) {
                                androidx.compose.runtime.key(vm.chatServerId, s0.paneId) { AgentScreen(vm, s0.paneId) }
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Choose an agent on the left.", color = Palette.Muted, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                } else if (vm.starting && vm.screen == Screen.Servers) {
                    // Startup is still finding a server that answers: not the server screen yet.
                    // 2.2.7: Back goes to the main screen (startup carries on), never out of the app.
                    BackHandler { vm.screen = Screen.Home }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.CircularProgressIndicator(color = Palette.Blue)
                            Text("Opening your agents…", color = Palette.Ink, fontSize = 17.sp, modifier = Modifier.padding(top = 16.dp))
                            vm.startingOn?.let { Text(it, color = Palette.Muted, fontSize = 14.sp) }
                            androidx.compose.material3.TextButton(onClick = { vm.screen = Screen.Servers; vm.cancelStarting() }) { Text("Servers") }
                        }
                    }
                } else when (val s = vm.screen) {
                    // Back on these two is handled inside them (2.1.3): the form closes to the
                    // list, and the list returns to where it was opened from while a server is open.
                    Screen.Servers -> ServersScreen(vm)
                    is Screen.EditServer -> EditServerScreen(vm, s.server, s.prefillHost, s.prefillName)
                    Screen.Tabs -> {
                        BackHandler { vm.disconnect() }
                        TabsScreen(vm)
                    }
                    is Screen.Agent, Screen.Home, Screen.Questions -> {
                        // The drawer is the way around: Back closes it, or opens it.
                        val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
                        LaunchedEffect(vm.drawerOpen) { if (vm.drawerOpen) drawer.open() else drawer.close() }
                        LaunchedEffect(drawer) {
                            androidx.compose.runtime.snapshotFlow { drawer.currentValue }.collect { vm.drawerOpen = it == androidx.compose.material3.DrawerValue.Open }
                        }
                        BackHandler { vm.drawerOpen = !vm.drawerOpen }
                        androidx.compose.material3.ModalNavigationDrawer(
                            drawerState = drawer,
                            drawerContent = {
                                androidx.compose.material3.ModalDrawerSheet(drawerContainerColor = Palette.Ground) {
                                    com.billmongan.herdrdroid.ui.SessionDrawer(
                                        vm,
                                        opening = drawer.targetValue == androidx.compose.material3.DrawerValue.Open,
                                    )
                                }
                            },
                        ) {
                            // Keyed by server (2.1.1): the same pane id on another server is another chat, so
                            // its composer text (saved per pane id) never carries over.
                            if (s is Screen.Agent) androidx.compose.runtime.key(vm.chatServerId) { AgentScreen(vm, s.paneId) }
                            // 2.2.8: the Questions view, the drawer still there.
                            else if (s == Screen.Questions) com.billmongan.herdrdroid.ui.QuestionsScreen(vm)
                            // 2.2.7: the main screen with no agent open, the drawer still there.
                            else com.billmongan.herdrdroid.ui.HomeScreen(vm)
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
        vm.onForeground(HandsFreeService.active && HandsFreeService.command === handsFreeCommand)
        // Back from Android's settings, an allowed update continues.
        vm.updates.onResume()
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
        // The saved secrets' keys (1.3.1) open only after a strong biometric or the screen lock, so the
        // prompt asks for one of those (Android 11 and later; before that, any fingerprint or the lock).
        val kinds = if (android.os.Build.VERSION.SDK_INT >= 30) androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock HerdrDroid")
            .setSubtitle("Your servers' saved passwords and keys, and the chats kept on this phone, for the next 4 hours")
            .setAllowedAuthenticators(kinds)
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
