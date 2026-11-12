package com.billmongan.herdrdroid.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.BuildConfig
import com.billmongan.herdrdroid.Link
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.QuotaItem
import com.billmongan.herdrdroid.herdr.QuotaReading
import com.billmongan.herdrdroid.herdr.ago
import com.billmongan.herdrdroid.herdr.shortLabel

@Composable
fun SettingsScreen(vm: AppViewModel, back: Screen) {
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.changeWatching(granted) }
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Surface(color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.screen = back }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.Ink)
                }
                Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Section("Appearance") {
                ThemeMode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setTheme(m) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = vm.themeMode == m, onClick = { vm.setTheme(m) })
                        Text(m.label, color = Palette.Ink, fontSize = 16.sp)
                    }
                }
            }
            Section("Dark palette") {
                DarkPalette.entries.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.chooseDarkPalette(p) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = vm.darkPalette == p, onClick = { vm.chooseDarkPalette(p) })
                        Text(p.label, color = Palette.Ink, fontSize = 16.sp)
                    }
                }
                Text("Used whenever the app is dark (the phone's dark mode, or Dark above).", color = Palette.Muted, fontSize = 13.sp)
            }
            Section("Alerts") {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (vm.watching) vm.changeWatching(false)
                        else if (android.os.Build.VERSION.SDK_INT >= 33) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else vm.changeWatching(true)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Tell me when an agent needs me", color = Palette.Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vm.watching, onCheckedChange = { on ->
                        if (!on) vm.changeWatching(false)
                        else if (android.os.Build.VERSION.SDK_INT >= 33) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else vm.changeWatching(true)
                    })
                }
                Text(
                    "While connected, the app keeps watching in the background (a quiet \"Watching\" notification shows) " +
                        "and alerts you when an agent starts waiting on you, with its first two choices and Esc as buttons. A " +
                        "button answers only if the same question is still on screen. Agents are checked every 3 seconds. " +
                        "Alerts are made on the phone from what it reads over SSH; nothing is set up or left running on the " +
                        "computer. Watching uses some battery.",
                    color = Palette.Muted, fontSize = 13.sp,
                )
                Row(Modifier.fillMaxWidth().clickable { vm.changeAlertDone(!vm.alertDone) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Also when an agent finishes", color = Palette.Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vm.alertDone, onCheckedChange = { vm.changeAlertDone(it) })
                }
                Row(Modifier.fillMaxWidth().clickable { vm.changeWatchOthers(!vm.watchOthers) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Watch my other servers too", color = Palette.Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vm.watchOthers, onCheckedChange = { vm.changeWatchOthers(it) })
                }
                Text(
                    "Servers that sign in without asking (a key or a saved password; fingerprint-locked ones once " +
                        "unlocked) are checked every 5 seconds with alerts on (15 without), and their agents are listed " +
                        "under the ones on this server. A server reached over a WebSocket is watched only while the " +
                        "app is open, so it can sleep when you're away.",
                    color = Palette.Muted, fontSize = 13.sp,
                )
            }
            Section("Diagnostics") { Diagnostics() }
            Section("Helpers") {
                Row(Modifier.fillMaxWidth().clickable { vm.changeAutoCloseHelpers(!vm.autoCloseHelpers) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Close helpers when they finish", color = Palette.Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vm.autoCloseHelpers, onCheckedChange = { vm.changeAutoCloseHelpers(it) })
                }
                Text(
                    "A helper started from Hand out with a task closes its tab once it has worked and gone quiet. Its last " +
                        "reply is kept under \"Helper results\" at the top of the tab list, to copy or send on. A helper " +
                        "that asks you something stays open.",
                    color = Palette.Muted, fontSize = 13.sp,
                )
            }
            Section("Reading font") {
                ReadingFont.entries.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.chooseReadingFont(f) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = vm.readingFont == f, onClick = { vm.chooseReadingFont(f) })
                        Column {
                            Text(f.label, color = Palette.Ink, fontSize = 16.sp)
                            Text(
                                "Done. All 16 tests pass, and the APK is ready.",
                                color = Palette.Muted, fontSize = 14.sp, fontFamily = f.family,
                            )
                        }
                    }
                }
                Text(
                    "Used for the agent's replies. Tables, code and the full terminal always use the monospace font.",
                    color = Palette.Muted, fontSize = 13.sp,
                )
            }
            Section("Usage limits") {
                Text(
                    "Every card shows its agent's limits, always. The app keeps them up to date by itself (Claude every 10 " +
                        "minutes, Grok and Antigravity every 15, the rest every 30, and right away when a reading is due), " +
                        "using the agent's own usage command (Claude Code, Codex, Copilot, Grok, Antigravity, Hermes, Oh My Pi). " +
                        "None of these uses a model call. It only asks an agent that's idle, prefers a pane that isn't in " +
                        "front at the computer, and never presses Enter if something is already typed there.",
                    color = Palette.Muted, fontSize = 13.sp,
                )
            }
            Text("HerdrDroid ${BuildConfig.VERSION_NAME}", color = Palette.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.Ink)
            content()
        }
    }
}

/** "Connected", "Reconnecting…" or "Offline", with a dot. Reconnecting is normal and not alarming. */
@Composable
fun LinkLabel(vm: AppViewModel, extra: String) {
    val (dot, text) = when (vm.link) {
        Link.CONNECTED -> Palette.Green to "Connected"
        Link.RECONNECTING -> Palette.Muted to "Reconnecting…"
        Link.OFFLINE -> Palette.Orange to "Offline"
    }
    val waiting = if (vm.queuedCount > 0) " · ${vm.queuedCount} waiting to send" else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(
            text + extra + waiting,
            color = Palette.Muted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** Banners for a server screen: offline after retrying for a while, or a problem to act on. */
@Composable
fun ServerBanners(vm: AppViewModel) {
    if (vm.link == Link.OFFLINE) {
        ErrorBanner(
            "Can't reach ${vm.current?.label ?: "the computer"}. " + (vm.linkProblem ?: "") +
                if (vm.queuedCount > 0) " What you sent will go out when it's back." else "",
            actionLabel = "Retry",
            // Stops the reconnect tries and goes back to the server list.
            secondLabel = "Disconnect", onSecond = { vm.disconnect() },
        ) { vm.retryNow() }
    }
    vm.error?.let { ErrorBanner(it) { vm.error = null } }
}

/**
 * The tightest limit on an agent's card, in two short lines: a short name, a bar and the percent
 * used, then when it resets and how old the reading is.
 */
@Composable
fun QuotaLine(r: QuotaReading, modifier: Modifier = Modifier) {
    if (r.items.isEmpty()) return
    // Every limit the agent reports (the 5-hour window, the weekly one, a weekly per-model one...),
    // one row each in the agent's own order, then how old the reading is.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        r.items.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    shortLabel(t.label),
                    color = Palette.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.1f),
                )
                Box(Modifier.weight(1f)) { QuotaBar(t) }
                Text("${t.usedPercent.fmt()}%", color = Palette.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                t.resets?.let {
                    Text(it, color = Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.9f))
                }
            }
        }
        Text(ago(r.at), color = Palette.Muted, fontSize = 11.5.sp, maxLines = 1)
    }
}

@Composable
fun QuotaBar(item: QuotaItem) {
    val frac = (item.usedPercent / 100.0).toFloat().coerceIn(0f, 1f)
    val color = when {
        item.usedPercent >= 90 -> Palette.OrangeInk
        item.usedPercent >= 70 -> Palette.Orange
        else -> Palette.Blue
    }
    LinearProgressIndicator(
        progress = { frac },
        color = color,
        trackColor = Palette.Gray,
        drawStopIndicator = {},
        modifier = Modifier.fillMaxWidth().height(4.dp),
    )
}

/** Every limit for one agent, for the agent screen. */
@Composable
fun QuotaDetails(r: QuotaReading, onClose: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usage limits · ${ago(r.at)}", fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Hide") }
            }
            r.items.forEach { i ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row {
                        Text(i.label, color = Palette.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("${i.usedPercent.fmt()}% used", color = Palette.Muted, fontSize = 13.sp)
                    }
                    QuotaBar(i)
                    i.resets?.let { Text("Resets $it", color = Palette.Muted, fontSize = 12.sp) }
                }
            }
        }
    }
}

private fun Double.fmt(): String = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)

/**
 * The diagnostic log (connections, the WebSocket bridge, SSH steps), with Copy, Save and Clear.
 * It holds no addresses of WebSocket servers, keys, passwords or file contents.
 */
@Composable
private fun Diagnostics() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by remember { mutableStateOf(com.billmongan.herdrdroid.diag.DiagLog.text()) }
    var note by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var seen = -1L
        while (true) {
            val v = com.billmongan.herdrdroid.diag.DiagLog.version
            if (v != seen) { seen = v; text = com.billmongan.herdrdroid.diag.DiagLog.text() }
            kotlinx.coroutines.delay(1_000)
        }
    }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            note = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                "Saved."
            }.getOrElse { "Couldn't save: ${it.message}" }
        }
    }
    Text(
        "What the app did while connecting: the WebSocket bridge's steps and byte counts, and each SSH step. " +
            "It has no server addresses, keys or passwords, so it's safe to copy and send. Kept in memory only " +
            "(the last ${com.billmongan.herdrdroid.diag.DiagLog.MAX} lines) until the app closes.",
        color = Palette.Muted, fontSize = 13.sp,
    )
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedButton(enabled = text.isNotEmpty(), onClick = {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)); note = "Copied."
        }) { Text("Copy") }
        androidx.compose.material3.OutlinedButton(enabled = text.isNotEmpty(), onClick = {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            save.launch("herdrdroid-diagnostics-$stamp.txt")
        }) { Text("Save…") }
        androidx.compose.material3.OutlinedButton(enabled = text.isNotEmpty(), onClick = {
            com.billmongan.herdrdroid.diag.DiagLog.clear(); note = null
        }) { Text("Clear") }
    }
    note?.let { Text(it, color = Palette.Blue, fontSize = 13.sp) }
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text.ifEmpty { "Nothing yet. Connect to a server, then come back here." }.lines().takeLast(200).joinToString("\n"),
            fontFamily = Fonts.Mono, fontSize = 11.sp, lineHeight = 15.sp, color = Palette.Ink,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                .background(Palette.Gray, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(10.dp),
        )
    }
}
