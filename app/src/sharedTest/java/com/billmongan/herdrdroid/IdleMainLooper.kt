package com.billmongan.herdrdroid

import org.junit.rules.TestRule

/**
 * The rule order that puts IdleMainLooperRule outside every other rule in a class, Compose's test
 * rule included: `@get:Rule(order = OUTERMOST_RULE) val idleMainLooper = idleMainLooperRule()`.
 * Outside, it resets Compose's kept frame time before the Compose rule opens its activity, and
 * drains the main looper after that activity is closed.
 */
const val OUTERMOST_RULE = -100

/**
 * IdleMainLooperRule under Robolectric, and a rule that does nothing on a device or emulator. The
 * Compose test classes in src/sharedTest also run on an emulator, where there is no Robolectric,
 * so they get the rule from here; IdleMainLooperRule itself lives in src/test. See
 * IdleMainLooperRule and the README's "Test-order hazard"; ComposeRuleGuardTest checks that every
 * Robolectric test class has one.
 */
fun idleMainLooperRule(): TestRule {
    if (android.os.Build.FINGERPRINT != "robolectric") return TestRule { base, _ -> base }
    return Class.forName("com.billmongan.herdrdroid.IdleMainLooperRule").getDeclaredConstructor().newInstance() as TestRule
}
