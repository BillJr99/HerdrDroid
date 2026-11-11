package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.QuotaReading
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Filter(val label: String) { ALL("All"), NEEDS("Needs you"), WORKING("Working"), IDLE("Idle") }

private fun Filter.matches(p: Pane) = when (this) {
    Filter.ALL -> true
    Filter.NEEDS -> p.status == Status.NEEDS_YOU
    Filter.WORKING -> p.status == Status.WORKING
    Filter.IDLE -> p.status == Status.IDLE || p.status == Status.DONE || p.status == Status.UNKNOWN
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TabsScreen(vm: AppViewModel) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val scope = rememberCoroutineScope()

    // Panes every 3 seconds; the one-line previews every 9.
    LaunchedEffect(vm.current?.id) {
        var tick = 0
        while (true) {
            vm.refreshPanes()
            if (tick % 3 == 0) vm.refreshPreviews()
            tick++
            delay(3_000)
        }
    }

    val panes = vm.panes
    val names = panes.groupingBy { it.agentName }.eachCount()

    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Surface(color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(vm.current?.label ?: "", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    LinkLabel(vm, " · ${panes.size} tabs")
                }
                IconButton(onClick = { scope.launch { vm.refreshPanes(); vm.refreshPreviews() }; vm.refreshQuotasSoon() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Palette.Ink)
                }
                IconButton(onClick = { vm.screen = Screen.Settings(Screen.Tabs) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.Ink)
                }
                IconButton(onClick = { vm.disconnect() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Disconnect", tint = Palette.Ink)
                }
            }
        }
        ServerBanners(vm)
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEach { f ->
                val n = panes.count { f.matches(it) }
                val needs = f == Filter.NEEDS && n > 0
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text("${f.label} $n", fontWeight = FontWeight.SemiBold) },
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == f, borderColor = Palette.Line),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = if (needs) Palette.OrangeSoft else Palette.Card,
                        labelColor = if (needs) Palette.OrangeInk else Palette.Ink,
                        selectedContainerColor = Palette.Ink,
                        selectedLabelColor = Palette.Ground,
                    ),
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(panes.filter { filter.matches(it) }, key = { it.id }) { p ->
                val dup = (names[p.agentName] ?: 0) > 1
                PaneCard(p, vm.previews[p.id].orEmpty(), showFolder = dup, model = vm.models[p.id], quota = vm.quotaFor(p)) { vm.openPane(p.id) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PaneCard(p: Pane, preview: String, showFolder: Boolean, model: String?, quota: QuotaReading?, onClick: () -> Unit) {
    val needs = p.status == Status.NEEDS_YOU
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Palette.Card,
        border = if (needs) BorderStroke(2.dp, Palette.Orange) else BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp)) {
            AgentTile(p.initial, p.status)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (showFolder && p.folder.isNotBlank()) "${p.agentName} · ${p.folder}" else p.agentName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Palette.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusPill(p.status)
                }
                model?.let {
                    Text(
                        it,
                        color = Palette.Blue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    preview.ifBlank { p.id },
                    color = Palette.Preview,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                quota?.let { QuotaLine(it, Modifier.padding(top = 4.dp)) }
            }
        }
    }
}
