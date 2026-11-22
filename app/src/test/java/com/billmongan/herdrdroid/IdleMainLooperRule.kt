package com.billmongan.herdrdroid

import android.os.Looper
import android.view.Choreographer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.coroutines.ContinuationInterceptor

/**
 * Keeps a Robolectric test that changes Compose state (an AppViewModel's mutableStateOf fields, or
 * a rendered screen) from harming the tests after it, or being harmed by the ones before. Every
 * Robolectric test class declares it, outside any Compose rule:
 * `@get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()` (in src/sharedTest,
 * `idleMainLooperRule()`, which does nothing on an emulator). ComposeRuleGuardTest enforces this.
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
 *    get idle"). HandsFreeAppLockTest did this in 2.1.4. [after] drains the looper to prevent it,
 *    and [before] runs a lost callback (repairComposeDispatcher) in case an earlier class without
 *    the rule, or a write that raced the drain, left the dispatcher waiting (2.2.0 CI).
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
    override fun before() {
        repairComposeDispatcher()
        resetComposeFrameTime()
    }

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
            composeChoreographer()?.let { lastFrameTime.setLong(it, 0L) }
        }

        /**
         * The frame time, in milliseconds of the paused clock, that AndroidUiDispatcher.Main's
         * Choreographer keeps; null until a Compose test has made that dispatcher.
         */
        fun keptComposeFrameTimeMillis(): Long? = composeChoreographer()?.let { lastFrameTime.getLong(it) / 1_000_000 }

        /**
         * Repairs AndroidUiDispatcher.Main if a test before this one left it waiting for a callback
         * that Robolectric dropped (point 1 above), so Compose's main-thread work runs again. Called
         * before a test, when the main looper has nothing queued at all, so anything the dispatcher
         * or its Choreographer still counts as scheduled has been lost:
         *
         * - The dispatcher's own callback: running it does what the lost one would have done. It
         *   runs the waiting tasks and clears both of the dispatcher's flags once nothing is left.
         * - The kept Choreographer's next frame (mFrameScheduled): while it stays set, that
         *   Choreographer never asks for another frame, so a recomposition that waits for one never
         *   comes. It is cleared, and work still waiting for a frame gets a new frame callback.
         */
        fun repairComposeDispatcher() {
            val dispatcher = composeDispatcher() ?: return
            if (shadowOf(Looper.getMainLooper()).nextScheduledTaskTime != Duration.ZERO) return
            guarded {
                val type = dispatcher.javaClass
                fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
                val lock = field("lock").get(dispatcher)!!
                val tramp = field("scheduledTrampolineDispatch")
                val frame = field("scheduledFrameDispatch")
                val choreographer = field("choreographer").get(dispatcher) as Choreographer
                Choreographer::class.java.getDeclaredField("mFrameScheduled").apply { isAccessible = true }.setBoolean(choreographer, false)
                val stuck = synchronized(lock) { tramp.getBoolean(dispatcher) || frame.getBoolean(dispatcher) }
                if (!stuck) return@guarded
                val callback = field("dispatchCallback").get(dispatcher)
                (callback as Runnable).run()
                val waitsForFrame = synchronized(lock) { frame.getBoolean(dispatcher) }
                if (waitsForFrame) {
                    choreographer.removeFrameCallback(callback as Choreographer.FrameCallback)
                    choreographer.postFrameCallback(callback)
                }
            }
        }

        private val lastFrameTime by lazy {
            guarded { Choreographer::class.java.getDeclaredField("mLastFrameTimeNanos").apply { isAccessible = true } }
        }

        /** AndroidUiDispatcher.Main's dispatcher; null until a Compose test has made it (this never makes it). */
        private fun composeDispatcher(): Any? = guarded {
            val lazy = AndroidUiDispatcher::class.java.getDeclaredField("Main\$delegate").apply { isAccessible = true }.get(null) as Lazy<*>
            if (!lazy.isInitialized()) return@guarded null
            checkNotNull(AndroidUiDispatcher.Main[ContinuationInterceptor]) { "AndroidUiDispatcher.Main has no dispatcher" }
        }

        private fun composeChoreographer(): Choreographer? = composeDispatcher()?.let { dispatcher ->
            guarded { dispatcher.javaClass.getDeclaredField("choreographer").apply { isAccessible = true }.get(dispatcher) as Choreographer }
        }

        private fun <T> guarded(block: () -> T): T {
            try {
                return block()
            } catch (e: ReflectiveOperationException) {
                throw AssertionError("IdleMainLooperRule could not reach Compose's main-thread dispatcher; Compose or Robolectric changed: $e", e)
            } catch (e: ClassCastException) {
                throw AssertionError("IdleMainLooperRule could not reach Compose's main-thread dispatcher; Compose or Robolectric changed: $e", e)
            }
        }
    }
}
