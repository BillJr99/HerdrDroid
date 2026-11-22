#!/usr/bin/env bash
# Runs inside the eclipse-temurin:17-jdk container that docker-test.sh starts. Do not run it on the host.
#   /w/src    the repository (mounted read-write; Gradle writes app/build and .gradle there)
#   /w/cache  the persistent cache: sdk/ (Android SDK), gradle/ (GRADLE_USER_HOME), m2/ (mounted at
#             /root/.m2, where Robolectric keeps its android-all jars)
#   /w/out    the output folder: build.log, summary.txt, test-results/, and the debug APK (full mode)
# MODE=compile  compile the app, unit-test and androidTest sources only (a fast check)
# MODE=tests    the unit tests matching TESTS (space-separated Gradle --tests patterns)
# MODE=full     every unit test, then assembleDebug
# ROBOLECTRIC_REPO_URL  optional Maven repository for Robolectric's android-all jars
set -u
MODE=${MODE:-full}
export ANDROID_HOME=/w/cache/sdk ANDROID_SDK_ROOT=/w/cache/sdk GRADLE_USER_HOME=/w/cache/gradle
export JAVA_HOME=${JAVA_HOME:-/opt/java/openjdk}
# An empty folder for the signing key, so the build never reads keystore/ (tests and debug builds
# need no key; the release build is not run here).
export HD_KEYSTORE_DIR=/w/no-keystore
mkdir -p "$HD_KEYSTORE_DIR" /w/out
LOG=/w/out/build.log; : > "$LOG"
cd /w/src

fetch() { # fetch URL FILE
  if command -v curl >/dev/null; then curl -fsSL --retry 3 -o "$2" "$1"
  elif command -v wget >/dev/null; then wget -q -O "$2" "$1"
  else apt-get update -qq >/dev/null && apt-get install -y -qq curl >/dev/null && curl -fsSL --retry 3 -o "$2" "$1"; fi
}

# The SDK packages this build needs, read from app/build.gradle.kts. AGP 8.5 uses build-tools 34.0.0
# unless buildToolsVersion says otherwise.
PLATFORM=$(sed -nE 's/^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts | head -1)
BUILD_TOOLS=$(sed -nE 's/^[[:space:]]*buildToolsVersion[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -1)
PLATFORM=${PLATFORM:-34}; BUILD_TOOLS=${BUILD_TOOLS:-${HD_BUILD_TOOLS:-34.0.0}}
if [ ! -d "$ANDROID_HOME/platforms/android-$PLATFORM" ] || [ ! -d "$ANDROID_HOME/build-tools/$BUILD_TOOLS" ]; then
  echo "first use: installing Android SDK platform $PLATFORM and build-tools $BUILD_TOOLS into the cache" | tee -a "$LOG"
  SDKM=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1)
  if [ -z "$SDKM" ]; then
    T=$(mktemp -d)
    fetch https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip "$T/clt.zip" || { echo "exit=1 (could not download the SDK command-line tools)" | tee -a "$LOG"; exit 0; }
    # The JDK's jar tool unpacks the zip, so the image needs no unzip.
    (cd "$T" && jar xf clt.zip) && chmod +x "$T"/cmdline-tools/bin/*
    mkdir -p "$ANDROID_HOME/cmdline-tools" && mv "$T/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  fi
  yes | "$SDKM" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  "$SDKM" --sdk_root="$ANDROID_HOME" "platforms;android-$PLATFORM" "build-tools;$BUILD_TOOLS" >> "$LOG" 2>&1 \
    || { echo "exit=1 (sdkmanager failed; see above)" >> "$LOG"; exit 0; }
fi

# local.properties points Gradle at the cached SDK. A developer's own copy is put back afterwards.
[ -f local.properties ] && cp local.properties /w/local.properties.orig
echo "sdk.dir=$ANDROID_HOME" > local.properties
restore() {
  if [ -f /w/local.properties.orig ]; then cp /w/local.properties.orig local.properties; else rm -f local.properties; fi
}
trap restore EXIT

G="./gradlew --no-daemon --console=plain"
[ -n "${ROBOLECTRIC_REPO_URL:-}" ] && G="$G -Probolectric.repo.url=$ROBOLECTRIC_REPO_URL"
START=$(date +%s)
case "$MODE" in
  compile) $G compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin >> "$LOG" 2>&1 ;;
  tests)   F=""; for t in ${TESTS:-}; do F="$F --tests $t"; done; $G testDebugUnitTest $F >> "$LOG" 2>&1 ;;
  full)    $G testDebugUnitTest assembleDebug >> "$LOG" 2>&1 ;;
  *)       echo "unknown MODE $MODE (compile, tests or full)" >> "$LOG"; false ;;
esac
RC=$?
echo "exit=$RC seconds=$(( $(date +%s) - START ))" >> "$LOG"

rm -rf /w/out/test-results && mkdir -p /w/out/test-results
# Test results only from a run that ran tests (a compile run would pick up an older run's files).
[ "$MODE" != compile ] && cp -r app/build/test-results/testDebugUnitTest /w/out/test-results/ 2>/dev/null
[ "$MODE" = full ] && cp app/build/outputs/apk/debug/*.apk /w/out/ 2>/dev/null

# The summary: exit status, compile errors, failing tests and totals.
{
  grep -E '^exit=' "$LOG" | tail -1
  grep -E '^BUILD (SUCCESSFUL|FAILED)' "$LOG" | tail -1
  grep -E '^e: ' "$LOG" | head -10
  t=0 f=0 e=0 s=0 bad=()
  n() { echo "$1" | sed -nE "s/.* $2=\"([0-9]+)\".*/\\1/p"; }
  for x in /w/out/test-results/testDebugUnitTest/*.xml; do
    [ -f "$x" ] || continue
    h=$(grep -m1 -o '<testsuite [^>]*>' "$x")
    t=$((t + $(n "$h" tests))); f=$((f + $(n "$h" failures))); e=$((e + $(n "$h" errors))); s=$((s + $(n "$h" skipped)))
    c=${x##*/TEST-}; c=${c%.xml}
    while read -r m; do bad+=("FAIL $c.$m"); done < <(grep -B1 -E '<(failure|error) ' "$x" | sed -nE 's/.*<testcase name="([^"]+)".*/\1/p' | head -3)
  done
  [ ${#bad[@]} -gt 0 ] && printf '%s\n' "${bad[@]:0:20}"
  [ -d /w/out/test-results/testDebugUnitTest ] && echo "tests=$t failures=$f errors=$e skipped=$s"
  ls /w/out/*.apk 2>/dev/null | sed 's/^/apk: /'
} > /w/out/summary.txt 2>/dev/null
chown -R "${HOST_UID:-0}:${HOST_GID:-0}" /w/out /w/src/app/build /w/src/.gradle /w/src/build /w/src/.kotlin /w/cache 2>/dev/null
exit 0
