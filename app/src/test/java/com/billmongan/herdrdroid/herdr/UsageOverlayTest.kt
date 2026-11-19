package com.billmongan.herdrdroid.herdr

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsageOverlayTest {
    @get:Rule val tmp = TemporaryFolder()
    private val usage = "Status   Config   Usage\nCurrent session\nLoading…\nEsc to close"
    private val approval = "Approve this tool call\n❯ 1. Yes\n2. No\nEsc to cancel"

    @Test fun automaticQuotaUsesOnlyCliAndFooterProfiles() {
        assertEquals(setOf("vibe", "omp", "muse"), QuotaProfiles.builtIn
            .filter { it.hasPassiveSource }.flatMap { it.agents }.toSet())
    }

    @Test fun usageActionRequiresKnownLayoutNotOnlyAnEscapeHintOrNumbers() {
        assertNotNull(dismissibleUsageFrame("claude", usage))
        for (text in listOf(approval, "30% used\nEsc to cancel", "Current session\n30% used\nEsc to close",
            "$usage\n❯ my draft", "$usage\n$approval", usage.replace("Loading…", "Approve this tool call"),
            usage.replace("Current session", "Select model"), usage.replace("Esc to close", "Esc to interrupt"))) {
            assertNull(text, dismissibleUsageFrame("claude", text))
        }
        assertNull(dismissibleUsageFrame("grok", usage))
    }

    @Test fun dismissalRechecksChangedScreenOccupantAndNavigation() = runBlocking {
        val shown = UsageOverlay("occupant-1", usage)
        var sends = 0
        val send: suspend () -> Boolean = { sends++; true }
        for (live in listOf(null, shown.copy(occupant = "occupant-2"), shown.copy(visible = approval))) {
            assertFalse(dismissUsageOverlay(shown, { true }, { live }, send))
        }
        var current = true
        assertFalse(dismissUsageOverlay(shown, { current }, { current = false; shown }, send))
        assertFalse(dismissUsageOverlay(shown, { false }, { error("must not read") }, send))
        try {
            dismissUsageOverlay(shown, { true }, { throw CancellationException() }, send)
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(0, sends)
        assertTrue(dismissUsageOverlay(shown, { true }, { shown }, send))
        assertEquals(1, sends)
    }

    @Test fun serverSideGuardSendsOnlyForTheExactLiveSnapshot() {
        assumeTrue(File("/usr/bin/python3").canExecute() && File("/proc/self/stat").isFile)
        val root = tmp.newFolder()
        val fake = root.resolve("herdr")
        fake.writeText("""#!/usr/bin/python3
import json, pathlib, sys
root = pathlib.Path(__file__).parent
state = json.loads((root / 'state').read_text())
cmd = sys.argv[2]
pane = 'w1:p1'
if cmd == 'get':
    print(json.dumps({'result': {'pane': {'pane_id': pane, 'agent': state['agent'],
        'workspace_id': 'w1', 'tab_id': 'w1:t1', 'agent_status': state['status'],
        'scroll': {'offset_from_bottom': state['scroll']}}}}))
elif cmd == 'process-info':
    reads = root / 'reads'
    pid = state.get('final_pid', state['pid']) if reads.exists() and int(reads.read_text()) >= 2 else state['pid']
    print(json.dumps({'result': {'process_info': {'pane_id': pane, 'shell_pid': pid,
        'foreground_process_group_id': pid}}}))
elif cmd == 'read':
    assert sys.argv[3:] == [pane, '--source', 'visible']
    count_path = root / 'reads'
    count = int(count_path.read_text()) if count_path.exists() else 0
    count_path.write_text(str(count+1))
    print(state.get('next', state['visible']) if count else state['visible'])
elif cmd == 'send-keys':
    assert sys.argv[3:] == [pane, 'esc']
    with (root / 'keys').open('a') as f:
        f.write('esc\n')
    state['visible'] = '❯ existing draft'
    (root / 'state').write_text(json.dumps(state))
else:
    raise AssertionError(cmd)
""")
        assertTrue(fake.setExecutable(true))
        val pid = File("/proc/self/stat").readText().substringBefore(' ').toLong()
        fun state(visible: String = usage, agent: String = "claude", status: String = "idle", scroll: Int = 0, process: Long = pid, next: String? = null, finalPid: Long? = null) {
            root.resolve("reads").delete()
            root.resolve("state").writeText(JSONObject().put("visible", visible).put("agent", agent).put("status", status)
                .put("scroll", scroll).put("pid", process).put("next", next).put("final_pid", finalPid).toString())
        }
        fun call(vararg args: String): String {
            val p = ProcessBuilder("/usr/bin/python3", "-c", USAGE_OVERLAY_SNAPSHOT, fake.path, "w1:p1", "claude", *args).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            assertEquals(p.errorStream.bufferedReader().readText(), 0, p.waitFor())
            return out
        }
        state()
        val shown = JSONObject(call())
        val digest = MessageDigest.getInstance("SHA-256").digest(shown.getString("visible").toByteArray()).joinToString("") { "%02x".format(it) }
        fun close() = call(shown.getString("occupant"), digest)
        state(visible = approval)
        assertEquals("", close())
        state(next = approval) // Changes after the first visible read, just before dispatch.
        assertEquals("", close())
        state(status = "working")
        assertEquals("", close())
        state(scroll = 1)
        assertEquals("", close())
        state(agent = "codex")
        assertEquals("", close())
        state(process = 1)
        assertEquals("", close())
        state(finalPid = 1) // Identical final frame, but a different occupant by the end of that read.
        assertEquals("", close())
        assertFalse(root.resolve("keys").exists())
        state()
        assertEquals("sent", close())
        val recovered = JSONObject(call()).getString("visible")
        assertEquals("❯ existing draft", recovered.trim())
        assertNull(dismissibleUsageFrame("claude", recovered))
        assertEquals("", close()) // A stale tap cannot send Escape into the restored composer.
        assertEquals(listOf("esc"), root.resolve("keys").readLines())
    }
}
