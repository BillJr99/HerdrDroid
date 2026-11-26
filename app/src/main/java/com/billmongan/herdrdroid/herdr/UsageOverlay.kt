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

/** What a guarded live read found (2.1.4): the dialog in its known layout, no dialog, or can't tell. */
internal sealed interface UsageState {
    data class Open(val overlay: UsageOverlay) : UsageState
    data object Closed : UsageState
    /** The read failed or was refused, or the dialog's hint is drawn in a layout not known. */
    data object Unknown : UsageState
}

/** How a close went: confirmed closed by a fresh read, or not, with the reason. */
internal data class UsageCloseOutcome(val closed: Boolean, val escapes: Int, val why: String?)

/**
 * Close an agent's usage dialog and confirm it closed (2.1.4, Bill: "The grok pane was a usage tab
 * probably opened by herdrdroid make sure that doesn't get stuck in the future").
 *
 * Each round reads the live screen; only its known layout ([UsageState.Open]) gets an Esc, sent by
 * [sendEscape] for that exact snapshot, then [settle] waits for a redraw and the screen is read
 * again. Up to [maxEscapes] Esc presses; nothing else is ever typed. It is closed only when a read
 * finds no dialog and no hint of one. A read that can't tell is tried once more, then reported.
 * Read failures count as can't tell; cancellation is passed on (callers run this NonCancellable).
 */
internal suspend fun closeUsageAndVerify(
    stillCurrent: () -> Boolean,
    readState: suspend () -> UsageState,
    sendEscape: suspend (UsageOverlay) -> Boolean,
    settle: suspend () -> Unit,
    maxEscapes: Int = 3,
): UsageCloseOutcome {
    var escapes = 0
    var tries = 0
    var unknown = 0
    while (true) {
        if (!stillCurrent()) return UsageCloseOutcome(false, escapes, "the pane or its connection changed")
        val state = try {
            readState()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            UsageState.Unknown
        }
        when (state) {
            UsageState.Closed -> return UsageCloseOutcome(true, escapes, null)
            UsageState.Unknown -> {
                if (++unknown >= 2) return UsageCloseOutcome(false, escapes, "its screen couldn't be checked")
                settle()
            }
            is UsageState.Open -> {
                if (tries >= maxEscapes) return UsageCloseOutcome(false, escapes, "it was still open after $escapes Esc")
                tries++
                if (!stillCurrent()) return UsageCloseOutcome(false, escapes, "the pane or its connection changed")
                val sent = try {
                    sendEscape(state.overlay)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (_: Exception) {
                    false
                }
                if (sent) escapes++
                settle()
            }
        }
    }
}

/** The Herdr 0.9.5 pane get/process-info response shape is documented in the socket API.
 * Bind PID numbers to boot and process-start ticks, and check the pane both sides of the read.
 * When the backend/platform cannot positively identify the occupant or viewport, fail closed.
 */
internal val USAGE_OVERLAY_SNAPSHOT = """
    import hashlib, json, pathlib, subprocess, sys
    herdr, pane, agent = sys.argv[1:4]
    expected = sys.argv[4:6]
    key = sys.argv[6] if len(sys.argv) > 6 else 'esc'
    if key not in ('esc', 'down'):
        sys.exit(0)
    ALIASES = {
        'agy': ('agy', 'antigravity', 'antigravity-cli', 'antigravity_cli', 'gemini-antigravity'),
        'claude': ('claude', 'claude-code', 'claude_code', 'claudecode'),
        'codex': ('codex', 'codex-cli', 'openai-codex'),
        'grok': ('grok', 'grok-cli', 'grok-build'),
        'copilot': ('copilot', 'copilot-cli', 'github-copilot', 'gh-copilot'),
        'omp': ('omp', 'oh-my-pi', 'ohmypi'),
        'pi': ('pi', 'pi-agent', 'pi-coding-agent'),
        'kiro': ('kiro', 'kiro-cli', 'kiro-cli-chat'),
        'vibe': ('vibe', 'vibe-rs', 'mistral-vibe', 'vibe-acp'),
        'muse': ('muse', 'muse-code', 'meta-muse', 'muse-cli'),
    }
    def call(*args):
        return subprocess.check_output([herdr, 'pane', *args], text=True)
    def metadata():
        p = json.loads(call('get', pane))['result']['pane']
        if (p['pane_id'] != pane or p['agent'] not in ALIASES.get(agent, (agent,)) or
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
                call('send-keys', pane, key)
                print('sent')
    except (OSError, ValueError, KeyError, IndexError, TypeError, subprocess.SubprocessError):
        pass
""".trimIndent()
