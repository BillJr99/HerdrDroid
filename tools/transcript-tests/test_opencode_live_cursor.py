"""OpenCode cursor readback must stay bound to the named live pane."""

from contextlib import closing, redirect_stdout
import io
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from test_kiro_reader import load


class OpenCodeLiveCursor(unittest.TestCase):
    cwd = "/fixture"
    target = "ses_target"
    neighbor = "ses_neighbor"
    notice = "Synthetic notice\nSecond line"

    def setUp(self):
        home = self.enterContext(tempfile.TemporaryDirectory())
        self.enterContext(patch.dict(os.environ))
        self.reader = load(home)
        self.enterContext(patch.object(self.reader, "pane_procs", return_value=([], set())))
        self.db = Path(home, ".local/share/opencode/opencode.db")
        self.db.parent.mkdir(parents=True)
        self.connection = self.enterContext(closing(sqlite3.connect(self.db)))
        self.connection.executescript("""
            CREATE TABLE session_v2 (id TEXT, title TEXT, directory TEXT,
                parent_id TEXT, time_archived INTEGER, time_updated INTEGER);
            CREATE TABLE session_message (id TEXT, session_id TEXT, type TEXT,
                seq INTEGER, data TEXT, time_created INTEGER);
        """)
        self.connection.executemany(
            "INSERT INTO session_v2 VALUES (?, ?, ?, NULL, NULL, ?)",
            [(self.target, "Target", self.cwd, 1), (self.neighbor, "Neighbor", self.cwd, 2)],
        )
        self.add_user(self.target, 1, "Target seed")
        self.add_user(self.neighbor, 1, "Neighbor seed")

    def add_user(self, session, sequence, text):
        self.connection.execute(
            "INSERT INTO session_message VALUES (?, ?, 'user', ?, ?, ?)",
            (f"{session}-{sequence}", session, sequence, json.dumps({"text": text}), sequence),
        )
        self.connection.commit()

    def read(self, pane, session, cursor=None):
        self.reader.PIN.clear()
        self.reader.LIVE.clear()
        args = {**(cursor or {}), "pane": pane, "hsid": session}
        stdout = io.StringIO()
        with (
            patch.object(self.reader.sys, "argv", ["transcript.py", "opencode", self.cwd, json.dumps(args)]),
            redirect_stdout(stdout),
            self.assertRaises(SystemExit) as stopped,
        ):
            self.reader.main()
        self.assertEqual(0, stopped.exception.code)
        result = json.loads(stdout.getvalue())
        self.assertTrue(result["ok"], result)
        return result

    def user_text(self, result):
        return [item["text"] for item in result["items"] if item["t"] == "user"]

    def test_live_sqlite_cursor_keeps_pane_binding_on_followup_reads(self):
        before = self.read("w1:p1", self.target)
        neighbor_before = self.read("w1:p2", self.neighbor)
        self.assertEqual(["Target seed"], self.user_text(before))
        self.assertEqual(["Neighbor seed"], self.user_text(neighbor_before))
        self.assertEqual(str(self.db), before["cur"]["db"])
        self.add_user(self.target, 2, self.notice)
        with self.connection:
            self.connection.execute("UPDATE session_v2 SET time_updated = 3 WHERE id = ?", (self.target,))

        after = self.read("w1:p1", self.target, before["cur"])
        neighbor_after = self.read("w1:p2", self.neighbor, neighbor_before["cur"])
        self.assertEqual(before["live"], after["live"])
        self.assertFalse(after["reset"])
        self.assertIn(self.notice, self.user_text(after))
        self.assertEqual(neighbor_before["live"], neighbor_after["live"])
        self.assertFalse(neighbor_after["reset"])
        self.assertEqual(["Neighbor seed"], self.user_text(neighbor_after))

    def test_explicit_history_pin_stays_on_its_session(self):
        pinned = self.read("w1:p2", self.neighbor, {"pin_sid": self.target, "db": str(self.db)})
        self.assertNotIn("live", pinned)
        self.assertEqual(["Target seed"], self.user_text(pinned))
        self.assertEqual(self.target, pinned["cur"]["pin_sid"])


if __name__ == "__main__":
    unittest.main()
