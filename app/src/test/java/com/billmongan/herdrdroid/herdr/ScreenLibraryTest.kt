package com.billmongan.herdrdroid.herdr

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The screen library (1.4.1, Bill: "A screen library that catches regressions when agents
 * update"): real screens captured from each agent, stored by agent and version under
 * app/src/test/resources/screens/<agent>/<version>/<name>.txt, run through the parsers.
 *
 * A screen with a <name>.expect file is checked against it:
 *   card: options | fallback | none    what detectPrompt must return
 *   blocked: true | false              herdr's status when it was captured
 *   heading: ...                       the question as the card shows it (optional)
 *   options: a | b | c                 every option's label, in order (optional)
 * A screen with no .expect file (a fresh capture from bill-agent-screens.py on demo-box2 after an
 * agent update) must, if it looks like a question (a question footer, or a highlighted numbered
 * row), parse as a card with at least two options rather than fall back to the generic card.
 * Every screen that fails is listed as "BROKEN <agent>/<version>/<name>.txt", which the weekly
 * check on demo-box2 reads to report which card stopped parsing.
 */
class ScreenLibraryTest {
    private val root: File? = javaClass.classLoader?.getResource("screens")?.let { File(it.toURI()) }

    private fun expect(f: File): Map<String, String>? {
        val e = File(f.path.removeSuffix(".txt") + ".expect")
        if (!e.isFile) return null
        return e.readLines().mapNotNull { l -> l.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
    }

    private val looksLikeQuestion = Regex("""Enter to select|esc to cancel|Esc to cancel|↑/↓|Press enter to confirm|^\s*[❯›>]\s*1[.)]\s""", RegexOption.MULTILINE)

    @Test fun everyCapturedScreenParses() {
        val r = root ?: error("no screens folder on the test classpath")
        val screens = r.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }.sortedBy { it.path }.toList()
        assertTrue("the library is empty", screens.isNotEmpty())
        val broken = mutableListOf<String>()
        for (f in screens) {
            val rel = f.relativeTo(r).path
            val text = f.readText()
            val e = expect(f)
            val why: String? = if (e != null) {
                val p = detectPrompt(text, e["blocked"] == "true")
                when (e["card"]) {
                    "none" -> if (p != null) "expected no card, got \"${p.question}\"" else null
                    "fallback" -> if (p == null || p.choices.isNotEmpty() || p.region == null) "expected the fallback card" else null
                    else -> when {
                        p == null || p.choices.isEmpty() -> "expected a card with options, got ${if (p == null) "none" else "no options"}"
                        e["heading"] != null && (p.parts?.heading ?: p.shownParts().heading ?: p.question) != e["heading"] ->
                            "heading \"${p.parts?.heading ?: p.shownParts().heading ?: p.question}\", expected \"${e["heading"]}\""
                        e["options"] != null && p.choices.map { it.label } != e["options"]!!.split(" | ") ->
                            "options ${p.choices.map { it.label }}, expected ${e["options"]}"
                        else -> null
                    }
                }
            } else if (looksLikeQuestion.containsMatchIn(text)) {
                val p = detectPrompt(text, blocked = true)
                if (p == null || p.choices.size < 2) "looks like a question but parsed as ${if (p == null) "no card" else "the fallback card"}" else null
            } else null
            if (why != null) broken += "BROKEN $rel: $why"
        }
        assertTrue(broken.joinToString("\n"), broken.isEmpty())
    }
}
