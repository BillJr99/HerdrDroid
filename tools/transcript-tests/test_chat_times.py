"""Message times for the chat (2.1.4, Bill: "grok isn't showing timestamps make sure all panes do in
chat"). Grok's chat history has no times; the reader takes them from the session's updates.jsonl
(tool calls by id, messages by their text) and rewind_points.jsonl (prompts by index). Kiro's log
times only prompts; the reader takes each reply's time from the turn's end in <id>.json. Both are
"at" (a label), never "ts" (which places questions). Synthetic records in each agent's own shape
(field names checked against the agents' files on 2026-11-21, names only).
Run: python3 -I tools/transcript-tests/test_chat_times.py"""
import json
import os
import sys
import tempfile
import unittest
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_kiro_reader import load, session, RECORDS  # noqa: E402

T = 1_795_295_147_000  # 2026-11-21T21:05:47Z in ms


def grok_session(home, cwd, history, updates, rewind):
    d = os.path.join(home, ".grok", "sessions", urllib.parse.quote(cwd, safe=""), "s1")
    os.makedirs(d, exist_ok=True)
    for name, recs in (("chat_history.jsonl", history), ("updates.jsonl", updates), ("rewind_points.jsonl", rewind)):
        with open(os.path.join(d, name), "w") as fh:
            for r in recs:
                fh.write(json.dumps(r) + "\n")
    return d


def upd(ms, update):
    return {"method": "session/update", "timestamp": ms // 1000, "timestamp_us": ms * 1000,
            "params": {"sessionId": "s1", "_meta": {"agentTimestampMs": ms, "eventId": "e"}, "update": update}}


def chunk(kind, ms, text):
    return upd(ms, {"sessionUpdate": kind, "content": {"type": "text", "text": text}})


HISTORY = [
    {"type": "system", "content": "system prompt"},
    {"type": "user", "content": [{"type": "text", "text": "<user_query>\nFrom claude: review the diff\n</user_query>"}], "prompt_index": 7},
    {"type": "reasoning", "id": "r1", "status": "completed", "summary": [{"type": "summary_text", "text": "Reading the diff first."}], "encrypted_content": "x"},
    {"type": "assistant", "content": "I am reviewing the `diff` now.", "tool_calls": [{"id": "call_1", "name": "run_command", "arguments": "{\"command\": \"git diff\"}"}],
     "model_id": "m", "model_fingerprint": "f", "reasoning_effort": "high"},
    {"type": "tool_result", "tool_call_id": "call_1", "content": "diff text"},
    {"type": "assistant", "content": "done", "model_id": "m", "model_fingerprint": "f", "reasoning_effort": "high"},
    {"type": "user", "content": [{"type": "text", "text": "<user_query>\nFrom claude: again\n</user_query>"}], "prompt_index": 8},
    {"type": "assistant", "content": "done", "model_id": "m", "model_fingerprint": "f", "reasoning_effort": "high"},
]
UPDATES = [
    chunk("agent_message_chunk", T - 3_600_000, "done"),  # an older turn's "done"
    upd(T - 3_599_000, {"sessionUpdate": "turn_completed"}),
    chunk("user_message_chunk", T, "From claude: review the diff"),
    chunk("agent_thought_chunk", T + 2_000, "Reading the "),
    chunk("agent_thought_chunk", T + 2_100, "diff first."),
    chunk("agent_message_chunk", T + 9_000, "I am reviewing the `diff` now."),
    upd(T + 9_500, {"sessionUpdate": "tool_call", "toolCallId": "call_1", "title": "git diff"}),
    upd(T + 10_000, {"sessionUpdate": "tool_call_update", "toolCallId": "call_1", "status": "completed"}),
    chunk("agent_message_chunk", T + 60_000, "done"),
    upd(T + 61_000, {"sessionUpdate": "turn_completed"}),
    chunk("user_message_chunk", T + 120_000, "From claude: again"),
    chunk("agent_message_chunk", T + 125_000, "done"),
]
REWIND = [{"prompt_index": 7, "created_at": "2026-11-21T21:05:47.000Z", "file_snapshots": {}, "after_snapshots": {}},
          {"prompt_index": 8, "created_at": "2026-11-21T21:07:47.000Z", "file_snapshots": {}, "after_snapshots": {}}]


class GrokTimes(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp()
        self.t = load(self.home)

    def by_text(self, r):
        return [(i["t"], i.get("text") or i.get("name"), i.get("at"), i.get("ts")) for i in r["items"] if i["t"] != "result"]

    def test_every_message_takes_the_time_grok_showed_it(self):
        grok_session(self.home, "/w/grok", HISTORY, UPDATES, REWIND)
        r = self.t.grok("/w/grok", {})
        self.assertEqual(self.by_text(r), [
            ("user", "From claude: review the diff", T, None),
            ("think", "Reading the diff first.", T + 2_000, None),
            ("text", "I am reviewing the `diff` now.", T + 9_000, None),
            ("tool", "run_command", T + 9_500, None),
            ("text", "done", T + 60_000, None),
            ("user", "From claude: again", T + 120_000, None),
            ("text", "done", T + 125_000, None),
        ])

    def test_a_live_read_of_the_newest_messages(self):
        d = grok_session(self.home, "/w/grok", HISTORY[:6], UPDATES[:10], REWIND[:1])
        first = self.t.grok("/w/grok", {})
        with open(os.path.join(d, "chat_history.jsonl"), "a") as fh:
            for r in HISTORY[6:]:
                fh.write(json.dumps(r) + "\n")
        with open(os.path.join(d, "updates.jsonl"), "a") as fh:
            for r in UPDATES[10:]:
                fh.write(json.dumps(r) + "\n")
        with open(os.path.join(d, "rewind_points.jsonl"), "a") as fh:
            fh.write(json.dumps(REWIND[1]) + "\n")
        r = self.t.grok("/w/grok", first["cur"])
        self.assertFalse(r["reset"])
        self.assertEqual(self.by_text(r), [("user", "From claude: again", T + 120_000, None), ("text", "done", T + 125_000, None)])

    def test_without_updates_or_rewind_points_nothing_is_made_up(self):
        grok_session(self.home, "/w/grok", HISTORY, [], [])
        r = self.t.grok("/w/grok", {})
        self.assertTrue(all("at" not in i and "ts" not in i for i in r["items"]))

    def test_the_reply_shows_its_own_turns_time_not_an_older_identical_one(self):
        grok_session(self.home, "/w/grok", HISTORY[:6], UPDATES[:10], REWIND)
        r = self.t.grok("/w/grok", {})
        done = [i for i in r["items"] if i.get("text") == "done"]
        self.assertEqual([i["at"] for i in done], [T + 60_000])


class KiroTimes(unittest.TestCase):
    def test_a_reply_takes_its_turns_end(self):
        with tempfile.TemporaryDirectory() as home:
            session(home, "s1", "/w/kiro", RECORDS)
            meta_path = os.path.join(home, ".kiro", "sessions", "cli", "s1.json")
            with open(meta_path) as fh:
                meta = json.load(fh)
            meta["session_state"] = {"conversation_metadata": {"user_turn_metadatas": [
                {"message_ids": ["m1", "m2", "m3", "m4"], "end_timestamp": "2026-11-21T21:05:47.000Z"}]}}
            with open(meta_path, "w") as fh:
                json.dump(meta, fh)
            t = load(home)
            r = t.kiro("/w/kiro", {})
            last = [i for i in r["items"] if i["t"] == "text"][-1]
            self.assertEqual(last["text"], "Two files.")
            self.assertEqual(last["at"], T)
            self.assertNotIn("ts", last)
            # The prompt keeps its own logged time; the steps in between have none of their own.
            self.assertEqual(r["items"][0]["ts"], 1000)
            self.assertTrue(all("at" not in i for i in r["items"][1:-1]))
            self.assertEqual(r["title"], "ls")

    def test_no_turn_records_no_reply_time(self):
        with tempfile.TemporaryDirectory() as home:
            session(home, "s1", "/w/kiro", RECORDS)
            t = load(home)
            r = t.kiro("/w/kiro", {})
            self.assertTrue(all("at" not in i for i in r["items"]))


class HostZone(unittest.TestCase):
    def test_utc_offset_in_minutes(self):
        t = load(tempfile.mkdtemp())
        self.assertIsInstance(t.tz_minutes(), int)
        self.assertTrue(-18 * 60 <= t.tz_minutes() <= 18 * 60)


if __name__ == "__main__":
    unittest.main()
