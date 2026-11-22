package com.billmongan.herdrdroid

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * An activity's own composition, which ComposeFrameTimeOrderTest runs right after
 * LostComposeCallbackTest. A Compose test rule sends state changes and drives frames itself, so it
 * can pass while the app's own path is still broken; an activity's composition recomposes only when
 * Compose's main-thread dispatcher runs and its kept Choreographer still asks for frames. Without
 * IdleMainLooperRule's repair of that Choreographer's lost frame, the recomposition never runs.
 * Gradle also runs it on its own.
 */
@RunWith(AndroidJUnit4::class)
class ComposeActivityFollowerTest {
    @get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()

    @Test fun anActivitysCompositionStillRecomposes() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shown = mutableStateOf(0)
        var seen = -1
        activity.get().setContent { val v = shown.value; SideEffect { seen = v }; Text("activity $v") }
        shadowOf(Looper.getMainLooper()).idle()
        shown.value = 1
        shadowOf(Looper.getMainLooper()).idle()
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the activity's composition did not recompose: Compose's main-thread dispatcher is stuck", seen == 1)
    }
}
