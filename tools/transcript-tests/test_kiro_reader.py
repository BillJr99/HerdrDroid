"""The Kiro reader in app/src/main/assets/transcript.py (1.4.1), on a session store laid out the way
Kiro CLI writes it (~/.kiro/sessions/cli/<id>.json with the folder, <id>.jsonl with the records).
Synthetic records only. Run: python3 -I tools/transcript-tests/test_kiro_reader.py"""
import json
import os
import sys
import tempfile
import time
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "transcript.py")


def load(home):
    os.environ["HOME"] = home
    with open(SCRIPT) as fh:
        src = fh.read().rstrip()
    assert src.endswith("main()")
    mod = types.ModuleType("transcript_under_test")
    mod.__file__ = SCRIPT
    exec(compile(src[: -len("main()")], SCRIPT, "exec"), mod.__dict__)
    mod.HOME = home
    return mod


RECORDS = [
    {"version": "v1", "kind": "Prompt", "data": {"message_id": "m1", "content": [{"kind": "text", "data": "list the files"}], "meta": {"timestamp": 1}}},
    {"version": "v1", "kind": "AssistantMessage", "data": {"message_id": "m2", "content": [
        {"kind": "thinking", "data": {"text": "I will run ls.", "signature": "x"}},
        {"kind": "text", "data": "Running ls."},
        {"kind": "toolUse", "data": {"toolUseId": "t1", "name": "execute_bash", "input": {"command": "ls -la"}}}]}},
    {"version": "v1", "kind": "ToolResults", "data": {"message_id": "m3", "content": [
        {"kind": "toolResult", "data": {"toolUseId": "t1", "content": [{"kind": "text", "data": "a\nb"}], "status": "Success"}}]}},
    {"version": "v1", "kind": "AssistantMessage", "data": {"message_id": "m4", "content": [{"kind": "text", "data": "Two files."}]}},
]


def session(home, sid, cwd, records, title="ls"):
    d = os.path.join(home, ".kiro", "sessions", "cli")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, sid + ".json"), "w") as fh:
        json.dump({"session_id": sid, "cwd": cwd, "title": title}, fh)
    with open(os.path.join(d, sid + ".jsonl"), "w") as fh:
        for r in records:
            fh.write(json.dumps(r) + "\n")


class KiroReader(unittest.TestCase):
    def test_reads_messages_thinking_tools_and_results(self):
        with tempfile.TemporaryDirectory() as home:
            session(home, "s1", "/w/kiro", RECORDS)
            t = load(home)
            r = t.kiro("/w/kiro", {})
            self.assertTrue(r["reset"])
            self.assertEqual([i["t"] for i in r["items"]], ["user", "think", "text", "tool", "result", "text"])
            tool = [i for i in r["items"] if i["t"] == "tool"][0]
            self.assertEqual(tool["id"], "t1")
            res = [i for i in r["items"] if i["t"] == "result"][0]
            self.assertEqual(res["ref"], "t1")
            self.assertFalse(res.get("err"))
            # The output itself, from the text blocks' "data" (1.4.1, from a code review).
            self.assertEqual(res["out"], "a\nb")
            self.assertEqual(r["title"], "ls")

    def test_the_session_whose_log_was_written_last(self):
        # Two sessions in one folder: the one being written to wins, even when the other's .json
        # is newer (1.4.1, from a code review).
        with tempfile.TemporaryDirectory() as home:
            d = os.path.join(home, ".kiro", "sessions", "cli")
            session(home, "old", "/w/kiro", RECORDS[:1], title="old")
            session(home, "live", "/w/kiro", RECORDS, title="live")
            now = time.time()
            os.utime(os.path.join(d, "old.jsonl"), (now - 600, now - 600))
            os.utime(os.path.join(d, "live.jsonl"), (now, now))
            os.utime(os.path.join(d, "old.json"), (now + 60, now + 60))
            os.utime(os.path.join(d, "live.json"), (now - 900, now - 900))
            t = load(home)
            self.assertEqual(t.kiro("/w/kiro", {})["title"], "live")

    def test_a_file_removed_while_sorting_counts_as_oldest(self):
        with tempfile.TemporaryDirectory() as home:
            t = load(home)
            self.assertEqual(t.mtime(os.path.join(home, "gone")), 0)

    def test_only_this_folders_newest_session_and_deltas(self):
        with tempfile.TemporaryDirectory() as home:
            session(home, "other", "/w/elsewhere", RECORDS)
            session(home, "s1", "/w/kiro", RECORDS[:1])
            t = load(home)
            r = t.kiro("/w/kiro", {})
            self.assertEqual([i["t"] for i in r["items"]], ["user"])
            # More records are appended: the next read from the cursor returns only them.
            with open(os.path.join(home, ".kiro", "sessions", "cli", "s1.jsonl"), "a") as fh:
                fh.write(json.dumps(RECORDS[3]) + "\n")
            r2 = t.kiro("/w/kiro", r["cur"])
            self.assertFalse(r2["reset"])
            self.assertEqual([i["t"] for i in r2["items"]], ["text"])
            self.assertIsNone(t.kiro("/w/none", {}))


if __name__ == "__main__":
    unittest.main()
