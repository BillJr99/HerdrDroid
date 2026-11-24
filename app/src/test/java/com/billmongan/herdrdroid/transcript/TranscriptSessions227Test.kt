package com.billmongan.herdrdroid.transcript

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 2.2.7 (Grok's review): transcript.py's past-sessions list takes a named launch of OpenCode
 * ("opencode-<name>") as OpenCode, as its chat reader does. Runs where python3 is.
 */
class TranscriptSessions227Test {
    private val python = listOf("/usr/bin/python3", "/bin/python3").map { File(it) }.firstOrNull { it.canExecute() }
    private val script = listOf("src/main/assets/transcript.py", "app/src/main/assets/transcript.py").map { File(it) }.firstOrNull { it.isFile }

    private fun run(home: File, vararg args: String): String {
        val p = ProcessBuilder(python!!.path, *args).apply { environment()["HOME"] = home.path }.redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return out
    }

    @Test fun aNamedOpencodeLaunchListsItsPastSessions() {
        assumeTrue("needs python3", python != null)
        assertNotNull("transcript.py", script)
        val home = java.nio.file.Files.createTempDirectory("hd227-sessions").toFile()
        try {
            val db = File(home, ".local/share/opencode/opencode.db").apply { parentFile.mkdirs() }
            // A fixture OpenCode database with one session in the folder.
            run(home, "-c", "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); " +
                "c.execute('create table session_v2 (id text, title text, time_updated integer, directory text, parent_id text)'); " +
                "c.execute(\"insert into session_v2 values ('ses_fixture', 'Fixture session', 1764000000000, '/fixture/project', null)\"); c.commit()",
                db.path)
            for (agent in listOf("opencode", "opencode-work", "OpenCode-Lab2")) {
                val out = run(home, script!!.path, "__sessions__", agent, "/fixture/project")
                assertTrue("$agent: $out", out.contains("\"ses_fixture\"") && out.contains("Fixture session"))
            }
            assertFalse("another agent lists none of them", run(home, script!!.path, "__sessions__", "codex", "/fixture/project").contains("ses_fixture"))
        } finally {
            home.deleteRecursively()
        }
    }
}
