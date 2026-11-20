package com.billmongan.herdrdroid.herdr

/** The actual pane occupant and live usage frame. A public pane ID alone can be reused. */
internal data class UsageOverlay(val occupant: String, val visible: String)

// Hermes prints these reports into the transcript (classic CLI and Ink). None of these
// headings, token counts, or neighboring key hints is evidence that Escape is safe.
private val HERMES_USAGE_TITLE = Regex("""(?:📊\s*)?(?:(?:Session )?Token Usage|Usage)""")
private val HERMES_USAGE_ROW = Regex("""(?:Input tokens|Output tokens|Total tokens|API calls)(?::\s*|\s+)[\d,.]+""")

/** End of the last identifiable report; text below it may contain a newer, real question. */
internal fun hermesUsageEnd(screen: String): Int? {
    val lines = stripAnsi(screen).lines()
    fun body(row: String) = row.trim().trim('│', '*').trim()
    val start = lines.indexOfLast { HERMES_USAGE_TITLE.matches(body(it)) }
    if (start < 0 || start < lines.size - 80) return null
    val rows = lines.drop(start + 1).take(18).takeWhile {
        val row = it.trim()
        !row.startsWith('╰') && !row.startsWith('╭') && !row.startsWith('❯') && !row.startsWith('›')
    }
    val counters = rows.withIndex().filter { HERMES_USAGE_ROW.matches(body(it.value)) }
    return counters.lastOrNull()?.index?.takeIf { counters.size >= 2 }?.let { start + 1 + it }
}

/** For rapport/dispatch only: no question inferred from prior scrollback or the report's chrome. */
internal fun hermesUsageWaiting(screen: String): Boolean {
    val end = hermesUsageEnd(screen) ?: return false
    val tail = screen.lineSequence().drop(end + 1).joinToString("\n")
    return inputLineOf(tail, "hermes") == null && detectPrompt(screen, false, "hermes") == null
}

/** No fallback read, queued key, or retry. The caller refreshes the screen after any attempt. */
internal suspend fun dismissUsageOverlay(
    shown: UsageOverlay,
    stillCurrent: () -> Boolean,
    readLive: suspend () -> UsageOverlay?,
    sendEscape: suspend () -> Boolean,
): Boolean {
    if (!stillCurrent() || readLive() != shown || !stillCurrent()) return false
    return sendEscape()
}

/** The Herdr 0.9.5 pane get/process-info response shape is documented in the socket API.
 * Bind PID numbers to boot and process-start ticks, and check the pane both sides of the read.
 * When the backend/platform cannot positively identify the occupant or viewport, fail closed.
 */
internal val USAGE_OVERLAY_SNAPSHOT = """
    import hashlib, json, pathlib, subprocess, sys
    herdr, pane, agent = sys.argv[1:4]
    expected = sys.argv[4:]
    def call(*args):
        return subprocess.check_output([herdr, 'pane', *args], text=True)
    def metadata():
        p = json.loads(call('get', pane))['result']['pane']
        if (p['pane_id'] != pane or p['agent'] != agent or
                p['agent_status'] not in ('idle', 'done', 'blocked') or
                p['scroll']['offset_from_bottom'] != 0):
            raise ValueError()
        return [p['workspace_id'], p['tab_id'], p.get('terminal_id'), p.get('agent_session')]
    def process_start(pid):
        if type(pid) is not int or pid <= 0:
            raise ValueError()
        stat = pathlib.Path('/proc', str(pid), 'stat').read_text()
        return [pid, int(stat.rsplit(')', 1)[1].split()[19])]
    def occupant():
        q = json.loads(call('process-info', '--pane', pane))['result']['process_info']
        if q['pane_id'] != pane:
            raise ValueError()
        boot = pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip()
        if not boot:
            raise ValueError()
        return [boot, process_start(q['shell_pid']), process_start(q['foreground_process_group_id'])]
    try:
        before = [metadata(), occupant()]
        visible = call('read', pane, '--source', 'visible')
        if before == [metadata(), occupant()]:
            identity = hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest()
            if not expected:
                print(json.dumps({'occupant': identity, 'visible': visible}))
            elif (expected == [identity, hashlib.sha256(visible.encode()).hexdigest()] and
                  visible == call('read', pane, '--source', 'visible') and
                  before == [metadata(), occupant()]):
                call('send-keys', pane, 'esc')
                print('sent')
    except (OSError, ValueError, KeyError, IndexError, TypeError, subprocess.SubprocessError):
        pass
""".trimIndent()
