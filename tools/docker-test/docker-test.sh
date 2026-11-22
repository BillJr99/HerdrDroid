#!/usr/bin/env bash
# Runs the HerdrDroid unit tests (or a compile check, or the tests plus a debug APK) in a throwaway
# eclipse-temurin:17-jdk container. Needs only Docker, bash and tar on the host.
#
# Usage, from the repository root:
#   bash tools/docker-test/docker-test.sh [compile|tests|full] [Gradle --tests patterns...]
#   compile   compile the app, unit-test and androidTest sources (fast)
#   tests     the unit tests matching the patterns, e.g. com.billmongan.herdrdroid.ui.CardBodyTest
#             or '*PanePrefetch*' (all unit tests when no pattern is given); quote wildcards
#   full      (default) every unit test, then the debug APK
#
# Environment:
#   HD_TEST_CACHE        persistent cache for the Android SDK, Gradle and Robolectric's ~/.m2
#                        (default $HOME/.cache/herdrdroid-docker-test). The first run installs the
#                        SDK there; later runs reuse it.
#   HD_TEST_OUT          the folder that holds one new run-* folder per run (default
#                        $HOME/.cache/herdrdroid-docker-test-runs, where "latest" links to the newest)
#   HD_TEST_MIRROR=1     fetch Robolectric's android-all jars from Google's Maven Central mirror
#                        (https://maven-central.storage-download.googleapis.com/maven2/), for when
#                        Maven Central answers HTTP 429. ROBOLECTRIC_REPO_URL names any other one.
#   HD_TEST_IMAGE        the image (default eclipse-temurin:17-jdk)
#   HD_DOCKER            the docker command, split on spaces (default docker; e.g. "sudo -n docker")
#   HD_DOCKER_ARGS       extra docker run options, split on spaces (no quoting inside)
#   HD_TEST_KEEP_SRC=1   keep the unpacked source in the run folder
#   HD_TEST_WORKTREE=1   test the working tree's changes to tracked files (edits and deletions), not
#                        just the last commit; HD_TEST_INCLUDE_UNTRACKED=1 also adds untracked files
#                        git does not ignore (and implies HD_TEST_WORKTREE=1)
#   HD_SDK_TOOLS_URL     another download address for the SDK command-line tools zip
#
# What reaches the container: the committed tree (HEAD), written by git archive from git's objects
# without keystore/, release/ or local.properties; the cache folder; and this run's new output folder.
# With HD_TEST_WORKTREE=1 the tree is first built in a separate index from the working tree by git
# itself, which records a symlink as a link and never reads through one (this adds objects to the
# repository, as git add does). The working tree is never mounted or written, and the user's own index
# and stash are not touched.
# The cache and output folders must be outside the checkout (and so outside keystore/), must not
# overlap each other, and must not be / or $HOME; the script checks this before it creates anything.
#
# Locks: one run at a time per user on this computer ($HOME/.cache/herdrdroid-docker-test-locks/user.lock) and
# per cache (<cache>/lock), because two Robolectric runs at once starve each other and fail with
# false AppNotIdleException errors. Without flock (e.g. macOS) the script warns and runs unlocked.
set -euo pipefail
MODE=${1:-full}; [ $# -gt 0 ] && shift
case "$MODE" in compile|tests|full) ;; *) echo "usage: $0 [compile|tests|full] [test patterns...]" >&2; exit 2 ;; esac
[ "$MODE" != tests ] && [ $# -gt 0 ] && { echo "test patterns go with the tests mode only" >&2; exit 2; }
HERE=$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REPO=$(cd -P "$HERE/../.." && pwd -P)
[ -f "$REPO/app/build.gradle.kts" ] || { echo "cannot find the repository around $HERE" >&2; exit 2; }
IMAGE=${HD_TEST_IMAGE:-eclipse-temurin:17-jdk}
read -r -a DOCKER <<< "${HD_DOCKER:-docker}"
read -r -a DOCKER_ARGS <<< "${HD_DOCKER_ARGS:-}"
MIRROR=${ROBOLECTRIC_REPO_URL:-}
[ "${HD_TEST_MIRROR:-0}" = 1 ] && [ -z "$MIRROR" ] && MIRROR=https://maven-central.storage-download.googleapis.com/maven2/

# The physical path of $1, which need not exist yet: the deepest existing folder is resolved with
# cd -P (so symlinks count), and the rest is appended. Relative paths and ".." are refused.
phys() {
  local p=$1 rest=""
  case "$p" in /*) ;; *) echo "not an absolute path: $p" >&2; return 1 ;; esac
  case "/$p/" in */../*|*/./*) echo "no . or .. in paths, please: $p" >&2; return 1 ;; esac
  p=${p%/}; [ -z "$p" ] && p=/
  while [ ! -d "$p" ]; do
    [ -e "$p" ] && { echo "exists and is not a folder: $p" >&2; return 1; }
    rest=/${p##*/}$rest; p=${p%/*}; [ -z "$p" ] && p=/
  done
  p=$(cd -P "$p" && pwd -P)
  [ "$p" = / ] && echo "${rest:-/}" || echo "$p$rest"
}
# Whether two physical paths are the same, or one is inside the other.
overlaps() { case "$1/" in "$2"/*) return 0 ;; esac; case "$2/" in "$1"/*) return 0 ;; esac; return 1; }
check_dir() { # check_dir NAME PATH
  local name=$1 p=$2 home
  home=$(cd -P "$HOME" && pwd -P)
  [ "$p" = / ] || [ "$p" = "$home" ] && { echo "$name may not be / or \$HOME: $p" >&2; exit 2; }
  overlaps "$p" "$REPO" && { echo "$name must be outside the checkout ($REPO): $p" >&2; exit 2; }
  if [ -e "$REPO/keystore" ]; then
    local ks; ks=$(cd -P "$REPO/keystore" 2>/dev/null && pwd -P || phys "$REPO/keystore")
    overlaps "$p" "$ks" && { echo "$name must not overlap the keystore folder ($ks): $p" >&2; exit 2; }
  fi
  return 0
}
DEFAULT_OUT=$HOME/.cache/herdrdroid-docker-test-runs
CACHE=$(phys "${HD_TEST_CACHE:-$HOME/.cache/herdrdroid-docker-test}") || exit 2
OUT=$(phys "${HD_TEST_OUT:-$DEFAULT_OUT}") || exit 2
check_dir HD_TEST_CACHE "$CACHE"; check_dir HD_TEST_OUT "$OUT"
overlaps "$CACHE" "$OUT" && { echo "HD_TEST_CACHE and HD_TEST_OUT must not overlap: $CACHE, $OUT" >&2; exit 2; }

# A folder this script uses: made here if missing, else it must be a real folder (not a symlink)
# owned by this user, whose physical path passes the same checks as the cache and output roots.
own_dir() { # own_dir NAME PATH
  local name=$1 p=$2
  [ -L "$p" ] && { echo "$name is a symlink; it must be a real folder: $p" >&2; exit 2; }
  if [ ! -e "$p" ]; then mkdir -p "$p" || exit 2; fi
  [ -d "$p" ] && [ ! -L "$p" ] && [ -O "$p" ] || { echo "$name must be a folder owned by $(id -un): $p" >&2; exit 2; }
  [ "$(cd -P "$p" && pwd -P)" = "$p" ] || { echo "$name resolves somewhere else: $p" >&2; exit 2; }
  check_dir "$name" "$p"
}
own_dir HD_TEST_CACHE "$CACHE"; own_dir HD_TEST_OUT "$OUT"
for d in sdk gradle m2; do own_dir "cache folder $d" "$CACHE/$d"; done
[ -f "$HERE/run.sh" ] && [ ! -L "$HERE/run.sh" ] || { echo "run.sh must be a regular file: $HERE/run.sh" >&2; exit 2; }

# The build inputs come from git's objects, never by reading paths in the working tree.
command -v git >/dev/null && git -C "$REPO" rev-parse --verify -q HEAD >/dev/null 2>&1 \
  || { echo "the checkout must be a git working tree with at least one commit: $REPO" >&2; exit 2; }
WORKTREE=${HD_TEST_WORKTREE:-0}; [ "${HD_TEST_INCLUDE_UNTRACKED:-0}" = 1 ] && WORKTREE=1
EXCLUDE=(':(exclude,top)keystore' ':(exclude,top)release' ':(exclude,top)local.properties')

# A lock file, opened on file descriptor $1 without truncating or writing it. The path must be a
# regular file (made here if missing), not a symlink; after opening, the descriptor must be that
# same file, which catches a swap between the check and the open.
open_lock() { # open_lock FD PATH
  local fd=$1 f=$2
  [ -L "$f" ] && { echo "lock file is a symlink: $f" >&2; exit 2; }
  [ ! -e "$f" ] || [ -f "$f" ] || { echo "lock file is not a regular file: $f" >&2; exit 2; }
  eval "exec $fd<>\"\$f\"" || exit 2
  [ -f "/dev/fd/$fd" ] && [ ! -L "$f" ] && [ -O "$f" ] \
    && [ "$(stat -L -c '%d:%i' "/dev/fd/$fd")" = "$(stat -c '%d:%i' "$f")" ] \
    || { echo "lock file changed while it was opened: $f" >&2; exit 2; }
}
if command -v flock >/dev/null; then
  own_dir "lock folder" "$HOME/.cache/herdrdroid-docker-test-locks"
  open_lock 8 "$HOME/.cache/herdrdroid-docker-test-locks/user.lock"
  open_lock 9 "$CACHE/lock"
  flock -n 8 && flock -n 9 || { echo "waiting for another HerdrDroid test run to finish..."; flock 8; flock 9; }
else
  echo "warning: flock not found; make sure no other HerdrDroid test run is going on this computer" >&2
fi

# A new folder for this run only: nothing in it comes from an earlier run.
RUN=$(mktemp -d "$OUT/run-$(date +%Y%m%d-%H%M%S)-XXXXXX")
link_owner() { stat -c %u "$1" 2>/dev/null || stat -f %u "$1"; }  # GNU, then BSD stat
# "latest" is kept only in the default output folder, and only replaces this user's own symlink.
if [ "$OUT" = "$(phys "$DEFAULT_OUT")" ]; then
  # stat without -L: the owner of the link itself, not of what it points at.
  if [ ! -e "$OUT/latest" ] && [ ! -L "$OUT/latest" ] || { [ -L "$OUT/latest" ] && [ "$(link_owner "$OUT/latest")" = "$(id -u)" ]; }; then
    ln -sfn "${RUN##*/}" "$OUT/latest"
  else
    echo "note: $OUT/latest is not this script's link, so it was left alone" >&2
  fi
fi
# The tree to test: HEAD, or HEAD updated from the working tree in a separate index in the run folder.
TREE=HEAD
if [ "$WORKTREE" = 1 ]; then
  ADD=(-u); [ "${HD_TEST_INCLUDE_UNTRACKED:-0}" = 1 ] && ADD=(-A)
  TREE=$(export GIT_INDEX_FILE="$RUN/stage-index"
         git -C "$REPO" read-tree HEAD && git -C "$REPO" add "${ADD[@]}" -- . && git -C "$REPO" write-tree) \
    || { echo "could not stage the working tree" >&2; exit 2; }
fi
echo "$MODE${*:+ ($*)} in $IMAGE; cache $CACHE; output $RUN${MIRROR:+; Robolectric from $MIRROR}"
echo "source: $([ "$WORKTREE" = 1 ] && echo "working tree (tree $TREE)" || echo "HEAD $(git -C "$REPO" rev-parse --short HEAD)")"
set +e
git -C "$REPO" archive --format=tar "$TREE" -- . "${EXCLUDE[@]}" |
  "${DOCKER[@]}" run --rm -i --name "herdrdroid-test-$$" ${DOCKER_ARGS[@]+"${DOCKER_ARGS[@]}"} \
    --user "$(id -u):$(id -g)" --tmpfs /w/home:exec,uid="$(id -u)",gid="$(id -g)" \
    -e MODE="$MODE" -e ROBOLECTRIC_REPO_URL="$MIRROR" -e HOME=/w/home \
    -e HD_TEST_KEEP_SRC="${HD_TEST_KEEP_SRC:-0}" -e HD_SDK_TOOLS_URL="${HD_SDK_TOOLS_URL:-}" \
    -v "$CACHE:/w/cache" -v "$CACHE/m2:/w/home/.m2" -v "$RUN:/w/out" -v "$HERE/run.sh:/w/run.sh:ro" \
    "$IMAGE" bash /w/run.sh "$@"
RC=("${PIPESTATUS[@]}")
set -e
# The status: docker's (run.sh's), else the source stream's, else the summary's own.
STATUS=${RC[1]}
[ "$STATUS" = 0 ] && [ "${RC[0]}" != 0 ] && STATUS=${RC[0]}
echo "---- summary ($RUN/summary.txt; full log in $RUN/build.log)"
if [ -f "$RUN/summary.txt" ] && SUMMARY_EXIT=$(sed -n '1s/^exit=\([0-9][0-9]*\)$/\1/p' "$RUN/summary.txt") && [ -n "$SUMMARY_EXIT" ]; then
  cat "$RUN/summary.txt"
  [ "$STATUS" = 0 ] && [ "$SUMMARY_EXIT" != 0 ] && STATUS=$SUMMARY_EXIT
else
  echo "no summary from this run (exit $STATUS); see $RUN/build.log"
  [ "$STATUS" = 0 ] && STATUS=1
fi
exit "$STATUS"
