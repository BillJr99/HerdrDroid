package com.billmongan.herdrdroid

import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.Request

/**
 * The 2.2.0 test-order hazards as fixed sequences in one JVM and one Robolectric sandbox (see
 * IdleMainLooperRule and the README's "Test-order hazard"). Each runs a Compose test, then the test
 * that leaves Compose's main-thread dispatcher in a bad state, then ComposeFrameTimeFollowerTest.
 * A plain JUnit test, so it can run the three in this order whatever order Gradle picks.
 */
class ComposeFrameTimeOrderTest {
    private val compose = Request.method(AaaComposeOrderProbeTest::class.java, "aTrivialComposableRenders")
    private val follower = Request.method(ComposeFrameTimeFollowerTest::class.java, "aComposeTestFindsNoStaleFrameTimeAndItsClockStaysPut")

    /**
     * The CI failure of 2.2.0: the dispatcher's callback is lost at the end of a test. Without the
     * repair in IdleMainLooperRule.before, the follower fails after 60 seconds with AppNotIdleException.
     */
    @Test fun aLostComposeCallbackDoesNotHangTheNextComposeTest() = runInOrder(
        compose,
        Request.method(LostComposeCallbackTest::class.java, "aStateWriteAfterTheLastDrainLeavesComposesCallbackToBeDropped"),
        follower,
    )

    /**
     * MenuWriteGuard220Test (new in 2.2.0, without IdleMainLooperRule) runs the paused clock about a
     * minute forward while Compose draws frames. Without the rule there, the follower finds Compose's
     * frame time about 40 seconds into the old clock.
     */
    @Test fun aViewModelTestThatRunsTheClockFarLeavesNoStaleFrameTime() = runInOrder(
        compose,
        Request.method(MenuWriteGuard220Test::class.java, "aVibeSwitchStuckOnAutoApproveSaysSoLoudly"),
        follower,
    )

    private fun runInOrder(vararg steps: Request) {
        for (step in steps) {
            val result = JUnitCore().run(step)
            if (result.runCount != 1 || !result.wasSuccessful()) fail(
                "${step.runner.description} (ran ${result.runCount}): " +
                    result.failures.joinToString("\n") { it.message ?: it.exception.toString() })
        }
    }
}
