package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.herdr.Status

/**
 * The app's colors. [dark] is set once per theme change by [HerdrTheme], which rebuilds the
 * screen with key(dark), so every color below is read again in the new theme.
 */
object Palette {
    var dark = false
        internal set

    private fun pick(light: Long, night: Long) = Color(if (dark) night else light)

    val Ground get() = pick(0xFFF5F6F8, 0xFF0F1115)
    val Card get() = pick(0xFFFFFFFF, 0xFF181B21)
    val Ink get() = pick(0xFF15171C, 0xFFE8EBF0)
    val Muted get() = pick(0xFF4A5160, 0xFFA4ACBA)
    val Line get() = pick(0xFFE3E6EB, 0xFF2A2F38)
    val Field get() = pick(0xFFC9CFD8, 0xFF3A404C)
    val Blue get() = pick(0xFF2457C5, 0xFF6C9BFF)
    val OnBlue get() = pick(0xFFFFFFFF, 0xFF0B1630)
    val BlueSoft get() = pick(0xFFEDF3FE, 0xFF1A2540)
    val BlueTile get() = pick(0xFFE3ECFD, 0xFF1F2C4D)
    val Orange get() = pick(0xFFE8833A, 0xFFF0A060)
    val OrangeInk get() = pick(0xFF9A3412, 0xFFFFB27A)
    val OrangeSoft get() = pick(0xFFFFF3EA, 0xFF2E1F14)
    val OrangeTile get() = pick(0xFFFFE7D6, 0xFF3A2616)
    val OrangeEdge get() = pick(0xFFF2C9A8, 0xFF5C3A20)
    val Green get() = pick(0xFF1F7A4D, 0xFF5CCB8F)
    val GreenSoft get() = pick(0xFFE6F4EC, 0xFF15291F)
    val Gray get() = pick(0xFFEEF0F3, 0xFF22262E)
    val Key get() = pick(0xFFF7F8FA, 0xFF20242B)
    val KeyEdge get() = pick(0xFFD5DAE2, 0xFF353B46)
    val Preview get() = pick(0xFF3A404C, 0xFFC3C9D4)
    val Terminal get() = pick(0xFF14161B, 0xFF08090C)
    val TerminalInk get() = pick(0xFFD9DEE7, 0xFFD9DEE7)
    val Code get() = pick(0xFFF3F5F8, 0xFF111419)
}

enum class ThemeMode(val label: String) { SYSTEM("Match phone"), LIGHT("Light"), DARK("Dark") }

@Composable
fun HerdrTheme(dark: Boolean, content: @Composable () -> Unit) {
    Palette.dark = dark
    key(dark) {
        val colors = if (dark) {
            darkColorScheme(
                primary = Palette.Blue, onPrimary = Palette.OnBlue,
                background = Palette.Ground, onBackground = Palette.Ink,
                surface = Palette.Card, onSurface = Palette.Ink,
                surfaceVariant = Palette.Gray, onSurfaceVariant = Palette.Muted,
                outline = Palette.Field, error = Palette.OrangeInk,
            )
        } else {
            lightColorScheme(
                primary = Palette.Blue, onPrimary = Palette.OnBlue,
                background = Palette.Ground, onBackground = Palette.Ink,
                surface = Palette.Card, onSurface = Palette.Ink,
                surfaceVariant = Palette.Gray, onSurfaceVariant = Palette.Muted,
                outline = Palette.Field, error = Palette.OrangeInk,
            )
        }
        MaterialTheme(colorScheme = colors, content = content)
    }
}

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
