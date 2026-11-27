package com.billmongan.herdrdroid.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.herdr.PaneQuotaReading
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun PaneQuotaText(reading: PaneQuotaReading) {
    val now by produceState(Instant.now(), reading) {
        while (true) {
            value = Instant.now()
            delay(30_000)
        }
    }
    Text(
        paneQuotaLabel(reading, now), color = Palette.Muted, fontSize = 12.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

internal fun paneQuotaLabel(reading: PaneQuotaReading, now: Instant): String {
    val minutes = ((now.epochSecond - reading.observedAt.epochSecond) / 60).coerceAtLeast(0)
    val age = when {
        minutes == 0L -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1_440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1_440}d ago"
    }
    val percent = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    return "Quota ${percent.format(reading.usedPercent)}% · $age"
}
