package com.billmongan.herdrdroid.ui

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
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setQuotasShown(!vm.showQuotas) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Show usage limits", color = Palette.Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vm.showQuotas, onCheckedChange = { vm.setQuotasShown(it) })
                }
                Text(
                    if (vm.showQuotas) {
                        "The app keeps each agent's limits up to date by itself, about every 30 minutes, using the " +
                            "agent's own usage command (Claude Code, Codex, Copilot, Grok, Antigravity and Hermes). " +
                            "None of these uses a model call. It only asks an agent that's idle, prefers a pane " +
                            "that isn't in front at the computer, and never presses Enter if something is already " +
                            "typed there. Agents with no usage command are left alone."
                    } else {
                        "Off. The app never asks any agent about usage."
                    },
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
    val t = r.tightest ?: return
    val more = r.items.size - 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                shortLabel(t.label),
                color = Palette.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(Modifier.weight(1f)) { QuotaBar(t) }
            Text("${t.usedPercent.fmt()}%", color = Palette.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            listOfNotNull(t.resets?.let { "resets $it" }, ago(r.at), if (more > 0) "+$more more" else null).joinToString(" · "),
            color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
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
