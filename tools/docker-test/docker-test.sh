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
#                        $HOME/.cache/herdrdroid-docker-test-runs); "latest" links to the newest
#   HD_TEST_MIRROR=1     fetch Robolectric's android-all jars from Google's Maven Central mirror
#                        (https://maven-central.storage-download.googleapis.com/maven2/), for when
#                        Maven Central answers HTTP 429. ROBOLECTRIC_REPO_URL names any other one.
#   HD_TEST_IMAGE        the image (default eclipse-temurin:17-jdk)
#   HD_DOCKER            the docker command, split on spaces (default docker; e.g. "sudo -n docker")
#   HD_DOCKER_ARGS       extra docker run options, split on spaces (no quoting inside)
#   HD_TEST_KEEP_SRC=1   keep the unpacked source in the run folder
#   HD_SDK_TOOLS_URL     another download address for the SDK command-line tools zip
#
# What reaches the container: a copy of the checkout streamed in with tar, without .git, keystore/,
# release/, local.properties or build output, plus the cache folder and this run's new output folder.
# The checkout itself is never mounted or written, and no signing file is ever in the container.
# The cache and output folders must be outside the checkout (and so outside keystore/), must not
# overlap each other, and must not be / or $HOME; the script checks this before it creates anything.
#
# Locks: one run at a time per user on this computer ($HOME/.cache/herdrdroid-docker-test.lock) and
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
CACHE=$(phys "${HD_TEST_CACHE:-$HOME/.cache/herdrdroid-docker-test}") || exit 2
OUT=$(phys "${HD_TEST_OUT:-$HOME/.cache/herdrdroid-docker-test-runs}") || exit 2
check_dir HD_TEST_CACHE "$CACHE"; check_dir HD_TEST_OUT "$OUT"
overlaps "$CACHE" "$OUT" && { echo "HD_TEST_CACHE and HD_TEST_OUT must not overlap: $CACHE, $OUT" >&2; exit 2; }

mkdir -p "$CACHE/sdk" "$CACHE/gradle" "$CACHE/m2" "$OUT"
if command -v flock >/dev/null; then
  mkdir -p "$HOME/.cache"
  exec 8>"$HOME/.cache/herdrdroid-docker-test.lock" 9>"$CACHE/lock"
  flock -n 8 && flock -n 9 || { echo "waiting for another HerdrDroid test run to finish..."; flock 8; flock 9; }
else
  echo "warning: flock not found; make sure no other HerdrDroid test run is going on this computer" >&2
fi

# A new folder for this run only: nothing in it comes from an earlier run.
RUN=$(mktemp -d "$OUT/run-$(date +%Y%m%d-%H%M%S)-XXXXXX")
ln -sfn "${RUN##*/}" "$OUT/latest"
echo "$MODE${*:+ ($*)} in $IMAGE; cache $CACHE; output $RUN${MIRROR:+; Robolectric from $MIRROR}"
set +e
tar -C "$REPO" --exclude=./.git --exclude=./keystore --exclude=./release --exclude=./build \
    --exclude=./app/build --exclude=./.gradle --exclude=./.kotlin --exclude=./.idea \
    --exclude=./local.properties -cf - . |
  "${DOCKER[@]}" run --rm -i --name "herdrdroid-test-$$" ${DOCKER_ARGS[@]+"${DOCKER_ARGS[@]}"} \
    --user "$(id -u):$(id -g)" --tmpfs /w/home:exec,uid="$(id -u)",gid="$(id -g)" \
    -e MODE="$MODE" -e ROBOLECTRIC_REPO_URL="$MIRROR" -e HOME=/w/home \
    -e HD_TEST_KEEP_SRC="${HD_TEST_KEEP_SRC:-0}" -e HD_SDK_TOOLS_URL="${HD_SDK_TOOLS_URL:-}" \
    -v "$CACHE:/w/cache" -v "$CACHE/m2:/w/home/.m2" -v "$RUN:/w/out" -v "$HERE/run.sh:/w/run.sh:ro" \
    "$IMAGE" bash /w/run.sh "$@"
RC=("${PIPESTATUS[@]}")
set -e
STATUS=${RC[1]}; [ "${RC[0]}" != 0 ] && [ "$STATUS" = 0 ] && STATUS=${RC[0]}
echo "---- summary ($RUN/summary.txt; full log in $RUN/build.log)"
cat "$RUN/summary.txt" 2>/dev/null || echo "no summary: the container stopped early (docker exit $STATUS)"
exit "$STATUS"
