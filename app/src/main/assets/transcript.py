"""HerdrDroid transcript reader.

HerdrDroid sends this script over its SSH connection on every poll (python3 - <agent> <cwd> <cursor>)
and nothing is installed on the computer. It finds the session log the agent in a pane is writing,
reads what was added since the cursor, and prints one JSON object of display items:

  {"ok": true, "src": agent, "cur": {...}, "reset": bool, "more": bool, "title": str|null,
   "items": [...]}

Items (all carry "k", a key that is stable across polls, so a re-sent item replaces the old one):
  {"t": "user", "text"}            a message you sent
  {"t": "text", "text"}            the agent's reply (Markdown)
  {"t": "think", "text", "ms"}     reasoning, when the agent records it
  {"t": "tool", "id", "name", "title", "input", "diff"?, "patch"?, "out"?, "err"?, "state"}
  {"t": "result", "ref", "out", "err"}   a tool's output, matched to its call by id
  {"t": "note", "text"}            a quiet line: a slash command, a compaction, an interruption

Only reads. Every log is opened read-only, and the SQLite ones in read-only URI mode. The only
tables read are the message tables named below.
"""
import glob
import json
import os
import re
import sqlite3
import sys
import time
import urllib.parse

HOME = os.path.expanduser("~")
TAIL_BYTES = 1_500_000       # how much of a long log the first read shows
DELTA_BYTES = 2 * TAIL_BYTES  # the most one read takes after its cursor; the next read goes on from there (1.3.0)
FIRST_ROWS = 400             # the same for the SQLite logs
OUT_CHARS = 6000             # a tool's output is cut here
IN_CHARS = 4000              # and its input here


def done(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False))
    sys.exit(0)


def clip(v, n):
    if v is None:
        return None
    s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False, indent=1)
    return s if len(s) <= n else s[:n] + "\n… (%d more characters)" % (len(s) - n)


def text_of(content):
    """The plain text in a content field: a string, or a list of text blocks."""
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for b in content:
            if isinstance(b, str):
                parts.append(b)
            elif isinstance(b, dict):
                if isinstance(b.get("text"), str):
                    parts.append(b["text"])
                elif b.get("type") in ("image", "input_image"):
                    parts.append("[image]")
                elif isinstance(b.get("content"), (str, list)):
                    parts.append(text_of(b["content"]))
        return "\n".join(p for p in parts if p)
    if isinstance(content, dict):
        for k in ("text", "output", "content", "stdout"):
            if isinstance(content.get(k), (str, list)):
                return text_of(content[k])
    return json.dumps(content, ensure_ascii=False)


def as_args(v):
    if isinstance(v, dict):
        return v
    if isinstance(v, str):
        try:
            p = json.loads(v)
            return p if isinstance(p, dict) else {"input": v}
        except Exception:
            return {"input": v}
    return {}


def base(p):
    return os.path.basename(str(p).rstrip("/")) or str(p)


def first_line(s, n=90):
    s = str(s).strip().splitlines()[0] if str(s).strip() else ""
    return s if len(s) <= n else s[: n - 1] + "…"


SHELL = {"bash", "shell", "exec_command", "run_shell_command", "terminal", "run_terminal_cmd", "execute",
         "local_shell", "container.exec", "run_command", "command"}
READ = {"read", "read_file", "view", "cat", "open_file", "readfile"}
WRITE = {"write", "write_file", "create_file", "writefile"}
EDIT = {"edit", "multiedit", "str_replace", "str_replace_editor", "edit_file", "replace", "apply_patch", "patch",
        "search_replace", "editfile", "notebookedit"}
SEARCH = {"grep", "search", "ripgrep", "rg", "search_files", "codebase_search", "grep_search", "file_search"}
FIND = {"glob", "find", "list", "ls", "list_dir", "list_directory", "find_files"}


def tool_item(key, call_id, name, args, out=None, err=None, state="run"):
    name = name or "tool"
    a = as_args(args)
    low = name.lower().split("__")[-1].split(".")[-1]
    path = a.get("file_path") or a.get("path") or a.get("target_file") or a.get("filePath") or a.get("filename")
    cmd = a.get("command") or a.get("cmd")
    if isinstance(cmd, list):
        cmd = " ".join(str(x) for x in cmd if x not in ("bash", "-lc", "-c")) if cmd else ""
    item = {"k": key, "t": "tool", "id": call_id, "name": name, "state": state}
    if low in SHELL or (cmd and low not in EDIT):
        item["title"] = "$ " + first_line(cmd or a.get("input") or "")
        item["kind"] = "shell"
    elif low in READ:
        item["title"] = "Read " + base(path or "")
        item["kind"] = "read"
    elif low in WRITE:
        item["title"] = "Write " + base(path or "")
        item["kind"] = "edit"
        if isinstance(a.get("content"), str):
            item["diff"] = {"path": path, "old": "", "new": clip(a["content"], IN_CHARS)}
    elif low in EDIT:
        item["kind"] = "edit"
        if low == "apply_patch" or "patch" in a or (isinstance(a.get("input"), str) and "*** Begin Patch" in a["input"]):
            patch = a.get("patch") or a.get("input") or ""
            files = re.findall(r"\*\*\* (?:Update|Add|Delete) File: (.+)", patch)
            item["title"] = "Edit " + (", ".join(base(f) for f in files[:3]) if files else "files")
            item["patch"] = clip(patch, IN_CHARS)
        elif isinstance(a.get("edits"), list):
            item["title"] = "Edit " + base(path or "")
            old = "\n".join(str(e.get("old_string") or e.get("oldText") or "") for e in a["edits"] if isinstance(e, dict))
            new = "\n".join(str(e.get("new_string") or e.get("newText") or "") for e in a["edits"] if isinstance(e, dict))
            item["diff"] = {"path": path, "old": clip(old, IN_CHARS), "new": clip(new, IN_CHARS)}
        else:
            item["title"] = "Edit " + base(path or "")
            old = a.get("old_string") or a.get("oldString") or a.get("old_str") or a.get("oldText")
            new = a.get("new_string") or a.get("newString") or a.get("new_str") or a.get("newText")
            if old is not None or new is not None:
                item["diff"] = {"path": path, "old": clip(old or "", IN_CHARS), "new": clip(new or "", IN_CHARS)}
    elif low in SEARCH:
        item["title"] = "Search " + first_line(a.get("pattern") or a.get("query") or a.get("regex") or "", 60)
        item["kind"] = "search"
    elif low in FIND:
        item["title"] = "Find " + first_line(a.get("pattern") or a.get("glob") or path or "", 60)
        item["kind"] = "search"
    elif "fetch" in low or a.get("url"):
        item["title"] = "Fetch " + (urllib.parse.urlparse(str(a.get("url") or "")).netloc or first_line(a.get("url") or "", 60))
        item["kind"] = "web"
    elif "websearch" in low or "web_search" in low:
        item["title"] = "Search the web: " + first_line(a.get("query") or "", 60)
        item["kind"] = "web"
    elif low in ("task", "agent", "delegate", "spawn_agent", "delegate_task"):
        item["title"] = "Agent: " + first_line(a.get("description") or a.get("prompt") or a.get("task") or "", 70)
        item["kind"] = "agent"
    elif "todo" in low or low in ("update_plan", "plan"):
        item["title"] = "Update the plan"
        item["kind"] = "plan"
    else:
        hint = a.get("description") or a.get("query") or a.get("pattern") or path or a.get("url") or a.get("name")
        item["title"] = name + (": " + first_line(hint, 60) if hint else "")
        item["kind"] = "other"
    # Files a tool hands to the user become attachments: Claude Code's SendUserFile (and other
    # send/share/upload tools) by their file arguments, and Hermes's send_message (or any tool)
    # by the MEDIA:/path tags in its arguments.
    got = []
    if re.search(r"send.*file|share|upload|attach|deliver", low):
        files = a.get("files") or a.get("paths") or a.get("attachments") or ([path] if path else [])
        if isinstance(files, str):
            files = [files]
        for f in files if isinstance(files, list) else []:
            fp = f.get("path") or f.get("file") if isinstance(f, dict) else f
            if isinstance(fp, str) and fp.strip():
                got.append(fp.strip())
    # MEDIA tags count only in a messaging tool (Hermes's send_message), never in an edit or a
    # command that merely contains the text "MEDIA:".
    if re.search(r"send|message|notify|reply|deliver", low) and low not in SHELL | EDIT | WRITE:
        for v in a.values():
            if isinstance(v, str) and "MEDIA:" in v:
                got += [m.rstrip(".,;:)]}\"'") for m in re.findall(r"MEDIA:((?:~/|/)[^\s\"'`<>]+)", v)]
    got = list(dict.fromkeys(g for g in got if g.startswith(("/", "~/")) and len(g) > 2))
    if got:
        item["attach"] = got[:10]
        item["title"] = "Sent " + ", ".join(base(f) for f in got[:3]) + (" and more" if len(got) > 3 else "")
        item["kind"] = "other"
    if path and item.get("kind") in ("edit", "read"):
        item["path"] = str(path)
        if item["kind"] == "edit" and re.search(r"\.(png|jpe?g|gif|webp|bmp)$", str(path), re.I):
            item["image"] = str(path)
    if "diff" not in item and "patch" not in item:
        item["input"] = clip(a if len(a) != 1 or "input" not in a else a["input"], IN_CHARS)
    if out is not None:
        item["out"] = clip(out, OUT_CHARS)
        item["err"] = bool(err)
        item["state"] = "err" if err else "ok"
    return item


def result_item(key, ref, out, err=False):
    return {"k": key, "t": "result", "ref": ref, "out": clip(out, OUT_CHARS), "err": bool(err)}


def image_of(b):
    """(mime, base64) for an image block in any agent's shape, or None."""
    if not isinstance(b, dict):
        return None
    t = b.get("type")
    if t == "image":
        src = b.get("source") if isinstance(b.get("source"), dict) else {}
        data = src.get("data") or b.get("data")
        mime = src.get("media_type") or b.get("mimeType") or b.get("mime_type") or "image/png"
        if isinstance(data, str) and data:
            return mime, data
    if t in ("input_image", "image_url"):
        url = b.get("image_url")
        url = url.get("url") if isinstance(url, dict) else url
        m = re.match(r"data:([\w/+.-]+);base64,(.*)", str(url or ""), re.S)
        if m:
            return m.group(1), m.group(2)
    return None


def image_item(key, path, off, idx, b):
    """A pointer to an image inside a log line. The picture is fetched only when shown."""
    got = image_of(b)
    if not got:
        return None
    return {"k": key, "t": "image", "src": {"file": path, "off": off, "idx": idx}, "mime": got[0],
            "kb": len(got[1]) * 3 // 4 // 1024}


# ---- JSONL logs ----------------------------------------------------------------------------------

def read_jsonl(path, cur):
    """New complete lines since the cursor, as (line offset, record) pairs. With "earlier" in the
    cursor, the 1.5 MB before that offset instead (Load earlier messages)."""
    size = os.path.getsize(path)
    if cur.get("file") == path and isinstance(cur.get("earlier"), int):
        end_at = min(cur["earlier"], size)
        start = max(0, end_at - TAIL_BYTES)
        with open(path, "rb") as f:
            f.seek(start)
            data = f.read(end_at - start)
        if start > 0:
            nl = data.find(b"\n")
            data = data[nl + 1:] if nl >= 0 else b""
            start += nl + 1 if nl >= 0 else 0
        recs, pos = [], 0
        for line in data.split(b"\n"):
            if line.strip():
                try:
                    recs.append((start + pos, json.loads(line)))
                except Exception:
                    pass
            pos += len(line) + 1
        return recs, {"file": path, "start": start, "earlier": True}, False, start > 0
    off = cur.get("off") if cur.get("file") == path else None
    reset = off is None or off > size
    if reset:
        off = max(0, size - TAIL_BYTES)
    with open(path, "rb") as f:
        f.seek(off)
        # At most DELTA_BYTES (1.3.0, from a code review: a cursor far behind a big log read the whole
        # gap into memory and into the phone). The cursor stops at the last whole line read.
        data = f.read(DELTA_BYTES)
    if not reset and len(data) == DELTA_BYTES and data.rfind(b"\n") < 0:
        # One line longer than a whole read (a huge tool output): step past it rather than stall on it.
        return [], {"file": path, "off": off + len(data)}, False, False
    more = False
    if reset and off > 0:
        more = True
        nl = data.find(b"\n")
        data = data[nl + 1:] if nl >= 0 else b""
        off += nl + 1 if nl >= 0 else 0
    end = data.rfind(b"\n")
    if end < 0:
        return [], {"file": path, "off": off}, reset, more
    recs = []
    pos = 0
    for line in data[: end + 1].split(b"\n")[:-1]:
        if line.strip():
            try:
                recs.append((off + pos, json.loads(line)))
            except Exception:
                pass
        pos += len(line) + 1
    ncur = {"file": path, "off": off + end + 1}
    if reset:
        ncur["start"] = off  # where this read began, for Load earlier messages
    return recs, ncur, reset, more


def newest(paths):
    paths = [p for p in paths if os.path.isfile(p)]
    return max(paths, key=mtime) if paths else None


PIN = {}  # set by main(): {"pin": file} or {"pin_sid": id, "db": path} to read a past session


def mtime(path):
    """A file's modification time; one removed while sorting counts as oldest (1.4.1, from a code review)."""
    try:
        return os.path.getmtime(path)
    except OSError:
        return 0


def pinned(default):
    """The pinned session's log when one was asked for (history view), otherwise [default]."""
    p = PIN.get("pin")
    return p if p and os.path.isfile(p) else default


SKIP_USER = ("<system-reminder>", "<environment_context>", "<user_instructions>", "<permissions", "# AGENTS.md",
             "<local-command-caveat>", "Caveat: ", "<task-notification>", "<turn_aborted>")


PASTED_TAG = re.compile(r"</?pasted_content\b[^>]*>")


def ts_of(r):
    """A record's time in epoch milliseconds (1.4.3: questions are placed in the chat by time), or None.
    Looks where the agents keep it: "timestamp" (Claude, Codex, Copilot, pi, omp, Grok's updates),
    "createdAt" (Vibe), "message.timestamp", "payload.timestamp", "data.meta.timestamp" (Kiro),
    "time.created" (OpenCode). ISO-8601 text or a number of seconds or milliseconds."""
    if not isinstance(r, dict):
        return None
    cands = [r.get("timestamp"), r.get("ts"), r.get("createdAt"), r.get("created_at"), r.get("time")]
    for sub in ("message", "payload", "data"):
        d = r.get(sub)
        if isinstance(d, dict):
            cands += [d.get("timestamp"), (d.get("meta") or {}).get("timestamp") if isinstance(d.get("meta"), dict) else None]
    for v in cands:
        if isinstance(v, dict):
            v = v.get("created")
        t = to_ms(v)
        if t:
            return t
    return None


def to_ms(v):
    """Epoch milliseconds from ISO-8601 text or a number (seconds or milliseconds); None otherwise."""
    if isinstance(v, bool) or v is None:
        return None
    if isinstance(v, (int, float)):
        if v <= 0:
            return None
        return int(v * 1000) if v < 1e11 else int(v)
    if isinstance(v, str) and v[:4].isdigit():
        try:
            import datetime
            t = v.strip().replace("Z", "+00:00")
            d = datetime.datetime.fromisoformat(t)
            if d.tzinfo is None:
                d = d.replace(tzinfo=datetime.timezone.utc)
            return int(d.timestamp() * 1000)
        except Exception:
            try:
                return to_ms(float(v))
            except Exception:
                return None
    return None


def stamped(recs, items, key=None):
    """Yields each record; once its body has run, the items it added carry its time as "ts" (1.4.3)."""
    for rec in recs:
        n = len(items)
        yield rec
        t = ts_of(key(rec) if key else rec[1])
        if t:
            for it in items[n:]:
                it.setdefault("ts", t)


def user_items(key, text, out, via=None):
    # A pasted block arrives wrapped in <pasted_content id="..."> tags; show only what was pasted.
    text = PASTED_TAG.sub("", text or "").strip()
    # Grok logs what you typed as "<user_query>\n...\n</user_query>" (sometimes after other context).
    # Show, and match against what the phone sent, only the query itself (HerdrDroid 1.2.6: before,
    # every message to Grok looked undelivered and was offered for resending).
    q = re.search(r"<user_query>\s*(.*?)\s*</user_query>", text, re.S)
    if q:
        text = q.group(1).strip()
    if not text:
        return
    m = re.match(r"<command-name>(.*?)</command-name>", text, re.S)
    if m:
        args = re.search(r"<command-args>(.*?)</command-args>", text, re.S)
        out.append({"k": key, "t": "note", "text": (m.group(1).strip() + " " + (args.group(1).strip() if args else "")).strip()})
        return
    if text.startswith("<local-command-stdout>"):
        body = re.sub(r"</?local-command-stdout>", "", text).strip()
        if body:
            out.append({"k": key, "t": "note", "text": first_line(body, 200)})
        return
    # A shell command typed with "!" (Claude Code's bash mode): shown as you typed it, and its
    # output as a note.
    m = re.match(r"<bash-input>(.*?)</bash-input>", text, re.S)
    if m:
        out.append({"k": key, "t": "user", "text": "! " + m.group(1).strip()})
        return
    if text.startswith("<bash-stdout>") or text.startswith("<bash-stderr>"):
        body = (re.sub(r"</?bash-std(out|err)>", "\n", text)).strip()
        out.append({"k": key, "t": "note", "text": first_line(body, 200) if body else "(no output)"})
        return
    if text.startswith("[Request interrupted"):
        out.append({"k": key, "t": "note", "text": "Interrupted"})
        return
    if text.startswith(SKIP_USER):
        return
    item = {"k": key, "t": "user", "text": text}
    if via:
        item["via"] = via
    out.append(item)


def claude(cwd, cur):
    slug = re.sub(r"[^A-Za-z0-9]", "-", cwd)
    path = pinned(newest(glob.glob(os.path.join(HOME, ".claude", "projects", slug, "*.jsonl"))))
    if not path:
        return None
    recs, ncur, reset, more = read_jsonl(path, cur)
    items, title = [], None
    for off, r in stamped(recs, items):
        if r.get("type") == "custom-title":
            title = r.get("customTitle") or title
            continue
        if r.get("isSidechain") or r.get("isMeta"):
            continue
        t = r.get("type")
        # A message sent while Claude was working (steering, or queued behind its turn) is not a
        # user record: Claude logs it as a queued_command attachment when it takes it in.
        att = r.get("attachment") if isinstance(r.get("attachment"), dict) else None
        if t == "attachment" and att and att.get("type") == "queued_command":
            user_items("q%d" % off, att.get("prompt") if isinstance(att.get("prompt"), str) else text_of(att.get("prompt")), items, via="while working")
            continue
        m = r.get("message") if isinstance(r.get("message"), dict) else {}
        c = m.get("content")
        if t == "user":
            if isinstance(c, str):
                user_items("c%d" % off, c, items)
            elif isinstance(c, list):
                for i, b in enumerate(c):
                    if not isinstance(b, dict):
                        continue
                    if b.get("type") == "tool_result":
                        items.append(result_item("c%d.%d" % (off, i), b.get("tool_use_id"), text_of(b.get("content")), b.get("is_error")))
                        if isinstance(b.get("content"), list):
                            for j, x in enumerate(b["content"]):
                                im = image_item("c%d.%d.%d" % (off, i, j), path, off, [i, j], x)
                                if im:
                                    items.append(im)
                    elif b.get("type") == "text":
                        user_items("c%d.%d" % (off, i), b.get("text"), items)
                    elif b.get("type") == "image":
                        im = image_item("c%d.%d" % (off, i), path, off, [i], b)
                        if im:
                            im["user"] = True
                            items.append(im)
        elif t == "assistant" and isinstance(c, list):
            for i, b in enumerate(c):
                if not isinstance(b, dict):
                    continue
                k = "c%d.%d" % (off, i)
                if b.get("type") == "text" and b.get("text", "").strip():
                    items.append({"k": k, "t": "text", "text": b["text"]})
                elif b.get("type") == "thinking":
                    items.append({"k": k, "t": "think", "text": b.get("thinking") or "", "ms": r.get("thinkingDurationMs")})
                elif b.get("type") == "tool_use":
                    items.append(tool_item(k, b.get("id"), b.get("name"), b.get("input")))
        elif t == "system" and r.get("subtype") == "compact_boundary":
            items.append({"k": "c%d" % off, "t": "note", "text": "Conversation compacted"})
    return {"cur": ncur, "reset": reset, "more": more, "items": items, "title": title}


def codex(cwd, cur):
    files = glob.glob(os.path.join(HOME, ".codex", "sessions", "*", "*", "*", "*.jsonl"))
    files.sort(key=mtime, reverse=True)
    path = None
    if cur.get("file") in files[:40]:
        files = [cur["file"]] + [f for f in files[:40] if f != cur["file"]]
    for f in files[:40]:
        try:
            with open(f, "rb") as fh:
                head = json.loads(fh.readline() or b"{}")
            if (head.get("payload") or {}).get("cwd") == cwd:
                path = f
                break
        except Exception:
            continue
    if path is None or (cur.get("file") and cur["file"] != path and mtime(cur["file"]) > mtime(path)):
        path = path or cur.get("file")
    path = pinned(path)
    if not path:
        return None
    recs, ncur, reset, more = read_jsonl(path, cur)
    items = []
    for off, r in stamped(recs, items):
        if r.get("type") != "response_item":
            continue
        p = r.get("payload") or {}
        pt = p.get("type")
        k = "x%d" % off
        if pt == "message":
            role = p.get("role")
            txt = text_of(p.get("content"))
            if role == "user":
                user_items(k, txt, items)
                for i, b in enumerate(p.get("content") or []):
                    im = image_item("%s.%d" % (k, i), path, off, [i], b)
                    if im:
                        im["user"] = True
                        items.append(im)
            elif role == "assistant" and txt.strip():
                items.append({"k": k, "t": "text", "text": txt})
        elif pt == "reasoning":
            summ = text_of(p.get("summary"))
            if summ.strip():
                items.append({"k": k, "t": "think", "text": summ})
        elif pt in ("function_call", "custom_tool_call", "local_shell_call"):
            args = p.get("arguments") if pt == "function_call" else (p.get("input") if pt == "custom_tool_call" else p.get("action"))
            items.append(tool_item(k, p.get("call_id") or p.get("id"), p.get("name") or "shell", args))
        elif pt in ("function_call_output", "custom_tool_call_output", "local_shell_call_output"):
            o = p.get("output")
            err = False
            if isinstance(o, str):
                try:
                    j = json.loads(o)
                    if isinstance(j, dict):
                        err = ((j.get("metadata") or {}).get("exit_code") or 0) != 0
                        o = j.get("output", o)
                except Exception:
                    pass
            items.append(result_item(k, p.get("call_id"), text_of(o), err))
    return {"cur": ncur, "reset": reset, "more": more, "items": items, "title": None}


def pi(cwd, cur, root=(".pi", "agent")):
    """pi, and Oh My Pi (root ~/.omp/agent), which keeps pi's session format.

    pi names a session folder "--home-bill-agents-pi--". Oh My Pi names a folder inside the home
    directory relative to it ("-agents-omp" for ~/agents/omp) and others the pi way.
    """
    names = ["--" + cwd.strip("/").replace("/", "-") + "--"]
    if cwd.startswith(HOME + "/"):
        names.append("-" + cwd[len(HOME) + 1:].replace("/", "-"))
    files = []
    for d in names:
        files += glob.glob(os.path.join(HOME, *root, "sessions", d, "*.jsonl"))
    path = pinned(newest(files))
    if not path:
        return None
    recs, ncur, reset, more = read_jsonl(path, cur)
    items = []
    for off, r in stamped(recs, items):
        if r.get("type") != "message":
            continue
        m = r.get("message") or {}
        role = m.get("role")
        c = m.get("content")
        if role == "user":
            user_items("p%d" % off, text_of(c), items)
            for i, b in enumerate(c if isinstance(c, list) else []):
                im = image_item("p%d.%d" % (off, i), path, off, [i], b)
                if im:
                    im["user"] = True
                    items.append(im)
        elif role == "assistant" and isinstance(c, list):
            for i, b in enumerate(c):
                if not isinstance(b, dict):
                    continue
                k = "p%d.%d" % (off, i)
                if b.get("type") == "text" and (b.get("text") or "").strip():
                    items.append({"k": k, "t": "text", "text": b["text"]})
                elif b.get("type") == "thinking" and (b.get("thinking") or "").strip():
                    items.append({"k": k, "t": "think", "text": b["thinking"]})
                elif b.get("type") == "toolCall":
                    items.append(tool_item(k, b.get("id"), b.get("name"), b.get("arguments")))
        elif role == "toolResult":
            items.append(result_item("p%d" % off, m.get("toolCallId"), text_of(c), m.get("isError")))
            for i, b in enumerate(c if isinstance(c, list) else []):
                im = image_item("p%d.%d" % (off, i), path, off, [i], b)
                if im:
                    items.append(im)
    return {"cur": ncur, "reset": reset, "more": more, "items": items, "title": None}


def grok(cwd, cur):
    d = os.path.join(HOME, ".grok", "sessions", urllib.parse.quote(cwd, safe=""))
    path = pinned(newest(glob.glob(os.path.join(d, "*", "chat_history.jsonl"))))
    if not path:
        return None
    recs, ncur, reset, more = read_jsonl(path, cur)
    items = []
    for off, r in stamped(recs, items):
        t = r.get("type")
        k = "g%d" % off
        if t == "user" and not r.get("synthetic_reason"):
            user_items(k, text_of(r.get("content")), items)
            for i, b in enumerate(r.get("content") if isinstance(r.get("content"), list) else []):
                im = image_item("%s.%d" % (k, i), path, off, [i], b)
                if im:
                    im["user"] = True
                    items.append(im)
        elif t == "assistant":
            txt = text_of(r.get("content"))
            if txt.strip():
                items.append({"k": k, "t": "text", "text": txt})
            for i, tc in enumerate(r.get("tool_calls") or []):
                items.append(tool_item("%s.%d" % (k, i), tc.get("id"), tc.get("name"), tc.get("arguments")))
        elif t == "tool_result":
            items.append(result_item(k, r.get("tool_call_id"), text_of(r.get("content"))))
        elif t == "reasoning":
            summ = text_of(r.get("summary"))
            if summ.strip():
                items.append({"k": k, "t": "think", "text": summ})
    return {"cur": ncur, "reset": reset, "more": more, "items": items, "title": None}


def copilot(cwd, cur):
    # Newest first, stopping at the first session for this folder (1.4.0, from a code review: every
    # read, follow ticks included, opened the first line of every events.jsonl there was).
    best = None
    files = glob.glob(os.path.join(HOME, ".copilot", "session-state", "*", "events.jsonl"))
    for f in sorted(files, key=mtime, reverse=True):
        try:
            with open(f, "rb") as fh:
                head = json.loads(fh.readline() or b"{}")
            if ((head.get("data") or {}).get("context") or {}).get("cwd") == cwd:
                best = f
                break
        except Exception:
            continue
    best = pinned(best)
    if not best:
        return None
    recs, ncur, reset, more = read_jsonl(best, cur)
    items = []
    for off, r in stamped(recs, items):
        t = r.get("type") or ""
        d = r.get("data") or {}
        k = "o%d" % off
        if t == "user.message":
            user_items(k, text_of(d.get("content") or d.get("text")), items)
        elif t == "assistant.message":
            txt = text_of(d.get("content") or d.get("text"))
            if txt.strip():
                items.append({"k": k, "t": "text", "text": txt})
            for i, tr in enumerate(d.get("toolRequests") or []):
                items.append(tool_item("%s.%d" % (k, i), tr.get("toolCallId"), tr.get("name"), tr.get("arguments")))
        elif t in ("assistant.reasoning", "assistant.thinking"):
            txt = text_of(d.get("content") or d.get("text"))
            if txt.strip():
                items.append({"k": k, "t": "think", "text": txt})
        elif t == "tool.execution_start" and d.get("toolCallId"):
            items.append(tool_item(k, d.get("toolCallId"), d.get("toolName") or d.get("name"), d.get("arguments")))
        elif t == "tool.execution_complete":
            res = d.get("result")
            items.append(result_item(k, d.get("toolCallId"), text_of(res), d.get("success") is False))
    # No messages in the window read (1.4.0, from a code review): still return the cursor, so the
    # next read goes on from here instead of reading the same tail again.
    return {"cur": ncur, "reset": reset, "more": more, "items": items, "title": None}


# ---- SQLite logs ---------------------------------------------------------------------------------

def ro(path):
    return sqlite3.connect("file:%s?mode=ro" % urllib.parse.quote(path), uri=True, timeout=3)


def opencode(cwd, cur):
    dbs = [os.path.join(HOME, ".local", "share", "opencode", "opencode.db")]
    dbs += glob.glob(os.path.join(HOME, ".local", "*", "data", "opencode", "opencode.db"))
    best = None
    for db in dbs:
        if not os.path.isfile(db):
            continue
        try:
            c = ro(db)
            row = c.execute(
                "select id, title, time_updated from session_v2 where directory = ? and parent_id is null "
                "and time_archived is null order by time_updated desc limit 1", (cwd,)).fetchone()
            c.close()
        except Exception:
            continue
        if row and (best is None or row[2] > best[3]):
            best = (db, row[0], row[1], row[2])
    if PIN.get("pin_sid") and PIN.get("db"):
        best = (PIN["db"], PIN["pin_sid"], None, 0)
    if not best:
        return None
    db, sid, title, _ = best
    reset = cur.get("db") != db or cur.get("sid") != sid
    c = ro(db)
    more = False
    if reset:
        top = c.execute("select max(seq) from session_message where session_id = ?", (sid,)).fetchone()[0] or 0
        start = max(0, top - FIRST_ROWS)
        more = start > 0
    else:
        start = cur.get("seq", 0)
    rows = c.execute("select id, type, seq, data, time_created from session_message where session_id = ? and seq >= ? order by seq",
                     (sid, start)).fetchall()
    blobs = {}
    try:
        for mid, h, mime, size in c.execute(
                "select r.message_id, r.hash, b.mime, b.bytes from blob_ref r join blob b on b.hash = r.hash "
                "where r.session_id = ? order by r.seq", (sid,)):
            blobs.setdefault(mid, []).append((h, mime, size))
    except Exception:
        pass
    c.close()
    items = []
    last = start
    for mid, typ, seq, data, created in stamped(rows, items, key=lambda row: {"time": row[4]}):
        last = max(last, seq)
        try:
            d = json.loads(data)
        except Exception:
            continue
        if typ == "user":
            user_items("oc%s" % mid, d.get("text") or text_of(d.get("content")), items)
            for i, f in enumerate(d.get("files") or []):
                fp = f.get("path") or f.get("filename") if isinstance(f, dict) else f
                if isinstance(fp, str) and fp.startswith("/"):
                    items.append({"k": "oc%s.f%d" % (mid, i), "t": "image", "path": fp,
                                  "mime": (f.get("mime") if isinstance(f, dict) else None) or "application/octet-stream",
                                  "name": os.path.basename(fp), "user": True})
        elif typ == "assistant":
            for i, b in enumerate(d.get("content") or []):
                k = "oc%s.%d" % (mid, i)
                bt = b.get("type")
                if bt == "text" and (b.get("text") or "").strip():
                    items.append({"k": k, "t": "text", "text": b["text"]})
                elif bt == "reasoning" and (b.get("text") or "").strip():
                    items.append({"k": k, "t": "think", "text": b["text"]})
                elif bt == "tool":
                    st = b.get("state") or {}
                    status = st.get("status")
                    out = text_of(st.get("content")) if st.get("content") is not None else None
                    err = status == "error" or bool(st.get("error"))
                    if err and out is None:
                        out = text_of(st.get("error"))
                    items.append(tool_item(k, b.get("id"), b.get("name"), st.get("input"),
                                           out if status in ("completed", "error") or out else None, err,
                                           "run" if status in (None, "pending", "running") else "ok"))
        for h, mime, size in blobs.get(mid, []):
            items.append({"k": "ob%s" % h, "t": "image", "src": {"db": db, "hash": h}, "mime": mime or "application/octet-stream",
                          "kb": int(size or 0) // 1024, "user": typ == "user"})
    # The last message is re-read every poll: OpenCode updates a streaming reply in place.
    return {"cur": {"db": db, "sid": sid, "seq": last}, "reset": reset, "more": more, "items": items, "title": title}


def hermes(cwd, cur):
    db = os.path.join(HOME, ".hermes", "state.db")
    if not os.path.isfile(db):
        return None
    c = ro(db)
    # The pane's own conversation: one started in its folder first. A session with no folder (Hermes's
    # "Bot Chat", or one from its app) is taken only when none was started there, so the pane never
    # shows another conversation just because that one was busier lately.
    row = c.execute(
        "select id, title from sessions where source in ('cli', 'tui', 'local') and coalesce(archived, 0) = 0 "
        "and coalesce(hidden, 0) = 0 and (cwd = ? or cwd is null) "
        "order by (cwd = ?) desc, coalesce(last_activity_at, started_at) desc limit 1", (cwd, cwd)).fetchone()
    if PIN.get("pin_sid"):
        row = (PIN["pin_sid"], None)
    if not row:
        c.close()
        return None
    sid, title = row
    reset = cur.get("sid") != sid
    more = False
    if reset:
        top = c.execute("select max(id) from messages where session_id = ?", (sid,)).fetchone()[0] or 0
        ids = c.execute("select id from messages where session_id = ? order by id desc limit 1 offset ?",
                        (sid, FIRST_ROWS)).fetchone()
        start = ids[0] if ids else 0
        more = ids is not None
    else:
        start = cur.get("id", 0)
    rows = c.execute(
        "select id, role, content, tool_calls, tool_call_id, reasoning, display_kind, timestamp from messages "
        "where session_id = ? and id >= ? and coalesce(active, 1) = 1 order by id", (sid, start)).fetchall()
    c.close()
    items = []
    last = start
    for mid, role, content, tool_calls, tool_call_id, reasoning, display_kind, when in stamped(rows, items, key=lambda row: {"timestamp": row[7]}):
        last = max(last, mid)
        if display_kind == "hidden":
            continue
        k = "h%d" % mid
        if role == "user":
            user_items(k, text_of(content), items)
        elif role == "assistant":
            if reasoning and str(reasoning).strip():
                items.append({"k": k + "r", "t": "think", "text": str(reasoning)})
            txt = text_of(content)
            if txt.strip():
                items.append({"k": k, "t": "text", "text": txt})
            try:
                calls = json.loads(tool_calls) if tool_calls else []
            except Exception:
                calls = []
            for i, tc in enumerate(calls or []):
                fn = tc.get("function") or {}
                items.append(tool_item("%s.%d" % (k, i), tc.get("id"), fn.get("name") or tc.get("name"),
                                       fn.get("arguments") or tc.get("arguments")))
        elif role == "tool":
            items.append(result_item(k, tool_call_id, text_of(content)))
    return {"cur": {"sid": sid, "id": last}, "reset": reset, "more": more, "items": items, "title": title}


def vibe_sessions(cwd):
    """Mistral Vibe's sessions started in [cwd], newest first, as their meta.json paths."""
    out = []
    for meta in glob.glob(os.path.join(HOME, ".vibe", "logs", "session", "unified", "*", "meta.json")):
        try:
            with open(meta) as fh:
                m = json.load(fh)
        except Exception:
            continue
        if (m.get("environment") or {}).get("working_directory") == cwd or m.get("origin_directory") == cwd:
            out.append(meta)
    return sorted(out, key=mtime, reverse=True)


def vibe_entries(meta):
    """The conversation Vibe shows for one session: the projection named by its current snapshot
    (CURRENT -> generations/<n>/manifest.json -> projection_state chunks). Returns (generation, entries)."""
    d = os.path.dirname(meta)
    with open(os.path.join(d, "CURRENT")) as fh:
        gen = json.load(fh)["generation"]
    with open(os.path.join(d, "generations", gen, "manifest.json")) as fh:
        man = json.load(fh)
    entries = []
    for c in (man.get("projection_state") or {}).get("chunks") or []:
        with open(os.path.join(d, "chunks", c + ".json")) as fh:
            part = json.load(fh)
        if isinstance(part, list):
            entries.extend(part)
    return gen, entries


def vibe_items(entries, start):
    items = []
    for e in stamped(entries[start:], items, key=lambda e: e):
        k = "v" + str(e.get("id"))
        t = e.get("type")
        if t == "message" and e.get("role") == "user":
            user_items(k, text_of(e.get("content")), items)
        elif t == "message" and e.get("role") == "assistant":
            txt = text_of(e.get("content"))
            if txt.strip():
                items.append({"k": k, "t": "text", "text": txt})
        elif t == "reasoning":
            if str(e.get("text") or "").strip():
                items.append({"k": k, "t": "think", "text": str(e["text"])})
        elif t == "effect" and (e.get("detail") or {}).get("kind") == "tool":
            det, st = e.get("detail") or {}, e.get("state") or {}
            res = (st.get("output") or {})
            sc = res.get("structured_content") if isinstance(res.get("structured_content"), dict) else {}
            out = None
            if st.get("status") == "completed":
                out = sc.get("stdout") if "stdout" in sc else (st.get("outputText") or text_of(res.get("content")) or "")
                if sc.get("stderr"):
                    out = (out or "") + ("\n" if out else "") + sc["stderr"]
            err = res.get("type") not in (None, "success") or (sc.get("returncode") not in (None, 0))
            items.append(tool_item(k, e.get("id"), det.get("toolName"), det.get("input"), out=out, err=err))
    return items


def vibe(cwd, cur):
    sessions = vibe_sessions(cwd)
    meta = pinned(sessions[0] if sessions else None)
    if not meta:
        return None
    try:
        gen, entries = vibe_entries(meta)
    except Exception:
        return None
    reset = cur.get("file") != meta
    if not reset and cur.get("gen") == gen:
        return {"cur": cur, "reset": False, "more": False, "items": [], "title": None}
    start = 0 if reset else min(cur.get("n", 0), len(entries))
    more = False
    if reset and len(entries) > FIRST_ROWS:
        start, more = len(entries) - FIRST_ROWS, True
    # A snapshot can rewrite earlier entries (a tool finishing); re-send from the first one that changed.
    if not reset and start and cur.get("last") and (start > len(entries) or str(entries[start - 1].get("id")) != cur["last"]):
        start = 0
        reset = True
    items = vibe_items(entries, start)
    last = str(entries[-1].get("id")) if entries else None
    return {"cur": {"file": meta, "gen": gen, "n": len(entries), "last": last}, "reset": reset, "more": more,
            "items": items, "title": None}


def omp(cwd, cur):
    return pi(cwd, cur, root=(".omp", "agent"))


def kiro_text(content):
    """A Kiro tool result's text: its content is a string, or a list of {"kind", "data"} blocks whose
    text blocks hold the text in "data" (1.4.1, from a code review); other shapes as text_of reads them."""
    if isinstance(content, list) and any(isinstance(b, dict) and "kind" in b for b in content):
        parts = []
        for b in content:
            if isinstance(b, dict) and b.get("kind") == "text" and isinstance(b.get("data"), str):
                parts.append(b["data"])
            elif isinstance(b, dict) and b.get("kind") == "json":
                parts.append(json.dumps(b.get("data"), ensure_ascii=False))
        return "\n".join(parts)
    return text_of(content)


def kiro(cwd, cur):
    """Kiro CLI (1.4.1): ~/.kiro/sessions/cli/<id>.json names the session's folder; <id>.jsonl holds
    its records, each {"version", "kind", "data": {"message_id", "content": [...], ...}}: kind Prompt
    (your message, content of kind "text"), AssistantMessage (content of kind "text", "thinking"
    {"text"} or "toolUse" {"toolUseId", "name", "input"}) and ToolResults (content of kind
    "toolResult" {"toolUseId", "content", "status"})."""
    d = os.path.join(HOME, ".kiro", "sessions", "cli")
    best = None
    # The session whose log was written last (1.4.1, from a code review: sorting by the .json
    # switched a pane to another session in the same folder whose .json happened to be newer).
    logs = glob.glob(os.path.join(d, "*.jsonl"))
    for f in sorted(logs, key=mtime, reverse=True):
        try:
            if json.load(open(f[:-6] + ".json")).get("cwd") == cwd:
                best = f
                break
        except Exception:
            continue
    best = pinned(best)
    if not best:
        return None
    recs, ncur, reset, more = read_jsonl(best, cur)
    items = []
    for off, r in stamped(recs, items):
        kind = r.get("kind")
        data = r.get("data") or {}
        for i, c in enumerate(data.get("content") or []):
            k = "k%d.%d" % (off, i)
            ck, cd = c.get("kind"), c.get("data")
            if kind == "Prompt" and ck == "text" and isinstance(cd, str):
                user_items(k, cd, items)
            elif kind == "AssistantMessage" and ck == "text" and isinstance(cd, str) and cd.strip():
                items.append({"k": k, "t": "text", "text": cd})
            elif ck == "thinking" and isinstance(cd, dict) and str(cd.get("text") or "").strip():
                items.append({"k": k, "t": "think", "text": str(cd["text"])})
            elif ck == "toolUse" and isinstance(cd, dict):
                items.append(tool_item(k, cd.get("toolUseId"), cd.get("name"), cd.get("input")))
            elif ck == "toolResult" and isinstance(cd, dict):
                items.append(result_item(k, cd.get("toolUseId"), kiro_text(cd.get("content")),
                                         str(cd.get("status") or "").lower() not in ("", "success", "ok")))
    title = None
    try:
        title = json.load(open(best[:-6] + ".json")).get("title") or None
    except Exception:
        pass
    return {"cur": ncur, "reset": reset, "more": more, "items": items, "title": title}


MUSE = (".local", "share", "muse")


def muse_sessions(cwd):
    """Muse Code's sessions for this folder, newest first, as (log path, updated in microseconds,
    title), from its own index (~/.local/share/muse/session-index.db, read only), 1.4.5."""
    out = []
    db = os.path.join(HOME, *MUSE, "session-index.db")
    if not os.path.isfile(db):
        return out
    try:
        c = ro(db)
        for path, upd, title in c.execute(
                "select session_log_path, updated_at_us, title from sessions where workspace_root = ? "
                "and coalesce(msp_kind, 'root') = 'root' order by updated_at_us desc limit 100", (cwd,)):
            if path and os.path.isfile(path):
                out.append((path, int(upd or 0), title))
        c.close()
    except Exception:
        pass
    return out


def muse_live(path):
    """True when a running Muse holds this session (1.4.5). Muse holds a kernel flock on the session
    folder's .session.lock while it is open; the "pid=" line in it is only a hint and can outlive
    its process (from Grok's review). The lock is looked up in /proc/locks by device and inode, so
    nothing here ever takes or tests the lock itself. Without /proc/locks, the pid must be alive
    and be a Muse program."""
    lock = os.path.join(os.path.dirname(path), ".session.lock")
    try:
        st = os.stat(lock)
    except OSError:
        return False
    want = "%02x:%02x:%d" % (os.major(st.st_dev), os.minor(st.st_dev), st.st_ino)
    try:
        with open("/proc/locks") as fh:
            return any(len(f) > 5 and f[1] == "FLOCK" and f[5] == want for f in (l.split() for l in fh))
    except OSError:
        pass
    try:
        with open(lock) as fh:
            m = re.search(r"pid=(\d+)", fh.read(200))
        with open("/proc/%d/cmdline" % int(m.group(1)), "rb") as fh:
            return b"muse" in fh.read()
    except Exception:
        return False


def muse(cwd, cur):
    """Muse Code 1.4.20 (1.4.5): ~/.local/share/muse/sessions/<y>/<m>/<d>/<id>/session.jsonl, one
    event per line: {"payload_type", "payload", "recorded_at" (microseconds), ...}. The chat is in
    payload_type "runtime.session" with payload kind "run": event kind "started" (your prompt),
    "assistant_message_committed" (text), "reasoning_summary_delta" (text), "assistant_tool_calls_committed"
    (tool_calls: name, call_id, args as JSON text), "tool_result_batch_committed" (results: tool_call_id,
    text; "tool failed: ..." for an error) and "terminal" (how the turn ended). The session open in the
    pane is the one whose .session.lock names a live process; otherwise the newest for the folder
    (a headless `muse exec` in the same folder makes a session too)."""
    found = muse_sessions(cwd)
    live = [f for f in found if muse_live(f[0])]
    pick = (live or found or [None])[0]
    path = pinned(pick[0] if pick else None)
    if not path:
        return None
    title = next((f[2] for f in found if f[0] == path), None)
    recs, ncur, reset, more = read_jsonl(path, cur)
    items = []
    think_of = {}
    for off, r in recs:
        if r.get("payload_type") != "runtime.session":
            continue
        p = r.get("payload") if isinstance(r.get("payload"), dict) else {}
        e = p.get("event") if isinstance(p.get("event"), dict) else None
        if p.get("kind") != "run" or not e:
            continue
        n = len(items)
        k = "m%d" % off
        ek = e.get("kind")
        if ek == "started" and isinstance(e.get("prompt"), str):
            user_items(k, e["prompt"], items)
        elif ek == "assistant_message_committed" and str(e.get("text") or "").strip():
            items.append({"k": k, "t": "text", "text": e["text"]})
        elif ek in ("reasoning_summary_delta", "reasoning_committed") and str(e.get("text") or "").strip():
            mid = e.get("message_id")
            if ek == "reasoning_committed" and mid in think_of:
                continue
            prev = think_of.get(mid)
            if prev is not None and items and items[-1] is prev:
                prev["text"] += "\n" + e["text"]
                continue
            it = {"k": k, "t": "think", "text": e["text"]}
            think_of[mid] = it
            items.append(it)
        elif ek == "assistant_tool_calls_committed":
            for i, c in enumerate(e.get("tool_calls") or []):
                if isinstance(c, dict):
                    items.append(tool_item("%s.%d" % (k, i), c.get("call_id") or c.get("id"), c.get("name"), c.get("args")))
        elif ek == "tool_result_batch_committed":
            for i, res in enumerate(e.get("results") or []):
                if isinstance(res, dict):
                    txt = str(res.get("text") or "")
                    err = txt.startswith("tool failed")
                    # A shell result is JSON text: {"command", "exit_code", "output", ...}.
                    if txt.startswith("{"):
                        try:
                            j = json.loads(txt)
                            if isinstance(j, dict) and "output" in j:
                                err = err or (j.get("exit_code") or 0) != 0
                                txt = str(j.get("output") or "")
                        except Exception:
                            pass
                    items.append(result_item("%s.%d" % (k, i), res.get("tool_call_id"), txt, err))
        elif ek == "terminal" and e.get("terminal") not in (None, "completed"):
            items.append({"k": k, "t": "note", "text": "Turn ended: %s" % first_line(str(e.get("reason") or e.get("terminal")), 160)})
        t = r.get("recorded_at")
        if isinstance(t, (int, float)) and t > 0:
            for it in items[n:]:
                it.setdefault("ts", int(t // 1000))
    return {"cur": ncur, "reset": reset, "more": more, "items": items, "title": title}


READERS = {"claude": claude, "codex": codex, "pi": pi, "omp": omp, "grok": grok, "copilot": copilot,
           "opencode": opencode, "hermes": hermes, "vibe": vibe, "kiro": kiro, "muse": muse}


def fetch_image(path, off, idx):
    """Print one image from a log line as {"mime", "data"} (base64). Used when the app shows it."""
    with open(path, "rb") as f:
        f.seek(int(off))
        r = json.loads(f.readline())
    for key in ("message", "payload"):
        if isinstance(r.get(key), dict) and "content" in r[key]:
            node = r[key]["content"]
            break
    else:
        node = r.get("content")
    for n, i in enumerate(idx):
        node = node[int(i)]
        if n < len(idx) - 1:
            node = node.get("content")
    got = image_of(node)
    if not got:
        done({"ok": False, "why": "no image there"})
    done({"ok": True, "mime": got[0], "data": got[1]})


def first_user_text(path):
    """The first thing you said in a JSONL session log, for the history list."""
    try:
        with open(path, "rb") as f:
            for n, line in enumerate(f):
                if n > 400:
                    break
                try:
                    r = json.loads(line)
                except Exception:
                    continue
                cands = []
                m = r.get("message") if isinstance(r.get("message"), dict) else None
                p = r.get("payload") if isinstance(r.get("payload"), dict) else None
                if m and m.get("role") == "user" and r.get("type") in (None, "user", "message") and not r.get("isMeta"):
                    cands.append(text_of(m.get("content")))
                elif p and p.get("type") == "message" and p.get("role") == "user":
                    cands.append(text_of(p.get("content")))
                elif r.get("type") == "user" and not r.get("synthetic_reason"):
                    cands.append(text_of(r.get("content")))
                elif r.get("type") == "user.message":
                    cands.append(text_of((r.get("data") or {}).get("content")))
                for t in cands:
                    t = (t or "").strip()
                    if t and not t.startswith(SKIP_USER) and not t.startswith("<"):
                        return first_line(t, 120)
    except Exception:
        pass
    return None


def list_sessions(agent, cwd):
    """Past sessions for this agent and folder, newest first: [{ref, db?, when, title, kb}]."""
    out = []

    def files(fs):
        for f in fs:
            try:
                st = os.stat(f)
            except Exception:
                continue
            out.append({"ref": f, "when": int(st.st_mtime), "kb": st.st_size // 1024, "title": None})

    if agent == "claude":
        files(glob.glob(os.path.join(HOME, ".claude", "projects", re.sub(r"[^A-Za-z0-9]", "-", cwd), "*.jsonl")))
    elif agent in ("pi", "omp"):
        root = (".pi", "agent") if agent == "pi" else (".omp", "agent")
        names = ["--" + cwd.strip("/").replace("/", "-") + "--"]
        if cwd.startswith(HOME + "/"):
            names.append("-" + cwd[len(HOME) + 1:].replace("/", "-"))
        for d in names:
            files(glob.glob(os.path.join(HOME, *root, "sessions", d, "*.jsonl")))
    elif agent == "grok":
        files(glob.glob(os.path.join(HOME, ".grok", "sessions", urllib.parse.quote(cwd, safe=""), "*", "chat_history.jsonl")))
    elif agent == "codex":
        fs = sorted(glob.glob(os.path.join(HOME, ".codex", "sessions", "*", "*", "*", "*.jsonl")), key=mtime, reverse=True)[:300]
        mine = []
        for f in fs:
            try:
                with open(f, "rb") as fh:
                    if (json.loads(fh.readline() or b"{}").get("payload") or {}).get("cwd") == cwd:
                        mine.append(f)
            except Exception:
                pass
        files(mine)
    elif agent == "copilot":
        mine = []
        for f in glob.glob(os.path.join(HOME, ".copilot", "session-state", "*", "events.jsonl")):
            try:
                with open(f, "rb") as fh:
                    if ((json.loads(fh.readline() or b"{}").get("data") or {}).get("context") or {}).get("cwd") == cwd:
                        mine.append(f)
            except Exception:
                pass
        files(mine)
    elif agent == "opencode":
        dbs = [os.path.join(HOME, ".local", "share", "opencode", "opencode.db")] + glob.glob(os.path.join(HOME, ".local", "*", "data", "opencode", "opencode.db"))
        for db in dbs:
            if not os.path.isfile(db):
                continue
            try:
                c = ro(db)
                for sid, title, upd in c.execute("select id, title, time_updated from session_v2 where directory = ? and parent_id is null "
                                                 "order by time_updated desc limit 60", (cwd,)):
                    out.append({"ref": sid, "db": db, "when": int((upd or 0) / 1000) if (upd or 0) > 10**11 else int(upd or 0), "title": title, "kb": 0})
                c.close()
            except Exception:
                pass
    elif agent == "vibe":
        for meta in vibe_sessions(cwd)[:60]:
            try:
                _, ents = vibe_entries(meta)
            except Exception:
                continue
            first = next((text_of(e.get("content")) for e in ents if e.get("type") == "message" and e.get("role") == "user"), "")
            out.append({"ref": meta, "when": int(os.path.getmtime(meta)), "kb": 0, "title": first_line(first or "(empty)", 90)})
    elif agent == "hermes":
        db = os.path.join(HOME, ".hermes", "state.db")
        if os.path.isfile(db):
            c = ro(db)
            for sid, title, when, n in c.execute(
                    "select id, title, coalesce(last_activity_at, started_at), message_count from sessions "
                    "where source in ('cli', 'tui', 'local') and (cwd = ? or cwd is null) and coalesce(hidden, 0) = 0 "
                    "order by coalesce(last_activity_at, started_at) desc limit 60", (cwd,)):
                out.append({"ref": sid, "db": db, "when": int(when or 0), "title": title, "kb": 0, "messages": n})
            c.close()
    elif agent == "muse":
        for path, upd, title in muse_sessions(cwd)[:60]:
            try:
                kb = os.path.getsize(path) // 1024
            except OSError:
                kb = 0
            out.append({"ref": path, "when": int(upd // 1_000_000), "kb": kb, "title": title})
    out.sort(key=lambda s: s["when"], reverse=True)
    out = out[:60]
    for s in out:
        if not s.get("title") and "db" not in s:
            s["title"] = first_user_text(s["ref"])
    return out


def fetch_blob(db, h):
    """Print one OpenCode blob as {"mime", "data"} (base64)."""
    import base64
    c = ro(db)
    row = c.execute("select mime, data from blob where hash = ?", (h,)).fetchone()
    c.close()
    if not row:
        done({"ok": False, "why": "no such blob"})
    data = row[1] if isinstance(row[1], (bytes, bytearray)) else str(row[1]).encode()
    done({"ok": True, "mime": row[0], "data": base64.b64encode(data).decode()})



# ---- Work under a pane: processes, Claude's subagents and background tasks -------------------

SECRET_ARG = re.compile(r"(?i)(token|secret|passw(or)?d|passwd|api[_-]?key|apikey|auth|bearer|credential|private[_-]?key|cookie|session[_-]?key)")
LONG_RANDOM = re.compile(r"(?<![/\w.-])[A-Za-z0-9_\-+=]{32,}")


def mask_args(args):
    """A command line safe to show on the phone: values that look secret become •••."""
    out = []
    hide_next = False
    for a in args:
        if hide_next:
            out.append("•••")
            hide_next = False
            continue
        if re.match(r"^-{1,2}[\w.-]+$", a) and SECRET_ARG.search(a):
            out.append(a)
            hide_next = True
            continue
        m = re.match(r"^(-{0,2}[\w.-]+)[=:](.+)$", a)
        if m and SECRET_ARG.search(m.group(1)):
            out.append(m.group(1) + "=•••")
            continue
        a = re.sub(r"://[^/\s:@]+:[^/\s@]+@", "://•••@", a)
        a = re.sub(r"(?i)(authorization:\s*\S+\s+)\S+", r"\1•••", a)
        a = LONG_RANDOM.sub("•••", a)
        out.append(a)
    s = " ".join(out)
    return s if len(s) <= 240 else s[:240] + "…"


def proc_table():
    """Own processes: pid -> (ppid, start ticks, CPU ticks, session ID, controlling tty)."""
    uid = os.getuid()
    procs = {}
    for d in os.listdir("/proc"):
        if not d.isdigit():
            continue
        try:
            if os.stat("/proc/" + d).st_uid != uid:
                continue
            with open("/proc/%s/stat" % d) as f:
                st = f.read()
            rest = st[st.rindex(")") + 2:].split()
            if rest[0] in ("Z", "X"):
                continue
            procs[int(d)] = (int(rest[1]), int(rest[19]), int(rest[11]) + int(rest[12]), int(rest[3]), int(rest[4]))
        except Exception:
            continue
    return procs


def pane_of(pid):
    try:
        with open("/proc/%d/environ" % pid, "rb") as f:
            for kv in f.read().split(b"\0"):
                # herdr's panes carry HERDR_PANE_ID; herdr-core's terminals carry HC_TERMINAL_ID.
                if kv.startswith(b"HERDR_PANE_ID="):
                    return kv[14:].decode("utf-8", "replace")
                if kv.startswith(b"HC_TERMINAL_ID="):
                    return kv[15:].decode("utf-8", "replace")
    except Exception:
        pass
    return None


def pane_procs(pane, table=None, pids=None):
    """The processes running in a herdr pane (they all carry HERDR_PANE_ID), as a tree.
    [pids], when the caller already knows which processes are in the pane, skips that lookup."""
    table = table if table is not None else proc_table()
    me = {os.getpid(), os.getppid()}
    if pids is not None:
        mine = {pid: table[pid] for pid in pids if pid in table and pid not in me}
    else:
        mine = {pid: v for pid, v in table.items() if pid not in me and pane_of(pid) == pane}
    hz = os.sysconf("SC_CLK_TCK")
    with open("/proc/uptime") as f:
        up = float(f.read().split()[0])
    kids = {}
    for pid, (ppid, *_) in mine.items():
        kids.setdefault(ppid if ppid in mine else 0, []).append(pid)
    out = []

    def walk(pid, depth):
        ppid, start, cpu, sid, tty = mine[pid]
        age = max(0.0, up - start / hz)
        try:
            with open("/proc/%d/cmdline" % pid, "rb") as f:
                args = [a.decode("utf-8", "replace") for a in f.read().split(b"\0") if a]
        except Exception:
            args = []
        try:
            with open("/proc/%d/statm" % pid) as f:
                rss_kb = int(f.read().split()[1]) * os.sysconf("SC_PAGE_SIZE") // 1024
        except Exception:
            rss_kb = 0
        out.append({"pid": pid, "ppid": ppid, "depth": depth, "cmd": mask_args(args) or "?",
                    "name": os.path.basename(args[0]) if args else "?", "sid": sid, "tty": tty,
                    "age": int(age), "cpu": round(100.0 * (cpu / hz) / age, 1) if age > 1 else 0.0, "rss": rss_kb})
        for c in sorted(kids.get(pid, [])):
            walk(c, depth + 1)

    for r in sorted(kids.get(0, [])):
        walk(r, 0)
    return out[:200], set(mine)


def claude_work_log(cwd, session, pid):
    """Use Herdr's exact session ID or an open parent transcript of this Claude process."""
    folder = os.path.realpath(os.path.join(HOME, ".claude", "projects", re.sub(r"[^A-Za-z0-9]", "-", cwd)))
    if session:
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", session):
            return None
        log = os.path.join(folder, session + ".jsonl")
        return log if os.path.isfile(log) else None
    logs = {f for f in open_files_of({pid}) if os.path.dirname(f) == folder and f.endswith(".jsonl")} if pid else set()
    return next(iter(logs)) if len(logs) == 1 else None


def claude_subagents(log, now, titles=True):
    sdir = os.path.splitext(log)[0]
    out = []
    fs = sorted(glob.glob(os.path.join(sdir, "subagents", "agent-*.jsonl")), key=mtime, reverse=True)[:30]
    for f in fs:
        st = os.stat(f)
        title = None
        if titles:
            try:
                with open(f, "rb") as h:
                    for n, line in enumerate(h):
                        if n > 60:
                            break
                        try:
                            r = json.loads(line)
                        except Exception:
                            continue
                        m = r.get("message") if isinstance(r.get("message"), dict) else None
                        if m and isinstance(m.get("content"), list):
                            for b in m["content"]:
                                if isinstance(b, dict) and b.get("type") == "tool_use" and isinstance(b.get("input"), dict) and b["input"].get("description"):
                                    title = title or b["input"]["description"]
                        if m and m.get("role") == "user" and not r.get("isMeta"):
                            t = (text_of(m.get("content")) or "").strip()
                            if t and not t.startswith("<"):
                                title = title or first_line(t, 100)
                        if title:
                            break
            except Exception:
                pass
        out.append({"id": os.path.basename(f)[6:-6], "file": f, "title": title or "Subagent",
                    "updated": int(st.st_mtime), "active": subagent_active(f, st, now), "kb": st.st_size // 1024})
    return out


def subagent_active(f, st, now):
    """A subagent is working when its log changed in the last two minutes, or when its last step is
    a tool call still waiting on its result (a long build, a CI wait, a test run writes nothing to
    the log until it returns). One whose turn has ended is not, however recently it wrote."""
    # Claude records a finished subagent in the meta file beside its log; that is final.
    try:
        with open(f[:-len(".jsonl")] + ".meta.json") as mf:
            if json.load(mf).get("finished"):
                return False
    except Exception:
        pass
    pending = set()
    ended = False
    try:
        with open(f, "rb") as h:
            h.seek(max(0, st.st_size - 262144))
            for line in h:
                try:
                    r = json.loads(line)
                except Exception:
                    continue
                m = r.get("message") if isinstance(r.get("message"), dict) else None
                if not m:
                    continue
                if m.get("role") == "assistant":
                    ended = m.get("stop_reason") == "end_turn"
                if isinstance(m.get("content"), list):
                    for b in m["content"]:
                        if not isinstance(b, dict):
                            continue
                        if b.get("type") == "tool_use" and b.get("id"):
                            pending.add(b["id"])
                            ended = False
                        elif b.get("type") == "tool_result":
                            pending.discard(b.get("tool_use_id"))
    except Exception:
        return now - st.st_mtime < 120
    if pending:
        # A call that has waited more than six hours is a stale log, not work.
        return now - st.st_mtime < 6 * 3600
    return (not ended) and now - st.st_mtime < 120


def claude_tasks(cwd, log, open_files, titles=True):
    sid = os.path.basename(os.path.splitext(log)[0])
    slug = re.sub(r"[^A-Za-z0-9]", "-", cwd)
    tdir = os.path.join("/tmp", "claude-%d" % os.getuid(), slug, sid, "tasks")
    fs = sorted(glob.glob(os.path.join(tdir, "*.output")), key=mtime, reverse=True)[:20]
    if not fs:
        return []
    # What each task is, from the session log: a background tool call and the id its result names.
    what = {}
    if titles:
        try:
            size = os.path.getsize(log)
            with open(log, "rb") as h:
                h.seek(max(0, size - 4_000_000))
                uses = {}
                for line in h:
                    if b"tool_use" not in line and b"tool_result" not in line:
                        continue
                    try:
                        r = json.loads(line)
                    except Exception:
                        continue
                    m = r.get("message") if isinstance(r.get("message"), dict) else None
                    if not m or not isinstance(m.get("content"), list):
                        continue
                    for b in m["content"]:
                        if not isinstance(b, dict):
                            continue
                        if b.get("type") == "tool_use" and isinstance(b.get("input"), dict):
                            i = b["input"]
                            uses[b.get("id")] = i.get("description") or first_line(str(i.get("command") or i.get("prompt") or b.get("name")), 100)
                        elif b.get("type") == "tool_result":
                            t = text_of(b.get("content")) or ""
                            for tid in re.findall(r"(?:ID|id)[:\s]+([a-z0-9]{6,})", t):
                                if b.get("tool_use_id") in uses:
                                    what[tid] = uses[b["tool_use_id"]]
        except Exception:
            pass
    out = []
    for f in fs:
        st = os.stat(f)
        tid = os.path.basename(f)[:-7]
        out.append({"id": tid, "file": f, "title": what.get(tid) or "Background task " + tid,
                    "updated": int(st.st_mtime), "running": os.path.realpath(f) in open_files, "kb": st.st_size // 1024})
    return out


def open_files_of(pids):
    out = set()
    for pid in pids:
        try:
            for fd in os.listdir("/proc/%d/fd" % pid):
                try:
                    out.add(os.path.realpath("/proc/%d/fd/%s" % (pid, fd)))
                except Exception:
                    pass
        except Exception:
            pass
    return out


def pane_work(pane, agent, cwd, session=""):
    now = time.time()
    procs, pids = pane_procs(pane)
    work = {"ok": True, "active_procs": active_shells(procs, agent), "subagents": [], "tasks": []}
    if agent == "claude" and cwd:
        log = claude_work_log(cwd, session, agent_pid(procs, agent))
        if log:
            work["subagents"] = claude_subagents(log, now)
            work["tasks"] = claude_tasks(cwd, log, open_files_of(pids))
    return work


# Long-lived helpers an agent starts on demand, which are not work in progress: language servers,
# MCP servers, file watchers and key agents. Matched on the masked command line. Kept in step with
# HELPER in Work.kt.
HELPER_RE = re.compile(
    r"language[-_ ]?server|langserver|\blsp\b|pyright|pylsp|gopls|rust-analyzer|clangd|jdtls|tsserver|"
    r"eslint_d|biome|\bmcp\b|mcp[-_]|[-_]mcp\b|mcpproxy|watchman|ssh-agent|gpg-agent|dbus-|app-server|daemon",
    re.I)


def agent_pid(procs, agent):
    if not agent:
        return None
    a = agent.lower()
    # The agent's name as a whole word (1.3.0, from a code review: as a substring, "pi" matched pip
    # and pigz); "kiro-cli" and "vibe-rs" still match.
    word = re.compile(r"(?<![a-z0-9])" + re.escape(a) + r"(?![a-z0-9])")
    ok = [p for p in procs if not HELPER_RE.search(p["cmd"])]
    cand = [p for p in ok if word.search(p["name"].lower())]
    if not cand and a not in ("claude", "kiro", "opencode"):
        cand = [p for p in ok if p["name"] in ("node", "bun", "deno", "python", "python3")
                and word.search(p["cmd"].lower()) and "agent-startup" not in p["cmd"]]
    return min(cand, key=lambda p: p["depth"])["pid"] if cand else None


def active_shells(procs, agent):
    """Live work below a known agent, not the pane shell or resident runtime."""
    root = agent_pid(procs, agent)
    if root is None:
        return []
    parents = {root}
    runtime = set()
    def detached(p, name):
        return p["name"] == name and p.get("sid") == p["pid"] and p.get("tty") == 0
    if agent == "opencode":
        runtime.update(p["pid"] for p in procs if p["ppid"] == root and detached(p, "opencode"))
    elif agent == "kiro":
        bun = {p["pid"] for p in procs if p["ppid"] == root and p["name"] == "bun"}
        for p in procs:
            if p["ppid"] in bun and detached(p, "kiro-cli"):
                runtime.update((p["ppid"], p["pid"]))
    out = []
    for p in procs:  # tree order: a parent comes before its children
        if p["pid"] in runtime and p["ppid"] in parents:
            parents.add(p["pid"])
        elif p["pid"] != root and p["ppid"] in parents and not HELPER_RE.search(p["cmd"]):
            parents.add(p["pid"])
            out.append(p)
    return out


def work_counts(panes):
    """For the cards: running subagents per pane (cheap)."""
    now = time.time()
    table = proc_table()
    by_pane = {}
    for pid in table:
        p = pane_of(pid)
        if p:
            by_pane.setdefault(p, set()).add(pid)
    out = {}
    busy = {}
    shells = {}
    for pane, agent, cwd, session in panes:
        # The card's count: subagents still running (shells are counted on the agent's own screen).
        # "busy" adds the background tasks and the commands the agent started that still run, for
        # every agent, so an agent herdr calls idle shows as working while any of them is active.
        n = 0
        tasks = 0
        procs = []
        pids = by_pane.get(pane, set())
        try:
            procs, _ = pane_procs(pane, table, pids)
            shells[pane] = len(active_shells(procs, agent))
        except Exception:
            shells[pane] = 0
        if agent == "claude" and cwd:
            try:
                log = claude_work_log(cwd, session, agent_pid(procs, agent))
                if log:
                    n = sum(1 for s in claude_subagents(log, now, titles=False) if s["active"])
                    tasks = sum(1 for t in claude_tasks(cwd, log, open_files_of(pids), titles=False) if t["running"])
            except OSError:
                pass
        out[pane] = n
        busy[pane] = n + tasks + shells[pane]
    return {"ok": True, "counts": out, "busy": busy, "shells": shells}


def tail_file(path, offset):
    """New bytes of a background task's output since [offset] (the last 64 KB the first time)."""
    path = os.path.realpath(path)
    if not (path.endswith(".output") and path.startswith("/tmp/claude-%d/" % os.getuid())):
        return {"ok": False, "why": "not a task output file"}
    size = os.path.getsize(path)
    start = offset if 0 <= offset <= size else max(0, size - 65536)
    if offset < 0:
        start = max(0, size - 65536)
    with open(path, "rb") as f:
        f.seek(start)
        data = f.read(262144)
    return {"ok": True, "text": data.decode("utf-8", "replace"), "next": start + len(data), "size": size, "cut": start > 0 and offset < 0}


def stop_proc(pane, pid, sig):
    """Stops a process in [pane] and everything under it, children first. Refuses a pid in another pane."""
    import signal
    table = proc_table()
    if pid not in table or pane_of(pid) != pane:
        return {"ok": False, "why": "That process is no longer running in this pane."}
    kids = {}
    for p, (pp, *_) in table.items():
        kids.setdefault(pp, []).append(p)
    order = []

    def walk(p):
        for c in kids.get(p, []):
            walk(c)
        order.append(p)

    walk(pid)
    s = signal.SIGKILL if sig == "KILL" else signal.SIGTERM
    sent = 0
    for p in order:
        if pane_of(p) != pane:
            continue
        try:
            os.kill(p, s)
            sent += 1
        except Exception:
            pass
    return {"ok": True, "sent": sent}


# ---- Git status for the cards, and search across sessions -------------------------------------

def git_status(cwds):
    """Branch, changed files, and commits ahead or behind its upstream, for each folder that is a repo."""
    import subprocess
    out = {}
    for cwd in cwds:
        try:
            r = subprocess.run(["git", "-C", cwd, "status", "--porcelain=v2", "--branch", "--untracked-files=normal"],
                               capture_output=True, text=True, timeout=8)
        except Exception:
            continue
        if r.returncode != 0:
            continue
        info = {"branch": None, "changed": 0, "ahead": 0, "behind": 0}
        for line in r.stdout.splitlines():
            if line.startswith("# branch.head "):
                info["branch"] = line[14:].strip()
            elif line.startswith("# branch.ab "):
                m = re.match(r"# branch.ab \+(\d+) -(\d+)", line)
                if m:
                    info["ahead"], info["behind"] = int(m.group(1)), int(m.group(2))
            elif line and line[0] in "12u?":
                info["changed"] += 1
        out[cwd] = info
    return {"ok": True, "git": out}


def search_sessions(query, limit=60):
    """Messages matching [query] (case-insensitive) in every agent's logs on this computer, newest first."""
    q = query.lower()
    hits = []

    def scan(agent, f):
        # Line by line, so a very large log (hundreds of MB) is searched too; the first match wins.
        try:
            st = os.stat(f)
            h = open(f, "rb")
        except Exception:
            return
        qb = q.encode()
        with h:
            for line in h:
                if qb not in line.lower():
                    continue
                try:
                    r = json.loads(line)
                except Exception:
                    continue
                m = r.get("message") if isinstance(r.get("message"), dict) else None
                p = r.get("payload") if isinstance(r.get("payload"), dict) else None
                d = r.get("data") if isinstance(r.get("data"), dict) else None
                role = (m or p or {}).get("role") or r.get("type")
                text = text_of((m or p or d or r).get("content")) or ""
                i = text.lower().find(q)
                if i < 0:
                    continue
                snip = text[max(0, i - 60): i + len(q) + 100].replace("\n", " ")
                hits.append({"agent": agent, "ref": f, "when": int(st.st_mtime), "title": first_user_text(f), "role": role, "snippet": snip})
                return

    roots = [("claude", os.path.join(HOME, ".claude", "projects", "*", "*.jsonl")),
             ("codex", os.path.join(HOME, ".codex", "sessions", "*", "*", "*", "*.jsonl")),
             ("pi", os.path.join(HOME, ".pi", "agent", "sessions", "*", "*.jsonl")),
             ("omp", os.path.join(HOME, ".omp", "agent", "sessions", "*", "*.jsonl")),
             ("grok", os.path.join(HOME, ".grok", "sessions", "*", "*", "chat_history.jsonl")),
             ("copilot", os.path.join(HOME, ".copilot", "session-state", "*", "events.jsonl"))]
    deadline = time.time() + 25
    for agent, pattern in roots:
        for f in sorted(glob.glob(pattern), key=mtime, reverse=True)[:400]:
            if time.time() > deadline:
                break
            scan(agent, f)
    # OpenCode and Hermes keep sessions in SQLite.
    like = "%" + query.replace("%", "") + "%"
    for agent, db, sql in (
            ("hermes", os.path.join(HOME, ".hermes", "state.db"),
             "select s.id, s.title, coalesce(s.last_activity_at, s.started_at), m.content from messages m join sessions s on s.id = m.session_id "
             "where m.content like ? order by m.id desc limit 40"),):
        if os.path.isfile(db):
            try:
                c = ro(db)
                for sid, title, when, content in c.execute(sql, (like,)):
                    t = content if isinstance(content, str) else ""
                    i = t.lower().find(q)
                    hits.append({"agent": agent, "ref": sid, "db": db, "when": int(when or 0), "title": title,
                                 "role": None, "snippet": t[max(0, i - 60): i + len(q) + 100].replace("\n", " ")})
                c.close()
            except Exception:
                pass
    hits.sort(key=lambda h: h["when"], reverse=True)
    return {"ok": True, "hits": hits[:limit]}

def main():
    if len(sys.argv) > 3 and sys.argv[1] == "__blob__":
        try:
            fetch_blob(sys.argv[2], sys.argv[3])
        except Exception as e:
            done({"ok": False, "why": "%s: %s" % (type(e).__name__, str(e)[:200])})
    if len(sys.argv) > 4 and sys.argv[1] == "__image__":
        try:
            fetch_image(sys.argv[2], sys.argv[3], json.loads(sys.argv[4]))
        except Exception as e:
            done({"ok": False, "why": "%s: %s" % (type(e).__name__, str(e)[:200])})
    if len(sys.argv) > 2 and sys.argv[1] == "__git__":
        try:
            done(git_status(json.loads(sys.argv[2])))
        except Exception as e:
            done({"ok": False, "why": "%s: %s" % (type(e).__name__, str(e)[:200])})
    if len(sys.argv) > 2 and sys.argv[1] == "__search__":
        try:
            done(search_sessions(sys.argv[2]))
        except Exception as e:
            done({"ok": False, "why": "%s: %s" % (type(e).__name__, str(e)[:200])})
    if len(sys.argv) > 2 and sys.argv[1] in ("__work__", "__workcounts__", "__tail__", "__stop__"):
        try:
            mode = sys.argv[1]
            if mode == "__work__":
                done(pane_work(sys.argv[2], (sys.argv[3] if len(sys.argv) > 3 else "").lower(), (sys.argv[4] if len(sys.argv) > 4 else "").rstrip("/"), sys.argv[5] if len(sys.argv) > 5 else ""))
            if mode == "__workcounts__":
                done(work_counts([(p, a.lower(), c.rstrip("/"), sid) for p, a, c, sid in json.loads(sys.argv[2])]))
            if mode == "__tail__":
                done(tail_file(sys.argv[2], int(sys.argv[3])))
            if mode == "__stop__":
                done(stop_proc(sys.argv[2], int(sys.argv[3]), sys.argv[4] if len(sys.argv) > 4 else "TERM"))
        except Exception as e:
            done({"ok": False, "why": "%s: %s" % (type(e).__name__, str(e)[:200])})
    if len(sys.argv) > 3 and sys.argv[1] == "__sessions__":
        try:
            done({"ok": True, "sessions": list_sessions(sys.argv[2].lower(), sys.argv[3].rstrip("/") or "/")})
        except Exception as e:
            done({"ok": False, "why": "%s: %s" % (type(e).__name__, str(e)[:200])})
    # Follow mode: the same read, repeated about three times a second for a while, printing one
    # JSON line whenever something changed (the app shows it at once instead of polling).
    follow = 0
    if len(sys.argv) > 1 and sys.argv[1] == "__follow__":
        follow = int(sys.argv[5]) if len(sys.argv) > 5 and sys.argv[5].isdigit() else 120
        sys.argv = [sys.argv[0]] + sys.argv[2:5]
    agent = (sys.argv[1] if len(sys.argv) > 1 else "").lower()
    cwd = sys.argv[2] if len(sys.argv) > 2 else ""
    try:
        cur = json.loads(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3] else {}
    except Exception:
        cur = {}
    # A history view pins one past session; the pin rides in the cursor.
    for k in ("pin", "pin_sid", "db"):
        if cur.get(k):
            PIN[k] = cur[k]
    reader = READERS.get(agent)
    if reader is None or not cwd:
        done({"ok": False, "why": "no log reader for this agent"})
    if follow:
        end = time.time() + min(follow, 600)
        first = True
        while time.time() < end:
            try:
                res = reader(cwd.rstrip("/") or "/", cur)
            except Exception as e:
                res = {"ok": False, "why": "%s: %s" % (type(e).__name__, str(e)[:200])}
            if res is None:
                res = {"ok": False, "why": "no session log found for this folder"}
            if res.get("ok") is False:
                sys.stdout.write(json.dumps(res) + "\n")
                sys.stdout.flush()
                return
            # The computer's clock, so the app can place questions (phone time) among items (1.4.3).
            res.update({"ok": True, "src": agent, "now": int(time.time() * 1000)})
            if PIN and isinstance(res.get("cur"), dict):
                res["cur"].update(PIN)
            if first or res.get("items") or res.get("reset") or res.get("title"):
                try:
                    sys.stdout.write(json.dumps(res, ensure_ascii=False) + "\n")
                    sys.stdout.flush()
                except BrokenPipeError:
                    return
            if isinstance(res.get("cur"), dict):
                cur = res["cur"]
            first = False
            time.sleep(0.3)
        return
    try:
        res = reader(cwd.rstrip("/") or "/", cur)
    except Exception as e:
        done({"ok": False, "why": "%s: %s" % (type(e).__name__, str(e)[:200])})
    if res is None:
        done({"ok": False, "why": "no session log found for this folder"})
    res.update({"ok": True, "src": agent, "now": int(time.time() * 1000)})
    if PIN and isinstance(res.get("cur"), dict):
        res["cur"].update(PIN)  # later reads stay on the pinned session
    done(res)


main()
