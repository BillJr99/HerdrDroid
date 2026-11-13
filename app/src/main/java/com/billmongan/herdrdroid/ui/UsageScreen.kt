package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.Pane
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.shortReset
import com.billmongan.herdrdroid.herdr.ago
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every agent's usage on the open server: each limit's latest percent, when it resets, and a chart
 * of the last week with lines at 80% and 95%.
 */
@Composable
fun UsageScreen(vm: AppViewModel) {
    val all = vm.usageOverview()
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Ink) }
            Text("Usage · ${vm.current?.label ?: ""}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        }
        HorizontalDivider(color = Palette.Line)
        if (all.isEmpty()) {
            Text("No usage read on this server yet. Readings appear here as the app checks each agent.",
                color = Palette.Muted, modifier = Modifier.padding(16.dp))
            return
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(6.dp)) }
            items(all, key = { it.first }) { (agent, r, hist) ->
                Surface(shape = RoundedCornerShape(14.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(Pane("", "", "", agent, Status.UNKNOWN, "", "").agentName + " · read " + ago(r.at),
                            color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        r.items.forEach { i ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row {
                                    Text(i.label, color = Palette.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    Text("${i.usedPercent.toInt()}% used", color = if (i.usedPercent >= 80) Palette.OrangeInk else Palette.Muted, fontSize = 13.sp)
                                }
                                i.resets?.let { Text("Resets ${shortReset(it)}", color = Palette.Muted, fontSize = 12.sp) }
                                val points = hist.mapNotNull { pt -> pt.used[i.label]?.let { pt.at to it } }
                                if (points.size >= 2) WeekChart(points) else Text("Not enough readings for a chart yet.", color = Palette.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Percent used over the last week: day marks below, dashed lines at 80% and 95%. */
@Composable
private fun WeekChart(points: List<Pair<Long, Double>>) {
    val line = Palette.Blue
    val fill = Palette.BlueSoft
    val grid = Palette.Line
    val warn = Palette.Orange
    val t1 = points.last().first
    val t0 = maxOf(points.first().first, t1 - 7L * 86_400_000)
    val shown = points.filter { it.first >= t0 }
    val zone = ZoneId.systemDefault()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width
            val h = size.height
            fun x(t: Long) = (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1) * w
            fun y(v: Double) = h - (v.coerceIn(0.0, 100.0) / 100.0).toFloat() * h
            listOf(0.0, 50.0, 100.0).forEach { g -> drawLine(grid, Offset(0f, y(g)), Offset(w, y(g)), 1f) }
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            listOf(80.0, 95.0).forEach { g -> drawLine(warn, Offset(0f, y(g)), Offset(w, y(g)), 2f, pathEffect = dash) }
            // A faint mark at each midnight.
            var day = Instant.ofEpochMilli(t0).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            while (day < t1) { drawLine(grid, Offset(x(day), 0f), Offset(x(day), h), 1f); day += 86_400_000 }
            if (shown.size >= 2) {
                val path = Path()
                shown.forEachIndexed { k, (t, v) -> if (k == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
                val area = Path().apply { addPath(path); lineTo(x(shown.last().first), h); lineTo(x(shown.first().first), h); close() }
                drawPath(area, fill)
                drawPath(path, line, style = Stroke(width = 3f))
                drawCircle(line, 6f, Offset(x(shown.last().first), y(shown.last().second)))
            }
        }
        val fmt = DateTimeFormatter.ofPattern("EEE M/d", Locale.US)
        Row(Modifier.fillMaxWidth()) {
            Text(Instant.ofEpochMilli(t0).atZone(zone).format(fmt), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("80% and 95% dashed", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("now", color = Palette.Muted, fontSize = 11.sp)
        }
    }
}
