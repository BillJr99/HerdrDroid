package com.billmongan.herdrdroid.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ClippedDialog2211Test {
    private fun fixture(agent: String): List<String> =
        checkNotNull(javaClass.getResource("/long-dialog-2211/$agent.txt")).readText().trimEnd('\n').lines()

    private fun visible(history: List<String>): String = history.takeLast(36).joinToString("\n")

    private fun shownText(prompt: Prompt): String = (
        listOf(prompt.question) + prompt.shownParts().blocks.map { it.text } + prompt.region.orEmpty()
        ).joinToString("\n")

    @Test fun claudeFindsTheActiveQuestionAndFirstOptionFromDeeperHistory() = runBlocking {
        val history = fixture("claude")
        val screen = visible(history)
        val partial = checkNotNull(detectPrompt(screen, true, "claude"))
        assertTrue(partial.topOffScreen)
        assertFalse(shownText(partial).contains("Review sample condition 001"))
        val reads = mutableListOf<Int>()
        val rebuilt = completeTallDialog(screen, true, null) { count ->
            reads += count
            history.takeLast(count).joinToString("\n")
        }
        assertEquals(TALL_DIALOG_READS.take(2), reads)
        val prompt = checkNotNull(detectPrompt(rebuilt, true, "claude"))
        assertTrue(shownText(prompt).contains("What should happen if the sample task still has work left to do"))
        assertTrue(shownText(prompt).contains("Wait for completion"))
        assertTrue(shownText(prompt).contains("Review sample condition 001"))
        assertTrue(prompt.historyReviewOnly)
    }

    @Test fun openCodeRecoversTheLongCommandAboveItsVisibleControls() = runBlocking {
        assertRecovered("opencode", "Allow once")
    }

    @Test fun agyRecoversTheLongCommandAboveItsVisibleControls() = runBlocking {
        assertRecovered("agy", "Yes, run command")
    }

    private suspend fun assertRecovered(agent: String, option: String) {
        val history = fixture(agent)
        val screen = visible(history)
        val reads = mutableListOf<Int>()
        val rebuilt = completeClippedDialog(screen, true, agent, null) { count ->
            reads += count
            history.takeLast(count).joinToString("\n")
        }
        assertEquals(TALL_DIALOG_READS.take(2), reads)
        val prompt = checkNotNull(detectPrompt(rebuilt, true, agent))
        val display = shownText(prompt)
        assertTrue(agent, display.contains("sample-record-000"))
        assertTrue(agent, display.contains("sample-record-206"))
        assertTrue(agent, display.contains(option))
        assertTrue(agent, prompt.historyReviewOnly)
        assertTrue(agent, prompt.forCard().terminalReview)
    }

    @Test fun unrelatedSuffixCannotCompleteAnOpenCodePermission() = runBlocking {
        val agent = "opencode"
        val history = fixture(agent)
        val screen = visible(history)
        val different = history.map { it.replace("sample-record-206", "changed-record") }
        val reads = mutableListOf<Int>()
        val rebuilt = completeClippedDialog(screen, true, agent, null) { count ->
            reads += count
            different.takeLast(count).joinToString("\n")
        }
        assertEquals(TALL_DIALOG_READS.take(2), reads)
        assertEquals(screen, rebuilt)
    }

    @Test fun ordinaryOutputDoesNotReadHistory() = runBlocking {
        val plain = "The sample report is complete."
        for (agent in listOf("claude", "opencode", "agy")) {
            assertEquals(plain, completeClippedDialog(plain, false, agent, null) {
                error("Unexpected history read for $agent")
            })
        }
    }
}
