"""Item times for placing questions in the chat (1.4.3): each reader's record shape gives its time.
Synthetic records in each agent's own shape (field names checked against the agents' logs on
2026-11-14, names only). Run: python3 -I tools/transcript-tests/test_times.py"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_kiro_reader import load, session, RECORDS  # noqa: E402

T = 1_800_000_000_000  # 2027-01-15T08:00:00Z in ms


class Times(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.home = tempfile.mkdtemp()
        cls.t = load(cls.home)

    def test_each_agents_record_shape(self):
        iso = "2027-01-15T08:00:00.000Z"
        shapes = {
            "claude": {"type": "user", "uuid": "u", "timestamp": iso, "message": {"role": "user", "content": "hi"}},
            "codex": {"timestamp": iso, "type": "response_item", "payload": {"type": "message"}},
            "codex payload": {"type": "event_msg", "payload": {"timestamp": iso}},
            "copilot": {"type": "user.message", "id": "e1", "timestamp": iso, "data": {"content": "hi"}},
            "pi / omp": {"type": "message", "id": "m1", "timestamp": iso, "message": {"role": "user"}},
            "pi / omp message": {"type": "message", "message": {"role": "user", "timestamp": T}},
            "kiro": {"version": "v1", "kind": "Prompt", "data": {"message_id": "m1", "meta": {"timestamp": T / 1000}}},
            "vibe": {"id": "e1", "type": "message", "role": "user", "createdAt": iso},
            "opencode (row)": {"time": T},
            "opencode (data)": {"time": {"created": T}},
            "hermes (row, seconds)": {"timestamp": T / 1000},
        }
        for name, rec in shapes.items():
            self.assertEqual(self.t.ts_of(rec), T, name)
        # Grok's chat history has no time; nothing is made up.
        self.assertIsNone(self.t.ts_of({"type": "user", "content": "hi", "prompt_index": 3}))
        self.assertIsNone(self.t.ts_of({"timestamp": "not a time"}))

    def test_items_carry_their_records_time(self):
        items = []
        recs = [(0, {"timestamp": "2027-01-15T08:00:00Z"}), (10, {"note": "no time"}), (20, {"timestamp": T + 5000})]
        for off, r in self.t.stamped(recs, items):
            items.append({"k": "k%d" % off, "t": "text", "text": "x"})
            if off == 0:
                items.append({"k": "k0b", "t": "text", "text": "y"})
        self.assertEqual([i.get("ts") for i in items], [T, T, None, T + 5000])

    def test_kiro_reader_end_to_end(self):
        with tempfile.TemporaryDirectory() as home:
            recs = [dict(r) for r in RECORDS]
            recs[0] = {**recs[0], "data": {**recs[0]["data"], "meta": {"timestamp": T / 1000}}}
            session(home, "s1", "/w/kiro", recs)
            t = load(home)
            r = t.kiro("/w/kiro", {})
            self.assertEqual(r["items"][0]["ts"], T)


if __name__ == "__main__":
    unittest.main()
