package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.herdr.Status

object Palette {
    val Ground = Color(0xFFF5F6F8)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF15171C)
    val Muted = Color(0xFF4A5160)
    val Line = Color(0xFFE3E6EB)
    val Field = Color(0xFFC9CFD8)
    val Blue = Color(0xFF2457C5)
    val BlueSoft = Color(0xFFEDF3FE)
    val BlueTile = Color(0xFFE3ECFD)
    val Orange = Color(0xFFE8833A)
    val OrangeInk = Color(0xFF9A3412)
    val OrangeSoft = Color(0xFFFFF3EA)
    val OrangeTile = Color(0xFFFFE7D6)
    val Green = Color(0xFF1F7A4D)
    val GreenSoft = Color(0xFFE6F4EC)
    val Gray = Color(0xFFEEF0F3)
    val Terminal = Color(0xFF14161B)
    val TerminalInk = Color(0xFFD9DEE7)
}

private val Colors = lightColorScheme(
    primary = Palette.Blue,
    onPrimary = Color.White,
    background = Palette.Ground,
    onBackground = Palette.Ink,
    surface = Palette.Card,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Gray,
    onSurfaceVariant = Palette.Muted,
    outline = Palette.Field,
    error = Palette.OrangeInk,
)

@Composable
fun HerdrTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)

data class StatusLook(val label: String, val ink: Color, val bg: Color, val tile: Color)

fun look(status: Status) = when (status) {
    Status.NEEDS_YOU -> StatusLook("Needs you", Palette.OrangeInk, Palette.OrangeSoft, Palette.OrangeTile)
    Status.WORKING -> StatusLook("Working", Palette.Blue, Palette.BlueSoft, Palette.BlueTile)
    Status.DONE -> StatusLook("Done", Palette.Green, Palette.GreenSoft, Palette.Gray)
    Status.IDLE -> StatusLook("Idle", Palette.Muted, Palette.Gray, Palette.Gray)
    Status.UNKNOWN -> StatusLook("Shell", Palette.Muted, Palette.Gray, Palette.Gray)
}

@Composable
fun StatusPill(status: Status) {
    val l = look(status)
    Text(
        l.label,
        color = l.ink,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(l.bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun AgentTile(initial: String, status: Status, size: Int = 44) {
    val l = look(status)
    Box(
        Modifier
            .size(size.dp)
            .background(l.tile, RoundedCornerShape((size / 3.6).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = if (status == Status.NEEDS_YOU || status == Status.WORKING) l.ink else Palette.Muted,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.4).sp,
        )
    }
}
