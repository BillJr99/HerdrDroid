package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billmongan.herdrdroid.AppViewModel

/**
 * 2.2.7: the main screen with no agent open ([com.billmongan.herdrdroid.Screen.Home]): where the
 * chat goes, with the left drawer of every pane. Back from the server list lands here when there is
 * no agent to return to, so Back stays in the app.
 */
@Composable
fun HomeScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(Palette.Ground).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.drawerOpen = true }) {
                Icon(Icons.Filled.Menu, contentDescription = "All agents", tint = Palette.Ink)
            }
            Text("HerdrDroid", color = Palette.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
        }
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (vm.current == null) "No server is open" else "No agent is open", color = Palette.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Pull out the menu on the left to see your panes, or open Servers to connect to a computer.",
                    color = Palette.Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.drawerOpen = true }) { Text("Show my panes") }
                    TextButton(onClick = { vm.openServers() }) { Text("Servers") }
                }
            }
        }
    }
}
