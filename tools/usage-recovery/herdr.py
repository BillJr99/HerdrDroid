#!/usr/bin/python3
"""Synthetic recent/visible pane views and a log of accepted keys; never invokes Herdr."""
import json
import pathlib
import sys

state = pathlib.Path('/fixture/state')
mode = state / 'mode'
keys = state / 'keys'
draft = '❯ synthetic existing draft'
usage = 'Status   Config   Usage\nCurrent session\nLoading…\nEsc to close'
approval = 'Approve this tool call\n❯ 1. Yes\n2. No\nEsc to cancel'
pane = 'w1:p1'
match sys.argv[1:]:
    case ['reset']:
        mode.write_text('usage')
        keys.write_text('')
    case ['approval']:
        mode.write_text('approval')
    case ['draft']:
        print(draft)
    case ['keys']:
        print(keys.read_text(), end='')
    case ['pane', 'get', p] if p == pane:
        print(json.dumps({'result': {'pane': {'pane_id': pane, 'agent': 'claude',
            'workspace_id': 'w1', 'tab_id': 'w1:t1', 'agent_status': 'idle',
            'scroll': {'offset_from_bottom': 0}}}}))
    case ['pane', 'process-info', '--pane', p] if p == pane:
        print(json.dumps({'result': {'process_info': {'pane_id': pane, 'shell_pid': 1,
            'foreground_process_group_id': 1}}}))
    case ['pane', 'read', p, '--source', 'visible'] if p == pane:
        print({'usage': usage, 'approval': approval, 'draft': draft}[mode.read_text()])
    case ['pane', 'read', p, '--source', 'recent', '--lines', _, '--format', 'ansi'] if p == pane:
        # A successful recent capture need not include the current live modal.
        print(draft)
    case ['pane', 'send-keys', p, 'esc'] if p == pane:
        with keys.open('a') as log:
            log.write('esc\n')
        assert mode.read_text() == 'usage', 'Escape reached a non-usage screen'
        mode.write_text('draft')
    case other:
        raise AssertionError(other)
