"""Synthetic process trees and transcripts, with no access to live agent sessions."""
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from test_kiro_reader import load


def proc(pid, ppid, depth, name, age=10):
    return dict(pid=pid, ppid=ppid, depth=depth, name=name, cmd=name, age=age)


class BackgroundWork(unittest.TestCase):
    def test_runtime_chain_is_not_work_and_only_agent_work_is_exported(self):
        with tempfile.TemporaryDirectory() as home:
            reader = load(home)
            shell = proc(1, 0, 0, "bash")
            launcher = proc(2, 1, 1, "python3")
            unrelated = proc(8, 1, 1, "sleep")
            for agent, parent, runtime in (
                ("opencode", proc(3, 2, 2, "opencode"),
                 [dict(proc(4, 3, 3, "opencode"), sid=4, tty=0)]),
                ("kiro", proc(3, 2, 2, "kiro-cli"),
                 [proc(5, 3, 3, "bun"), dict(proc(4, 5, 4, "kiro-cli"), sid=4, tty=0)]),
            ):
                idle = [shell, launcher, parent, *runtime, unrelated]
                working = idle + [proc(6, 4, 5, "sh"), proc(7, 6, 6, "sleep")]
                self.assertEqual([], reader.active_shells(idle, agent))
                self.assertEqual([6, 7], [p["pid"] for p in reader.active_shells(working, agent)])
                with patch.object(reader, "pane_procs", return_value=(working, set())):
                    result = reader.pane_work("p", agent, "")
                    self.assertEqual([6, 7], [p["pid"] for p in result["active_procs"]])
                    self.assertNotIn("procs", result)
                with (
                    patch.object(reader, "proc_table", return_value={}),
                    patch.object(reader, "pane_procs", side_effect=[(working, set()), (idle, set())]),
                ):
                    self.assertEqual({"p": 2}, reader.work_counts([("p", agent, "", "")])["busy"])
                    self.assertEqual({"p": 0}, reader.work_counts([("p", agent, "", "")])["busy"])
                explicit_agent = proc(9, 3, 3, parent['name'])
                self.assertEqual([9], [p['pid'] for p in reader.active_shells(idle + [explicit_agent], agent)])
            self.assertEqual([], reader.active_shells([shell, unrelated], ""))
            self.assertEqual([], reader.active_shells([shell, dict(launcher, cmd='bash -c opencode')], "opencode"))

    def test_recent_agent_child_counts_but_helpers_and_old_orphans_do_not(self):
        with tempfile.TemporaryDirectory() as home:
            reader = load(home)
            procs = [proc(1, 0, 0, "bash"), proc(2, 1, 1, "codex"),
                     proc(3, 2, 2, "sh", 9), proc(4, 3, 3, "sleep", 9),
                     proc(5, 2, 2, "mcp-server", 8), proc(6, 5, 3, "sleep", 8),
                     proc(7, 99, 0, "sh", 30), proc(8, 7, 1, "sleep", 30)]
            self.assertEqual([3, 4], [p["pid"] for p in reader.active_shells(procs, "codex")])
            self.assertEqual([], reader.active_shells(procs[:2] + procs[4:], "codex"))

    def test_same_directory_activity_needs_one_verified_parent_and_clears_on_completion(self):
        with tempfile.TemporaryDirectory() as home:
            reader = load(home)
            project = Path(home, ".claude", "projects", "-work-demo")
            subdir = project / "first" / "subagents"
            subdir.mkdir(parents=True)
            first, second = project / "first.jsonl", project / "second.jsonl"
            first.touch()
            sublog = subdir / "agent-worker.jsonl"
            sublog.write_text(json.dumps({"message": {"role": "assistant", "stop_reason": "tool_use"}}))
            second.touch()
            output = Path(home, "task.output")
            output.touch()
            procs = {"p1": [proc(1, 0, 0, "claude")], "p2": [proc(2, 0, 0, "claude")]}
            descriptors = {1: {str(first), str(output)}, 2: {str(second)}}

            def task_files(cwd, log, opened, titles):
                self.assertFalse(titles)
                return [{"running": str(output) in opened}] if log == str(first) else []

            panes = [("p1", "claude", "/work/demo", ""), ("p2", "claude", "/work/demo", "second")]
            with (
                patch.object(reader, "proc_table", return_value={1: (), 2: ()}),
                patch.object(reader, "pane_of", side_effect={1: "p1", 2: "p2"}.get),
                patch.object(reader, "pane_procs", side_effect=lambda p, _, ids: (procs[p], ids)),
                patch.object(reader, "open_files_of", side_effect=lambda pids: set().union(*(descriptors[p] for p in pids))),
                patch.object(reader, "claude_tasks", side_effect=task_files),
            ):
                self.assertEqual({"p1": 2, "p2": 0}, reader.work_counts(panes)["busy"])
                descriptors[1].add(str(second))
                self.assertEqual({"p1": 0, "p2": 0}, reader.work_counts(panes)["busy"])
                self.assertIsNone(reader.claude_work_log("/work/demo", "../first", 1))
                self.assertEqual(str(first), reader.claude_work_log("/work/demo", "first", 1))
                self.assertIsNone(reader.claude_work_log("/work/demo", "cleared", 1))
                descriptors[1] = {str(first)}
                sublog.with_suffix(".meta.json").write_text('{"finished": true}')
                self.assertEqual({"p1": 0, "p2": 0}, reader.work_counts(panes)["busy"])
                self.assertFalse(reader.subagent_active(sublog.as_posix(), sublog.stat(), time.time()))


if __name__ == "__main__":
    unittest.main()
