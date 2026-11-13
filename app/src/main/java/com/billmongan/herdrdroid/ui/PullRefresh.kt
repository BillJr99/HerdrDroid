package com.billmongan.herdrdroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** Pulling the list down from the top does what the screen's Refresh does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefresh(onRefresh: suspend () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = busy,
        onRefresh = {
            busy = true
            scope.launch { try { onRefresh() } finally { busy = false } }
        },
        modifier = modifier,
    ) { content() }
}
