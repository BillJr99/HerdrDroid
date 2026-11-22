package com.billmongan.herdrdroid

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The 2.1.4 test-order hazard as a fixed sequence in one Robolectric sandbox (see IdleMainLooperRule):
 * a Compose render, then a test that changes Compose state and ends without idling the main looper,
 * then another Compose render. The methods run in name order. Without IdleMainLooperRule's drain,
 * c_ finds Compose's main-thread dispatcher stuck and its composition never runs; without the frame
 * time reset, c_'s first idle moves the clock forward by about 10 seconds.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ComposeMainLooperRegressionTest {
    @get:Rule val idleMainLooper = IdleMainLooperRule()

    /**
     * Renders a composable in a fresh activity, then changes the state it reads; true when the
     * recomposition ran. The first composition happens inside setContent, so only the recomposition
     * shows whether Compose's main-thread dispatcher still runs.
     */
    private fun render(): Boolean {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shown = mutableStateOf(0)
        var seen = -1
        activity.get().setContent { val v = shown.value; SideEffect { seen = v }; Text("regression $v") }
        shadowOf(Looper.getMainLooper()).idle()
        shown.value = 1
        shadowOf(Looper.getMainLooper()).idle()
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return seen == 1
    }

    @Test fun a_aComposeRenderStartsComposesMainThreadDispatcher() {
        assertTrue(render())
        // Draw one more frame 10 seconds into this test's clock, so the dispatcher's Choreographer
        // remembers a late frame time.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        mutableStateOf(0).value = 1
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun b_aStateChangeEndsWithItsCallbackQueued() {
        mutableStateOf(0).value = 1
        // Compose's callback is waiting on the main looper; the test ends here without idling it.
        assertFalse(shadowOf(Looper.getMainLooper()).isIdle)
    }

    @Test fun c_aLaterComposeRenderRunsAndTheClockStaysPut() {
        val start = SystemClock.uptimeMillis()
        mutableStateOf(0).value = 1
        shadowOf(Looper.getMainLooper()).idle()
        val moved = SystemClock.uptimeMillis() - start
        assertTrue("one idle moved the clock $moved ms", moved < 1_000)
        assertTrue("the composition did not run: Compose's main-thread dispatcher is stuck", render())
    }
}
