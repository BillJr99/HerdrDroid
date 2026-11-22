#!/usr/bin/env bash
# Runs inside the eclipse-temurin:17-jdk container that docker-test.sh starts, as the host user.
# Do not run it on the host. Usage: bash /w/run.sh [Gradle --tests patterns...] with MODE set.
#   stdin     a tar stream of the checkout (no .git, keystore/, release/ or build output)
#   /w/out    this run's new, empty output folder: build.log, summary.txt, test-results/, the debug
#             APK (full mode). The source is unpacked into /w/out/src and removed at the end.
#   /w/cache  the persistent cache: sdk/ (Android SDK), gradle/ (GRADLE_USER_HOME), m2/ (mounted at
#             /w/home/.m2, where Robolectric keeps its android-all jars)
#   /w/home   an in-memory home folder
# MODE=compile  compile the app, unit-test and androidTest sources only (a fast check)
# MODE=tests    the unit tests matching the arguments (all unit tests when there are none)
# MODE=full     every unit test, then assembleDebug
# ROBOLECTRIC_REPO_URL  optional Maven repository for Robolectric's android-all jars
# The exit status is Gradle's, or 1 when the source or the SDK could not be set up.
set -u
MODE=${MODE:-full}
OUT=/w/out LOG=/w/out/build.log SRC=/w/out/src
export ANDROID_HOME=/w/cache/sdk ANDROID_SDK_ROOT=/w/cache/sdk GRADLE_USER_HOME=/w/cache/gradle
export JAVA_HOME=${JAVA_HOME:-/opt/java/openjdk}
# The user has no passwd entry in the image, so name the home folder for Java (Robolectric uses it).
export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
# An empty folder for the signing key: tests and debug builds need none, and the build never reads one.
export HD_KEYSTORE_DIR=$HOME/no-keystore
mkdir -p "$HD_KEYSTORE_DIR"
: > "$LOG"
START=$(date +%s)

# The summary: exit status, compile errors, failing tests and totals, from this run's files only.
finish() {
  local rc=$1
  echo "exit=$rc seconds=$(( $(date +%s) - START ))" >> "$LOG"
  {
    echo "exit=$rc"
    grep -E '^BUILD (SUCCESSFUL|FAILED)' "$LOG" | tail -1
    grep -E '^e: ' "$LOG" | head -10
    grep -E '^SETUP: ' "$LOG" | head -5
    if [ -d "$OUT/test-results/testDebugUnitTest" ]; then
      local t=0 f=0 e=0 s=0 x h c m bad=()
      n() { echo "$1" | sed -nE "s/.* $2=\"([0-9]+)\".*/\\1/p"; }
      for x in "$OUT"/test-results/testDebugUnitTest/*.xml; do
        [ -f "$x" ] || continue
        h=$(grep -m1 -o '<testsuite [^>]*>' "$x")
        t=$((t + $(n "$h" tests))); f=$((f + $(n "$h" failures))); e=$((e + $(n "$h" errors))); s=$((s + $(n "$h" skipped)))
        c=${x##*/TEST-}; c=${c%.xml}
        while read -r m; do bad+=("FAIL $c.$m"); done < <(grep -B1 -E '<(failure|error) ' "$x" | sed -nE 's/.*<testcase name="([^"]+)".*/\1/p' | head -3)
      done
      [ ${#bad[@]} -gt 0 ] && printf '%s\n' "${bad[@]:0:20}"
      echo "tests=$t failures=$f errors=$e skipped=$s"
    fi
    for x in "$OUT"/*.apk; do [ -f "$x" ] && echo "apk: $x"; done
  } > "$OUT/summary.txt"
  # The unpacked source and its build folders are this run's own; the results are copied out.
  [ "${HD_TEST_KEEP_SRC:-0}" = 1 ] || rm -rf "$SRC"
  exit "$rc"
}
setup_failed() { echo "SETUP: $*" >> "$LOG"; finish 1; }

mkdir "$SRC" && tar -xf - -C "$SRC" || setup_failed "could not unpack the source"
cd "$SRC" || setup_failed "no source folder"

fetch() { # fetch URL FILE
  if command -v curl >/dev/null; then curl -fsSL --retry 3 -o "$2" "$1"
  elif command -v wget >/dev/null; then wget -q -O "$2" "$1"
  else echo "neither curl nor wget is in the image" >&2; return 1; fi
}

# The SDK packages this build needs, read from app/build.gradle.kts. AGP 8.5 uses build-tools 34.0.0
# unless buildToolsVersion says otherwise.
PLATFORM=$(sed -nE 's/^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts | head -1)
BUILD_TOOLS=$(sed -nE 's/^[[:space:]]*buildToolsVersion[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -1)
PLATFORM=${PLATFORM:-34}; BUILD_TOOLS=${BUILD_TOOLS:-${HD_BUILD_TOOLS:-34.0.0}}
CLT_URL=${HD_SDK_TOOLS_URL:-}; CLT_URL=${CLT_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}
if [ ! -d "$ANDROID_HOME/platforms/android-$PLATFORM" ] || [ ! -d "$ANDROID_HOME/build-tools/$BUILD_TOOLS" ]; then
  echo "first use: installing Android SDK platform $PLATFORM and build-tools $BUILD_TOOLS into the cache" | tee -a "$LOG"
  SDKM=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1)
  if [ -z "$SDKM" ]; then
    T=$HOME/clt; mkdir -p "$T"
    fetch "$CLT_URL" "$T/clt.zip" >> "$LOG" 2>&1 || setup_failed "could not download the SDK command-line tools from $CLT_URL"
    # The JDK's jar tool unpacks the zip, so the image needs no unzip.
    (cd "$T" && jar xf clt.zip) && chmod +x "$T"/cmdline-tools/bin/* || setup_failed "could not unpack the SDK command-line tools"
    mkdir -p "$ANDROID_HOME/cmdline-tools" && mv "$T/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" || setup_failed "could not install the SDK command-line tools"
    SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  fi
  yes | "$SDKM" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1
  "$SDKM" --sdk_root="$ANDROID_HOME" "platforms;android-$PLATFORM" "build-tools;$BUILD_TOOLS" 2>&1 | tr '\r' '\n' | grep -vE '^\s*\[|^Unzipping|^Downloading' >> "$LOG"
  [ "${PIPESTATUS[0]}" = 0 ] && [ -d "$ANDROID_HOME/platforms/android-$PLATFORM" ] && [ -d "$ANDROID_HOME/build-tools/$BUILD_TOOLS" ] \
    || setup_failed "sdkmanager could not install platform $PLATFORM and build-tools $BUILD_TOOLS"
fi

# local.properties in the unpacked copy only: the checkout is never written.
echo "sdk.dir=$ANDROID_HOME" > local.properties

G=(./gradlew --no-daemon --console=plain)
[ -n "${ROBOLECTRIC_REPO_URL:-}" ] && G+=("-Probolectric.repo.url=$ROBOLECTRIC_REPO_URL")
case "$MODE" in
  compile) G+=(compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin) ;;
  tests)   G+=(testDebugUnitTest); for t in "$@"; do G+=(--tests "$t"); done ;;
  full)    G+=(testDebugUnitTest assembleDebug) ;;
  *)       setup_failed "unknown MODE $MODE (compile, tests or full)" ;;
esac
"${G[@]}" >> "$LOG" 2>&1
RC=$?

# Only what this run produced: the unpacked copy started with no build folders.
if [ "$MODE" != compile ] && [ -d app/build/test-results/testDebugUnitTest ]; then
  mkdir -p "$OUT/test-results" && cp -r app/build/test-results/testDebugUnitTest "$OUT/test-results/"
fi
if [ "$MODE" = full ]; then
  for a in app/build/outputs/apk/debug/*.apk; do [ -f "$a" ] && cp "$a" "$OUT/"; done
fi
finish "$RC"
