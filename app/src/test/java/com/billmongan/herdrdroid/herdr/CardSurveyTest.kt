package com.billmongan.herdrdroid.herdr

import java.io.File
import org.junit.Test

/** 1.4.9: writes how every derived card in the screen library parses, for the coverage audit (never fails). */
class CardSurveyTest {
    @Test fun survey() {
        val root = javaClass.classLoader?.getResource("screens")?.let { File(it.toURI()) } ?: return
        val out = StringBuilder()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") && (it.parentFile.name.startsWith("derived") || it.parentFile.name.startsWith("captured")) }.sortedBy { it.path }.forEach { f ->
            val agent = f.parentFile.parentFile.name
            val text = f.readText()
            for (blocked in listOf(true, false)) {
                val p = detectPrompt(text, blocked, agent)
                out.append("== ${agent}/${f.parentFile.name}/${f.name} blocked=$blocked\n")
                if (p == null) { out.append("   NO CARD\n"); continue }
                out.append("   heading: ${p.parts?.heading ?: p.shownParts().heading ?: p.question}\n")
                out.append("   command: ${p.shownParts().command}\n")
                out.append("   selected: ${p.selected} multi: ${p.multi} region: ${p.region?.size}\n")
                p.choices.forEach { c ->
                    out.append("   [${c.keys}] ${c.label} -> ${menuKeys(p, c)}${if (grantsStandingPermission(c.label)) " CAUTION" else ""}\n")
                }
            }
            out.append("   error: ${detectAgentError(text)}\n")
        }
        File("build/card-survey.txt").writeText(out.toString())
    }
}
