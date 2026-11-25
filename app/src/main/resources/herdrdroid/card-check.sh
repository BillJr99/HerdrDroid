# HerdrDroid 2.2.7: the check before a card's keys, for a computer without python3 (POSIX sh,
# awk, and sha256sum, shasum or openssl). The same steps and exit codes as CARD_SEND_CHECK in
# Herdr.kt, and byte for byte the same digests: 0 when the pane's occupant (read before and after
# the screen) and its screen match $3 and $4, 3 when anything changed, 4 when it couldn't check.
# Arguments: herdr, pane id, occupant digest, screen digest. No prompt text.
# 2.2.8: a screen digest of "-" checks the occupant only, wherever the pane is scrolled (a message
# queued behind a question goes only to its occupant): 0 when it matches, 5 when it doesn't, 4.
# 2.2.8: a screen argument "r:<rows under>:<rows>:<digest>" compares only the dialog's rows
# (cardRegionDigest in Herdr.kt): the same rows of this read, each with only its ends trimmed, and
# the rows under them by their shape (a new dialog under the old one is a change).
herdr=$1
pane=$2
occupant=$3
screen=$4
LC_ALL=C
export LC_ALL

sha() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 | cut -d' ' -f1
  elif command -v openssl >/dev/null 2>&1; then openssl dgst -sha256 | sed 's/^.*= *//'
  else return 1
  fi
}

hex64() {
  case $1 in *[!0-9a-f]* | '') return 1 ;; esac
  [ ${#1} -eq 64 ]
}

# One JSON value, read whole: MODE=status exits 0 (an occupant), 3 (none: another pane id, or
# not at the bottom) or 4 (unreadable); MODE=ser prints the occupant's fields, framed as the
# Python check frames them ("<bytes>:<value>" or "-", one per line).
J='
function err() { exit 4 }
function ws() {
  while (pos <= n) {
    c0 = substr(s, pos, 1)
    if (c0 != " " && c0 != "\t" && c0 != "\n" && c0 != "\r") return
    pos++
  }
}
function hex4(p,   i, h, d, v) {
  v = 0
  for (i = 0; i < 4; i++) {
    h = tolower(substr(s, p + i, 1))
    if (h == "") err()
    d = index("0123456789abcdef", h)
    if (d == 0) err()
    v = v * 16 + d - 1
  }
  return v
}
function utf8(cp) {
  if (cp < 128) return sprintf("%c", cp)
  if (cp < 2048) return sprintf("%c%c", 192 + int(cp / 64), 128 + cp % 64)
  if (cp < 65536) return sprintf("%c%c%c", 224 + int(cp / 4096), 128 + int(cp / 64) % 64, 128 + cp % 64)
  return sprintf("%c%c%c%c", 240 + int(cp / 262144), 128 + int(cp / 4096) % 64, 128 + int(cp / 64) % 64, 128 + cp % 64)
}
function str(path,   out, c, e, cp, lo) {
  pos++
  out = ""
  while (1) {
    if (pos > n) err()
    c = substr(s, pos, 1)
    if (c == "\"") { pos++; return out }
    if (c == "\\") {
      e = substr(s, pos + 1, 1)
      if (e == "\"" || e == "\\" || e == "/") { out = out e; pos += 2 }
      else if (e == "b") { out = out "\b"; pos += 2 }
      else if (e == "f") { out = out "\f"; pos += 2 }
      else if (e == "n") { out = out "\n"; pos += 2 }
      else if (e == "r") { out = out "\r"; pos += 2 }
      else if (e == "t") { out = out "\t"; pos += 2 }
      else if (e == "u") {
        cp = hex4(pos + 2)
        pos += 6
        if (cp >= 55296 && cp <= 56319 && substr(s, pos, 2) == "\\u") {
          lo = hex4(pos + 2)
          if (lo >= 56320 && lo <= 57343) { cp = 65536 + (cp - 55296) * 1024 + (lo - 56320); pos += 6 }
          else { odd[path] = 1; continue }
        } else if (cp >= 55296 && cp <= 57343) { odd[path] = 1; continue }
        if (cp == 0) { odd[path] = 1; continue }
        out = out utf8(cp)
      } else err()
    } else if (c < " ") err()
    else { out = out c; pos++ }
  }
}
function num(path,   r, t) {
  r = substr(s, pos)
  if (!match(r, /^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?/)) err()
  t = substr(r, 1, RLENGTH)
  pos += RLENGTH
  if (t ~ /[.eE]/) type[path] = "f"
  else { type[path] = "i"; if (t == "-0") t = "0" }
  val[path] = t
}
function forget(p,   x) {
  for (x in type) if (x == p || index(x, p SUBSEP) == 1) { delete type[x]; delete val[x]; delete odd[x]; delete cnt[x] }
}
function value(path,   c) {
  ws()
  c = substr(s, pos, 1)
  if (c == "{") obj(path)
  else if (c == "[") arr(path)
  else if (c == "\"") { val[path] = str(path); type[path] = "s" }
  else if (substr(s, pos, 4) == "true") { type[path] = "b"; val[path] = "true"; pos += 4 }
  else if (substr(s, pos, 5) == "false") { type[path] = "b"; val[path] = "false"; pos += 5 }
  else if (substr(s, pos, 4) == "null") { type[path] = "n"; val[path] = ""; pos += 4 }
  else num(path)
}
function obj(path,   k, c) {
  type[path] = "o"
  cnt[path] = 0
  pos++
  ws()
  if (substr(s, pos, 1) == "}") { pos++; return }
  while (1) {
    ws()
    if (substr(s, pos, 1) != "\"") err()
    k = str("")
    ws()
    if (substr(s, pos, 1) != ":") err()
    pos++
    if ((path SUBSEP k) in type) forget(path SUBSEP k)
    else cnt[path]++
    value(path SUBSEP k)
    ws()
    c = substr(s, pos, 1)
    if (c == ",") { pos++; continue }
    if (c == "}") { pos++; return }
    err()
  }
}
function arr(path,   i, c) {
  type[path] = "a"
  cnt[path] = 0
  pos++
  ws()
  if (substr(s, pos, 1) == "]") { pos++; return }
  for (i = 0; ; i++) {
    value(path SUBSEP "#" i)
    cnt[path]++
    ws()
    c = substr(s, pos, 1)
    if (c == ",") { pos++; continue }
    if (c == "]") { pos++; return }
    err()
  }
}
function zero(p,   t) {
  t = type[p]
  return ((t == "i" || t == "f") && val[p] + 0 == 0) || (t == "b" && val[p] == "false")
}
function falsy(p,   t) {
  t = type[p]
  return t == "" || t == "n" || zero(p) || (t == "s" && val[p] == "") || ((t == "a" || t == "o") && cnt[p] == 0)
}
function used(p) { return (type[p] == "s" && odd[p]) }
function out(p,   t, v) {
  t = type[p]
  v = val[p]
  if (t == "s" || t == "i") printf "%d:%s\n", length(v), v
  else printf "-\n"
}
{ s = s $0 "\n" }
END {
  n = length(s)
  pos = 1
  value("$")
  ws()
  if (pos <= n) err()
  P = "$" SUBSEP "result"
  if (type[P] != "o") err()
  P = P SUBSEP "pane"
  if (type[P] != "o") err()
  split("pane_id tab_id workspace_id agent foreground_cwd cwd terminal_id", F, " ")
  split("kind agent value", G, " ")
  Q = P SUBSEP "agent_session"
  if (ENVIRON["MODE"] == "status") {
    if (!(type[P SUBSEP "pane_id"] == "s" && !odd[P SUBSEP "pane_id"] && val[P SUBSEP "pane_id"] == ENVIRON["WANT"])) exit 3
    S = P SUBSEP "scroll"
    if (ENVIRON["ANYSCROLL"] == "1") { }
    else if (type[S] == "o") { if (!zero(S SUBSEP "offset_from_bottom")) exit 3 }
    else if (falsy(S)) exit 3
    else err()
    for (i = 1; i <= 7; i++) if (used(P SUBSEP F[i])) err()
    if (type[Q] == "o") for (i = 1; i <= 3; i++) if (used(Q SUBSEP G[i])) err()
    exit 0
  }
  for (i = 1; i <= 7; i++) out(P SUBSEP F[i])
  for (i = 1; i <= 3; i++) if (type[Q] == "o") out(Q SUBSEP G[i]); else printf "-\n"
}
'

# herdr's replies are kept as files, byte for byte: a shell variable can't hold a NUL (bash drops
# it), so one is looked for in the file itself and refused, as the Python check and the app do.
# They go in ~/.herdrdroid/check: a real folder (never a link), the user's own, with no group or
# other access. Each check makes its own two files there with mktemp (created new, 0600, never
# through a link, never shared with another check, even on the same pane) and removes exactly those
# two when it ends.
umask 077
[ -n "$HOME" ] || exit 4
[ -L "$HOME/.herdrdroid" ] || [ -L "$HOME/.herdrdroid/check" ] && exit 4
dir=$HOME/.herdrdroid/check
# A new folder is made private from the start; an existing one is checked as it is, never changed.
mkdir -p "$HOME/.herdrdroid" 2>/dev/null || exit 4
[ -e "$dir" ] || mkdir -m 700 "$dir" 2>/dev/null || [ -d "$dir" ] || exit 4
[ -d "$dir" ] && [ ! -L "$dir" ] || exit 4
set -- $(ls -ldn "$dir" 2>/dev/null)
[ "$3" = "$(id -u)" ] || exit 4
case $1 in d???------*) ;; *) exit 4 ;; esac
j=$(mktemp "$dir/j.XXXXXXXX" 2>/dev/null) || exit 4
v=$(mktemp "$dir/v.XXXXXXXX" 2>/dev/null) || { rm -f "$j"; exit 4; }
trap 'rm -f "$j" "$v"' EXIT
nul() { ! tr -d '\000' < "$1" | cmp -s - "$1"; }

# Sets id to the occupant digest and returns 0, or returns 3 (no occupant) or 4.
identity() {
  "$herdr" pane get "$pane" > "$j" 2>/dev/null || return 4
  nul "$j" && return 4
  MODE=status WANT=$pane ANYSCROLL=$anyscroll awk "$J" "$j" 2>/dev/null
  st=$?
  [ "$st" -eq 0 ] || { [ "$st" -eq 3 ] && return 3; return 4; }
  id=$(MODE=ser awk "$J" "$j" 2>/dev/null | sha)
  hex64 "$id" || return 4
  return 0
}

anyscroll=0
below=
case $screen in
  r:*)
    rest=${screen#r:}
    below=${rest%%:*}
    rest=${rest#*:}
    count=${rest%%:*}
    want=${rest#*:}
    case $below in '' | *[!0-9]*) { echo @@herdrdroid-card-refused@@; exit 3; } ;; esac
    case $count in '' | *[!0-9]* | 0*) { echo @@herdrdroid-card-refused@@; exit 3; } ;; esac
    [ ${#below} -le 6 ] && [ ${#count} -le 6 ] || { echo @@herdrdroid-card-refused@@; exit 3; }
    hex64 "$want" || { echo @@herdrdroid-card-refused@@; exit 3; }
    ;;
esac
if [ "$screen" = "-" ]; then
  anyscroll=1
  identity
  a=$?
  [ "$a" -eq 4 ] && exit 4
  before=$id
  identity
  b=$?
  [ "$b" -eq 4 ] && exit 4
  [ "$a" -eq 0 ] && [ "$b" -eq 0 ] && [ "$before" = "$id" ] && [ "$id" = "$occupant" ] && exit 0
  echo @@herdrdroid-occupant-refused@@
  exit 5
fi
identity
a=$?
[ "$a" -eq 4 ] && exit 4
before=$id
"$herdr" pane read "$pane" --source visible --format ansi > "$v" 2>/dev/null || exit 4
identity
b=$?
[ "$b" -eq 4 ] && exit 4
# A NUL in the screen never matches (cardScreenDigest; the Python check exits 3 too).
nul "$v" && { echo @@herdrdroid-card-refused@@; exit 3; }
# Hermes's countdown (cardTimerLine in Herdr.kt), and Oh My Pi's two running rows only around its
# approval box (cardScreenRows): every row of the box counts as it is.
T='
function shown(x,   t) {
  t = x
  gsub(/\033\[[0-9;?]*[ -\/]*[@-~]/, "", t)
  t = tolower(t)
  sub(/^[ \t\r\v\f]+/, "", t)
  sub(/[ \t\r\v\f]+$/, "", t)
  return t
}
function timer(x, t,   head) {
  if (t ~ /^(type [0-9\/]+, or )?↑\/↓ to select, enter to confirm[ \t\r\v\f]*\([0-9]+s\)$/ && match(x, /.*\([0-9]+s\)/)) {
    head = substr(x, 1, RLENGTH)
    sub(/\([0-9]+s\)$/, "(Ns)", head)
    x = head substr(x, RLENGTH + 1)
  }
  return x
}
{ raw[NR] = $0; row[NR] = shown($0); if (row[NR] ~ /^╭─ allow tool: .*╮$/) { tops++; top = NR } }
function boxrow(x) { return index(x, "│") == 1 || index(x, "├") == 1 }
END {
  # The one proven approval box (ompApprovalRegion in Herdr.kt), or none.
  bottom = 0
  elapsed = 0
  if (tops == 1) {
    for (i = top + 1; i <= NR; i++) {
      if (row[i] ~ /^╰(─)+╯$/) { bottom = i; break }
      if (!boxrow(row[i])) break
    }
    # A second bottom-shaped edge under the top: which one closes the box is not proven.
    if (bottom) for (i = bottom + 1; i <= NR; i++) if (row[i] ~ /^╰(─)+╯$/) bottom = 0
  }
  if (bottom) {
    lo = top - 3
    if (lo < 1) lo = 1
    for (i = top - 1; i >= lo; i--) {
      if (row[i] ~ /^╰(─)+ elapsed [0-9hms.]+ (─)+╯$/) { elapsed = i; break }
      if (index(row[i], "╭") == 1 || index(row[i], "╰") == 1 || boxrow(row[i])) break
    }
    if (elapsed) {
      j = elapsed - 1
      while (j >= 1 && index(row[j], "│") == 1) j--
      if (j < 1 || index(row[j], "╭") != 1) elapsed = 0
    }
  }
  for (i = 1; i <= NR; i++) {
    x = raw[i]
    if (!bottom) x = timer(x, row[i])
    else if (i >= top && i <= bottom) x = x
    else if (i == elapsed) sub(/elapsed [0-9hms.]+/, "elapsed N", x)
    else if (i == bottom + 1 && row[i] ~ /^[^ ]+ ([^ ]+ )?> .* > 📁 [^ ]+ ▶─/) x = "<status>"
    else x = timer(x, row[i])
    print x
  }
}
'
if [ -n "$below" ]; then
  # The dialog's rows only: "start" past the screen's top means they aren't there (exit 3).
  R='
{ r[NR] = $0 }
END {
  start = NR - below - count + 1
  if (start < 1) exit 3
  for (i = start; i <= NR - below; i++) {
    x = r[i]
    sub(/^[ \t\r\v\f]+/, "", x)
    sub(/[ \t\r\v\f]+$/, "", x)
    print x
  }
  # 2.2.8 (Codex review 4B): the rows under the dialog (cardUnderRow in Herdr.kt): each exactly, ends
  # trimmed, except a running status row (Grok, Claude/Codex/Antigravity, Hermes shapes), which counts
  # by its shape: styles dropped, each spinner glyph "*", each running time "T", each counter "⇣N",
  # any other digit 0.
  print "@@under@@"
  for (i = NR - below + 1; i <= NR; i++) {
    x = r[i]
    t = x
    gsub(/\033\[[0-9;?]*[ -\/]*[@-~]/, "", t)
    sub(/^[ \t\r\v\f]+/, "", t)
    sub(/[ \t\r\v\f]+$/, "", t)
    if (t ~ /^(\342[\240-\243][\200-\277]|◆|◇|◈) .*\[stop\]$/ ||
        t ~ /^(\342[\240-\243][\200-\277]|✻|✶|✳|✢|·|✽|◇|◆|•) .*\(.*esc to interrupt\)$/ ||
        t ~ /^(─)+ \342[\240-\243][\200-\277] Working/) {
      gsub(/\342[\240-\243][\200-\277]|·|✢|✳|✶|✻|✽|◐|◓|◑|◒|◆|◇|◈|•/, "*", t)
      gsub(/⇣ ?[0-9.]+[kKM]?/, "⇣N", t)
      gsub(/([0-9]+h ?)?([0-9]+m ?)?[0-9]+(\.[0-9]+)?[smh]/, "T", t)
      gsub(/[0-9]/, "0", t)
      print t
    } else {
      sub(/^[ \t\r\v\f]+/, "", x)
      sub(/[ \t\r\v\f]+$/, "", x)
      print x
    }
  }
}
'
  awk "$T" "$v" > "$j" 2>/dev/null || exit 4
  awk -v below="$below" -v count="$count" "$R" "$j" > "$v" 2>/dev/null
  st=$?
  [ "$st" -eq 3 ] && { echo @@herdrdroid-card-refused@@; exit 3; }
  [ "$st" -eq 0 ] || exit 4
  seen=$(sha < "$v")
  hex64 "$seen" || exit 4
  [ "$a" -eq 0 ] && [ "$b" -eq 0 ] && [ "$before" = "$id" ] && [ "$id" = "$occupant" ] && [ "$seen" = "$want" ] && exit 0
  echo @@herdrdroid-card-refused@@
  exit 3
fi
seen=$(awk "$T" "$v" 2>/dev/null | sha)
hex64 "$seen" || exit 4
[ "$a" -eq 0 ] && [ "$b" -eq 0 ] && [ "$before" = "$id" ] && [ "$id" = "$occupant" ] && [ "$seen" = "$screen" ] && exit 0
echo @@herdrdroid-card-refused@@
exit 3
