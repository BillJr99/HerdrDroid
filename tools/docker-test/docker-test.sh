#!/usr/bin/env bash
# Runs the HerdrDroid unit tests (or a compile check, or the tests plus a debug APK) in a throwaway
# eclipse-temurin:17-jdk container. Needs only Docker and bash on the host.
#
# Usage: bash tools/docker-test/docker-test.sh [compile|tests|full] [Gradle --tests patterns...]
#   compile   compile the app, unit-test and androidTest sources (fast)
#   tests     the unit tests matching the patterns, e.g. com.billmongan.herdrdroid.ui.CardBodyTest
#             or '*PanePrefetch*' (all unit tests when no pattern is given)
#   full      (default) every unit test, then the debug APK
#
# Environment:
#   HD_TEST_CACHE        persistent cache for the Android SDK, Gradle and Robolectric's ~/.m2
#                        (default $HOME/.cache/herdrdroid-docker-test). The first run installs the
#                        SDK there; later runs reuse it.
#   HD_TEST_OUT          output folder (default <repo>/build/docker-test): build.log, summary.txt,
#                        test-results/, and the debug APK in full mode
#   HD_TEST_MIRROR=1     fetch Robolectric's android-all jars from Google's Maven Central mirror
#                        (https://maven-central.storage-download.googleapis.com/maven2/), for when
#                        Maven Central answers HTTP 429. ROBOLECTRIC_REPO_URL names any other one.
#   HD_TEST_IMAGE        the image (default eclipse-temurin:17-jdk)
#   HD_DOCKER            the docker command (default docker; e.g. "sudo -n docker")
#   HD_DOCKER_ARGS       extra docker run options
#
# One run at a time per host: the script holds a lock (flock on <cache>/lock), because two Robolectric
# runs at once starve each other and fail with false AppNotIdleException errors. It never mounts or
# reads keystore/ or any signing key.
set -euo pipefail
MODE=${1:-full}; shift 2>/dev/null || true
case "$MODE" in compile|tests|full) ;; *) echo "usage: $0 [compile|tests|full] [test patterns...]" >&2; exit 2 ;; esac
TESTS="$*"
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
CACHE=${HD_TEST_CACHE:-$HOME/.cache/herdrdroid-docker-test}
OUT=${HD_TEST_OUT:-$REPO/build/docker-test}
IMAGE=${HD_TEST_IMAGE:-eclipse-temurin:17-jdk}
DOCKER=${HD_DOCKER:-docker}
MIRROR=${ROBOLECTRIC_REPO_URL:-}
[ "${HD_TEST_MIRROR:-0}" = 1 ] && [ -z "$MIRROR" ] && MIRROR=https://maven-central.storage-download.googleapis.com/maven2/
mkdir -p "$CACHE/sdk" "$CACHE/gradle" "$CACHE/m2" "$OUT"
CACHE=$(cd "$CACHE" && pwd); OUT=$(cd "$OUT" && pwd)

if command -v flock >/dev/null; then
  exec 9>"$CACHE/lock"
  flock -n 9 || { echo "waiting for another HerdrDroid test run on this host to finish..."; flock 9; }
else
  echo "warning: flock not found; make sure no other HerdrDroid test run is going on this host" >&2
fi

# keystore/ is hidden behind an empty in-memory folder inside the container.
HIDE_KEYS=""; [ -d "$REPO/keystore" ] && HIDE_KEYS="--mount type=tmpfs,destination=/w/src/keystore"
NAME=herdrdroid-test-$$
echo "$MODE ${TESTS:+($TESTS) }in $IMAGE; cache $CACHE; output $OUT${MIRROR:+; Robolectric from $MIRROR}"
# shellcheck disable=SC2086
$DOCKER run --rm --name "$NAME" ${HD_DOCKER_ARGS:-} \
  -e MODE="$MODE" -e TESTS="$TESTS" -e ROBOLECTRIC_REPO_URL="$MIRROR" \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  -v "$REPO:/w/src" -v "$CACHE:/w/cache" -v "$CACHE/m2:/root/.m2" -v "$OUT:/w/out" \
  -v "$HERE/run.sh:/w/run.sh:ro" $HIDE_KEYS \
  "$IMAGE" bash /w/run.sh

echo "---- summary ($OUT/summary.txt; full log in $OUT/build.log)"
cat "$OUT/summary.txt" 2>/dev/null || { echo "no summary; see $OUT/build.log"; exit 1; }
grep -q '^exit=0' "$OUT/summary.txt"
