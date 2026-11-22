package com.billmongan.herdrdroid

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps the test-order hazard from coming back (see IdleMainLooperRule and the README's
 * "Test-order hazard"). Every Robolectric test class shares one Compose main-thread dispatcher with
 * the rest of the JVM, so each one must reset and drain it:
 *
 * - every Robolectric class declares IdleMainLooperRule (idleMainLooperRule() in src/sharedTest,
 *   whose classes also run on an emulator);
 * - a class with a Compose test rule declares it with `order = OUTERMOST_RULE`, so it resets the
 *   frame time before the Compose rule opens its activity and drains the looper after it closes.
 *
 * It reads the test sources, so it runs from the app module (Gradle's working directory for unit tests).
 */
class ComposeRuleGuardTest {
    private val roots = listOf(File("src/test/java"), File("src/sharedTest/java"))
    private val robolectric = Regex("""@RunWith\(\s*(AndroidJUnit4|RobolectricTestRunner)::class\s*\)""")
    private val composeRule = Regex("""\bcreate(Android)?ComposeRule\s*[(<]""")
    private val idleRule = Regex("""(IdleMainLooperRule|idleMainLooperRule)\(\)""")
    private val outermostIdleRule = Regex("""@get:Rule\(order = OUTERMOST_RULE\)\s*val \w+ = (IdleMainLooperRule|idleMainLooperRule)\(\)""")

    private fun sources(): List<File> {
        roots.forEach { assertTrue("run from the app module: ${it.absolutePath} is missing", it.isDirectory) }
        return roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    @Test fun everyRobolectricClassResetsComposesMainThreadDispatcher() {
        val classes = sources().filter { robolectric.containsMatchIn(it.readText()) }
        assertTrue("found only ${classes.size} Robolectric test classes", classes.size >= 40)
        val missing = classes.filterNot { idleRule.containsMatchIn(it.readText()) }
        if (missing.isNotEmpty()) fail(
            "These Robolectric test classes do not declare IdleMainLooperRule, " +
                "so they can leave Compose unable to go idle for later tests:\n" + missing.joinToString("\n") { it.path })
    }

    @Test fun composeTestClassesPutTheIdleRuleOutsideTheComposeRule() {
        val compose = sources().filter { composeRule.containsMatchIn(it.readText()) }
        assertTrue("found only ${compose.size} Compose test classes", compose.size >= 15)
        val wrong = compose.filterNot { outermostIdleRule.containsMatchIn(it.readText()) }
        if (wrong.isNotEmpty()) fail(
            "Declare `@get:Rule(order = OUTERMOST_RULE) val idleMainLooper = IdleMainLooperRule()` " +
                "(idleMainLooperRule() in src/sharedTest) in:\n" + wrong.joinToString("\n") { it.path })
    }
}
