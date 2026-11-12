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
import urllib.parse

HOME = os.path.expanduser("~")
TAIL_BYTES = 1_500_000       # how much of a long log the first read shows
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
    """New complete lines since the cursor, as (line offset, record) pairs."""
    size = os.path.getsize(path)
    off = cur.get("off") if cur.get("file") == path else None
    reset = off is None or off > size
    if reset:
        off = max(0, size - TAIL_BYTES)
    with open(path, "rb") as f:
        f.seek(off)
        data = f.read()
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
    return recs, {"file": path, "off": off + end + 1}, reset, more


def newest(paths):
    paths = [p for p in paths if os.path.isfile(p)]
    return max(paths, key=os.path.getmtime) if paths else None


SKIP_USER = ("<system-reminder>", "<environment_context>", "<user_instructions>", "<permissions", "# AGENTS.md",
             "<local-command-caveat>", "Caveat: ", "<task-notification>", "<turn_aborted>")


def user_items(key, text, out):
    text = (text or "").strip()
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
    if text.startswith("[Request interrupted"):
        out.append({"k": key, "t": "note", "text": "Interrupted"})
        return
    if text.startswith(SKIP_USER):
        return
    out.append({"k": key, "t": "user", "text": text})


def claude(cwd, cur):
    slug = re.sub(r"[^A-Za-z0-9]", "-", cwd)
    path = newest(glob.glob(os.path.join(HOME, ".claude", "projects", slug, "*.jsonl")))
    if not path:
        return None
    recs, ncur, reset, more = read_jsonl(path, cur)
    items, title = [], None
    for off, r in recs:
        if r.get("type") == "custom-title":
            title = r.get("customTitle") or title
            continue
        if r.get("isSidechain") or r.get("isMeta"):
            continue
        t = r.get("type")
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
    files.sort(key=os.path.getmtime, reverse=True)
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
    if path is None or (cur.get("file") and cur["file"] != path and os.path.getmtime(cur["file"]) > os.path.getmtime(path)):
        path = path or cur.get("file")
    if not path:
        return None
    recs, ncur, reset, more = read_jsonl(path, cur)
    items = []
    for off, r in recs:
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
    path = newest(files)
    if not path:
        return None
    recs, ncur, reset, more = read_jsonl(path, cur)
    items = []
    for off, r in recs:
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
    path = newest(glob.glob(os.path.join(d, "*", "chat_history.jsonl")))
    if not path:
        return None
    recs, ncur, reset, more = read_jsonl(path, cur)
    items = []
    for off, r in recs:
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
    best = None
    for f in glob.glob(os.path.join(HOME, ".copilot", "session-state", "*", "events.jsonl")):
        try:
            with open(f, "rb") as fh:
                head = json.loads(fh.readline() or b"{}")
            if ((head.get("data") or {}).get("context") or {}).get("cwd") == cwd:
                if best is None or os.path.getmtime(f) > os.path.getmtime(best):
                    best = f
        except Exception:
            continue
    if not best:
        return None
    recs, ncur, reset, more = read_jsonl(best, cur)
    items = []
    for off, r in recs:
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
    if not items and reset:
        return None
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
    rows = c.execute("select id, type, seq, data from session_message where session_id = ? and seq >= ? order by seq",
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
    for mid, typ, seq, data in rows:
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
    row = c.execute(
        "select id, title from sessions where source in ('cli', 'tui', 'local') and coalesce(archived, 0) = 0 "
        "and coalesce(hidden, 0) = 0 and (cwd = ? or cwd is null) "
        "order by coalesce(last_activity_at, started_at) desc limit 1", (cwd,)).fetchone()
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
        "select id, role, content, tool_calls, tool_call_id, reasoning, display_kind from messages "
        "where session_id = ? and id >= ? and coalesce(active, 1) = 1 order by id", (sid, start)).fetchall()
    c.close()
    items = []
    last = start
    for mid, role, content, tool_calls, tool_call_id, reasoning, display_kind in rows:
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


def omp(cwd, cur):
    return pi(cwd, cur, root=(".omp", "agent"))


READERS = {"claude": claude, "codex": codex, "pi": pi, "omp": omp, "grok": grok, "copilot": copilot,
           "opencode": opencode, "hermes": hermes}


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
    agent = (sys.argv[1] if len(sys.argv) > 1 else "").lower()
    cwd = sys.argv[2] if len(sys.argv) > 2 else ""
    try:
        cur = json.loads(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3] else {}
    except Exception:
        cur = {}
    reader = READERS.get(agent)
    if reader is None or not cwd:
        done({"ok": False, "why": "no log reader for this agent"})
    try:
        res = reader(cwd.rstrip("/") or "/", cur)
    except Exception as e:
        done({"ok": False, "why": "%s: %s" % (type(e).__name__, str(e)[:200])})
    if res is None:
        done({"ok": False, "why": "no session log found for this folder"})
    res.update({"ok": True, "src": agent})
    done(res)


main()
