#!/usr/bin/python3
"""Synthetic CLI for CardPreviewReadTest. No real pane or input commands."""
import sys
import time
from pathlib import Path

root = Path(__file__).parent
args = sys.argv[1:]
if args == ["bulk"]:
    (root / "bulk-started").touch()
    while not (root / "release").exists():
        time.sleep(0.01)
    sys.exit(0)

source = "unexpected"
if args[:4] == ["pane", "read", "p1", "--source"]:
    if args[4:] == ["visible"]:
        source = "visible"
    elif args[4] == "recent" and args[-2:] == ["--format", "ansi"]:
        source = "recent"
with (root / "preview-trace").open("a") as trace:
    print(source, file=trace)
if source == "unexpected":
    sys.exit(2)
sys.stdout.write((root / source).read_text())
