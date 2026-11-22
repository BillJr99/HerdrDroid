package com.billmongan.herdrdroid

import android.os.Looper
import android.view.Choreographer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.ContinuationInterceptor

/**
 * Keeps a Robolectric test that changes Compose state (an AppViewModel's mutableStateOf fields)
 * without rendering anything from harming the tests after it, or being harmed by the ones before.
 * Use it in every view-model test class: `@get:Rule val idleMainLooper = IdleMainLooperRule()`.
 *
 * Once any Compose test has run in the JVM, Compose's GlobalSnapshotManager watches every state
 * write and, on the first one, posts a callback to the main looper through AndroidUiDispatcher.Main.
 * That dispatcher is a process-wide singleton, made in the first Compose test, and two things about
 * it outlive the test that made it:
 *
 * 1. It posts once and then waits for its callback to run (scheduledTrampolineDispatch stays true
 *    until it does). Robolectric empties the main looper's queue between tests, so a test that ends
 *    with the callback still queued leaves the dispatcher waiting forever: nothing on it runs again,
 *    and every later Compose test fails after 60 seconds with AppNotIdleException ("Compose did not
 *    get idle"). HandsFreeAppLockTest did this in 2.1.4. [after] drains the looper to prevent it.
 *
 * 2. It keeps the first test's Choreographer, and that Choreographer keeps the time of the last
 *    frame it drew. Robolectric starts every test's clock again at 100 ms, so in a later test the
 *    frame time looks as if it went backwards, and the Choreographer asks for one more frame every
 *    millisecond until the clock catches up. Robolectric moves its clock forward for each of those
 *    frames, so one idle() after a state write can jump the clock by as long as an earlier test ran
 *    (29 s after HeldAcrossServersTest), firing delayed work such as the 5-second notice timer.
 *    [before] and [after] set that frame time back to zero.
 *
 * The rule's after() runs after the class's @After methods, so cancel view models there first.
 * ComposeMainLooperRegressionTest covers both; AaaComposeOrderProbeTest is a smoke sentinel.
 */
class IdleMainLooperRule : ExternalResource() {
    override fun before() = resetComposeFrameTime()

    override fun after() {
        var failure: Throwable? = null
        try {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
        } catch (t: Throwable) {
            failure = t
        }
        try {
            resetComposeFrameTime()
        } catch (t: Throwable) {
            if (failure == null) failure = t else failure.addSuppressed(t)
        }
        failure?.let { throw it }
    }

    companion object {
        /**
         * Sets the frame time of the Choreographer that AndroidUiDispatcher.Main keeps back to zero.
         * Does nothing until a Compose test has made that dispatcher (this never makes it), and
         * fails loudly if the fields it needs have changed in Compose or Robolectric.
         */
        fun resetComposeFrameTime() {
            try {
                val lazy = AndroidUiDispatcher::class.java.getDeclaredField("Main\$delegate").apply { isAccessible = true }.get(null) as Lazy<*>
                if (!lazy.isInitialized()) return
                val dispatcher = checkNotNull(AndroidUiDispatcher.Main[ContinuationInterceptor]) { "AndroidUiDispatcher.Main has no dispatcher" }
                val choreographer = dispatcher.javaClass.getDeclaredField("choreographer").apply { isAccessible = true }.get(dispatcher) as Choreographer
                Choreographer::class.java.getDeclaredField("mLastFrameTimeNanos").apply { isAccessible = true }.setLong(choreographer, 0L)
            } catch (e: ReflectiveOperationException) {
                throw AssertionError("IdleMainLooperRule could not reset Compose's frame time; Compose or Robolectric changed: $e", e)
            } catch (e: ClassCastException) {
                throw AssertionError("IdleMainLooperRule could not reset Compose's frame time; Compose or Robolectric changed: $e", e)
            }
        }
    }
}
