"""The Mistral Vibe reader in app/src/main/assets/transcript.py, on a session store laid out the way
Vibe 3.1.2 writes it (meta.json, CURRENT, generations/<n>/manifest.json, chunks/<sha>.json).
Run: python3 -I tools/transcript-tests/test_vibe_reader.py"""
import json
import os
import sys
import tempfile
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "transcript.py")


def load(home):
    """The script without its last line, which runs main() as soon as the file is loaded."""
    os.environ["HOME"] = home
    with open(SCRIPT) as fh:
        src = fh.read().rstrip()
    assert src.endswith("main()")
    mod = types.ModuleType("transcript_under_test")
    mod.__file__ = SCRIPT
    exec(compile(src[: -len("main()")], SCRIPT, "exec"), mod.__dict__)
    mod.HOME = home
    return mod


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh)


ENTRIES = [
    {"type": "message", "role": "user", "id": "u1", "content": [{"type": "text", "text": "Run ls"}]},
    {"type": "reasoning", "id": "r1", "text": "I will run ls."},
    {"type": "effect", "id": "e1", "detail": {"kind": "tool", "toolName": "file_system.bash", "input": {"command": "ls -la"}},
     "state": {"status": "completed", "output": {"type": "success", "structured_content": {"stdout": "a\nb\n", "stderr": "", "returncode": 0}}}},
    {"type": "message", "role": "assistant", "id": "a1", "content": [{"type": "text", "text": "2"}]},
]


def session(home, sid, cwd, gen, entries):
    d = os.path.join(home, ".vibe", "logs", "session", "unified", sid)
    write(os.path.join(d, "meta.json"), {"session_id": sid, "environment": {"working_directory": cwd}, "origin_directory": cwd})
    write(os.path.join(d, "CURRENT"), {"generation": gen, "session_id": sid})
    write(os.path.join(d, "chunks", "c" + gen + ".json"), entries)
    write(os.path.join(d, "generations", gen, "manifest.json"), {"projection_state": {"chunks": ["c" + gen]}})
    return os.path.join(d, "meta.json")


class VibeReader(unittest.TestCase):
    def test_reads_messages_thinking_and_tools(self):
        with tempfile.TemporaryDirectory() as home:
            session(home, "s1", "/w/vibe", "0001", ENTRIES)
            t = load(home)
            r = t.vibe("/w/vibe", {})
            self.assertTrue(r["reset"])
            kinds = [i["t"] for i in r["items"]]
            self.assertEqual(kinds, ["user", "think", "tool", "text"])
            tool = r["items"][2]
            self.assertEqual(tool["title"], "$ ls -la")
            self.assertEqual(tool["state"], "ok")
            self.assertIn("a\nb", tool["out"])
            # Nothing new in the same snapshot.
            again = t.vibe("/w/vibe", r["cur"])
            self.assertEqual(again["items"], [])
            self.assertFalse(again["reset"])

    def test_new_snapshot_sends_only_new_entries(self):
        with tempfile.TemporaryDirectory() as home:
            meta = session(home, "s1", "/w/vibe", "0001", ENTRIES[:1])
            t = load(home)
            cur = t.vibe("/w/vibe", {})["cur"]
            session(home, "s1", "/w/vibe", "0002", ENTRIES)
            r = t.vibe("/w/vibe", cur)
            self.assertFalse(r["reset"])
            self.assertEqual([i["t"] for i in r["items"]], ["think", "tool", "text"])
            self.assertEqual(r["cur"]["file"], meta)

    def test_other_folders_and_session_list(self):
        with tempfile.TemporaryDirectory() as home:
            session(home, "s1", "/w/other", "0001", ENTRIES)
            t = load(home)
            self.assertIsNone(t.vibe("/w/vibe", {}))
            session(home, "s2", "/w/vibe", "0001", ENTRIES)
            listed = t.list_sessions("vibe", "/w/vibe")
            self.assertEqual(len(listed), 1)
            self.assertEqual(listed[0]["title"], "Run ls")


if __name__ == "__main__":
    unittest.main(verbosity=1)
