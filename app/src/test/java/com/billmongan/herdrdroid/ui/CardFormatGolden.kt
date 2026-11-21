package com.billmongan.herdrdroid.ui

import com.billmongan.herdrdroid.herdr.detectPrompt
import java.io.File

/** Every library screen that makes a card, laid out by [CardFormat] as plain text (2.1.1). */
object CardFormatGolden {
    fun describe(b: CardBlock): String = when (b) {
        is CardBlock.Headline -> "HEADLINE " + b.text
        is CardBlock.Caption -> "CAPTION " + b.text
        is CardBlock.Subhead -> "SUBHEAD " + b.text
        is CardBlock.Prose -> "PROSE " + b.text
        is CardBlock.Labelled -> "LABEL " + b.label + " = " + b.value
        is CardBlock.Items -> "LIST\n" + b.items.joinToString("\n") { "  " + it.mark + " " + it.text }
        is CardBlock.Code -> (if (b.diff) "DIFF" else if (b.wrap) "CODE wrap" else "CODE") + "\n" + b.lines.joinToString("\n") { "  | $it" }
    }

    /** The golden text split by screen, so a screen added later (not in the golden file yet) is left out. */
    fun sections(text: String): Map<String, String> = text.split("##### ").filter { it.isNotBlank() }
        .associate { it.substringBefore('\n') to it.substringAfter('\n') }

    fun render(): String {
        val root = File(requireNotNull(javaClass.classLoader?.getResource("screens")).toURI())
        val out = StringBuilder()
        for (f in root.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }.sortedBy { it.path }) {
            val rel = f.relativeTo(root)
            val agent = rel.parentFile.parentFile.name
            val e = File(f.path.removeSuffix(".txt") + ".expect")
            val blocked = !e.isFile || e.readText().contains("blocked: true")
            val screen = f.readText()
            val p = detectPrompt(screen, blocked, agent) ?: continue
            out.append("##### ").append(rel.path.replace(File.separatorChar, '/')).append('\n')
            CardFormat.format(p.shownParts(), screen).forEach { out.append(describe(it)).append('\n') }
            val notes = CardFormat.optionNotes(screen, p.choices)
            p.choices.forEach { c ->
                val (main, tail) = CardFormat.labelParts(c.label)
                out.append("OPTION ").append(main).append(if (tail.isEmpty()) "" else " ~$tail~").append('\n')
                notes[c.keys]?.let { out.append("  NOTE ").append(it).append('\n') }
            }
        }
        return out.toString()
    }
}
