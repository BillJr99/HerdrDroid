"""The Muse Code reader in app/src/main/assets/transcript.py (1.4.5), on a session store laid out the
way Muse Code 1.4.20 writes it: ~/.local/share/muse/session-index.db (table sessions) naming each
session's session.jsonl, a .session.lock with the running pid. Records are synthetic, in the shape of
a live session (field names checked 2026-11-15). Run: python3 -I tools/transcript-tests/test_muse_reader.py"""
import fcntl
import json
import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_kiro_reader import load  # noqa: E402

US = 1_800_000_000_000_000  # microseconds


def ev(seq, event, run="r1"):
    return {"id": "e%d" % seq, "payload_type": "runtime.session", "record_type": "event", "recorded_at": US + seq * 1000,
            "schema_version": 1, "sequence": seq, "stream": {"kind": "session", "id": "s"},
            "payload": {"kind": "run", "run_id": run, "event": event}}


RECORDS = [
    {"id": "x", "payload_type": "session.started", "payload": {"kind": "session"}, "recorded_at": US},
    ev(1, {"kind": "started", "prompt": "run echo hi"}),
    ev(2, {"kind": "reasoning_summary_delta", "message_id": "m1", "summary_index": 0, "text": "Running it."}),
    ev(3, {"kind": "reasoning_committed", "message_id": "m1", "text": "Running it.", "encrypted_content": "x"}),
    ev(4, {"kind": "assistant_tool_calls_committed", "message_id": "m2", "tool_calls": [
        {"args": json.dumps({"command": "echo hi", "description": "Run echo hi"}), "call_id": "c1", "id": "fc1", "name": "bash"},
        {"args": json.dumps({"command": "false"}), "call_id": "c2", "id": "fc2", "name": "bash"}]}),
    ev(5, {"kind": "tool_result_batch_committed", "batch_id": "m2", "results": [
        {"text": json.dumps({"command": "echo hi", "exit_code": 0, "output": "hi\n"}), "tool_call_id": "c1", "tool_call_index": 0},
        {"text": "tool failed: environment failure: no sandbox", "tool_call_id": "c2", "tool_call_index": 1}]}),
    ev(6, {"kind": "assistant_message_committed", "message_id": "m3", "text": "It printed hi."}),
    ev(7, {"kind": "terminal", "terminal": "completed", "reason": None}),
    ev(8, {"kind": "goal_usage_attribution", "record": {}}),
]


def store(home, cwd, sessions):
    """sessions: [(id, records, updated_us, pid or None)]"""
    root = os.path.join(home, ".local", "share", "muse")
    os.makedirs(root, exist_ok=True)
    db = sqlite3.connect(os.path.join(root, "session-index.db"))
    db.execute("create table sessions (session_id text primary key, session_log_path text, workspace_root text, "
               "title text, updated_at_us integer, msp_kind text)")
    for sid, recs, upd, pid in sessions:
        d = os.path.join(root, "sessions", "2027", "01", "15", sid)
        os.makedirs(d, exist_ok=True)
        log = os.path.join(d, "session.jsonl")
        with open(log, "w") as fh:
            for r in recs:
                fh.write(json.dumps(r) + "\n")
        with open(os.path.join(d, ".session.lock"), "w") as fh:
            fh.write("pid=%d\nhost=h\n" % pid if pid else "")
        db.execute("insert into sessions values (?, ?, ?, ?, ?, 'root')", (sid, log, cwd, "title " + sid, upd))
    db.commit()
    db.close()


class MuseReader(unittest.TestCase):
    def test_reads_prompt_reasoning_tools_results_and_reply(self):
        with tempfile.TemporaryDirectory() as home:
            store(home, "/w/muse", [("s1", RECORDS, 5, None)])
            t = load(home)
            r = t.muse("/w/muse", {})
            self.assertEqual([i["t"] for i in r["items"]], ["user", "think", "tool", "tool", "result", "result", "text"])
            self.assertEqual(r["items"][0]["text"], "run echo hi")
            self.assertEqual(r["items"][1]["text"], "Running it.")  # the committed copy is not repeated
            self.assertEqual(r["items"][2]["title"], "$ echo hi")
            ok, bad = [i for i in r["items"] if i["t"] == "result"]
            self.assertEqual((ok["ref"], ok["out"], ok["err"]), ("c1", "hi\n", False))
            self.assertTrue(bad["err"])
            self.assertEqual(r["items"][0]["ts"], (US + 1000) // 1000)
            self.assertEqual(r["title"], "title s1")
            # The next read from the cursor has nothing new.
            self.assertEqual(t.muse("/w/muse", r["cur"])["items"], [])

    def test_the_session_a_live_muse_holds_wins_over_a_newer_one(self):
        with tempfile.TemporaryDirectory() as home:
            other = [ev(1, {"kind": "started", "prompt": "headless exec"})]
            store(home, "/w/muse", [("live", RECORDS, 1, os.getpid()), ("exec", other, 9, None)])
            t = load(home)
            # A pid line alone (left by a Muse that exited, its pid now reused) is not live (from Grok's review).
            self.assertEqual(t.muse("/w/muse", {})["items"][0]["text"], "headless exec")
            # Held the way Muse holds it: a flock on .session.lock.
            lock = os.path.join(home, ".local", "share", "muse", "sessions", "2027", "01", "15", "live", ".session.lock")
            with open(lock) as fh:
                fcntl.flock(fh, fcntl.LOCK_EX)
                self.assertEqual(t.muse("/w/muse", {})["items"][0]["text"], "run echo hi")
            # Without a live lock, the newest for the folder.
            self.assertIsNone(t.muse("/w/other", {}))
            sess = t.list_sessions("muse", "/w/muse")
            self.assertEqual(len(sess), 2)

    def test_a_turn_that_did_not_complete_is_noted(self):
        with tempfile.TemporaryDirectory() as home:
            store(home, "/w/muse", [("s1", [ev(1, {"kind": "terminal", "terminal": "cancelled", "reason": "interrupted"})], 1, None)])
            t = load(home)
            self.assertEqual(t.muse("/w/muse", {})["items"][0]["text"], "Turn ended: interrupted")


if __name__ == "__main__":
    unittest.main()
