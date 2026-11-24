package com.billmongan.herdrdroid

import androidx.lifecycle.ViewModelStore

/**
 * Ends a view model a test made the way Android ends one: its scope is cancelled and onCleared
 * runs, which stops the reconnect, the other servers' watcher, the connections and the
 * process-wide listeners it set (2.2.8). Cancelling only the scope left those running into the
 * next test. Call it in @After or a finally block: IdleMainLooperRule's after() runs later.
 */
fun AppViewModel.clearForTest() {
    ViewModelStore().apply { put("test", this@clearForTest) }.clear()
}

/**
 * Keeps the view model from starting its real reconnect loop for the rest of the test: an active
 * placeholder job makes startReconnect return at once, so a lost link in a test never dials the
 * fixture host from an IO thread that outlives it. [clearForTest] cancels the placeholder (onCleared
 * cancels reconnectJob). Call it on the main thread before the screen opens.
 */
fun AppViewModel.holdReconnectForTest() {
    AppViewModel::class.java.getDeclaredField("reconnectJob").apply { isAccessible = true }.set(this, kotlinx.coroutines.Job())
}
