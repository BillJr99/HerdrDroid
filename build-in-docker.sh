#!/usr/bin/env bash
# Builds the signed HerdrDroid APK in a throwaway container.
# Usage: bash build-in-docker.sh <work-folder>
#   <work-folder>/src   the source (this folder's contents), including keystore/signing.properties
#                       with storePassword, keyAlias and keyPassword (git ignores that file)
#   <work-folder>/.cache   SDK and Gradle caches (reused between builds; delete to clean)
#   <work-folder>/out   the APK
#   HD_DOCKER_ARGS     optional extra docker run options (see the end of this file)
set -euo pipefail
W="$(cd "$1" && pwd)"
mkdir -p "$W/out" "$W/.cache"
cat > "$W/.cache/inner.sh" <<'INNER'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq unzip curl >/dev/null
export ANDROID_HOME=/w/.cache/sdk GRADLE_USER_HOME=/w/.cache/gradle
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf /tmp/clt && unzip -q /tmp/clt.zip -d /tmp/clt
  mkdir -p "$ANDROID_HOME/cmdline-tools" && mv /tmp/clt/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
"$SDKM" "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null
G=/w/.cache/gradle-8.9/bin/gradle
if [ ! -x "$G" ]; then
  curl -fsSL -o /tmp/g.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
  unzip -q /tmp/g.zip -d /w/.cache
fi
cd /w/src
mkdir -p keystore
P=keystore/signing.properties
[ -f "$P" ] || { echo "missing $P (storePassword, keyAlias, keyPassword)" >&2; exit 1; }
prop() { sed -n "s/^$1=//p" "$P" | head -1; }
# Make the key only when there is none, so every build signs with the same one and installs over the last.
if [ ! -f keystore/herdrdroid-sideload.jks ]; then
  SP="$(prop storePassword)" KP="$(prop keyPassword)" keytool -genkeypair -keystore keystore/herdrdroid-sideload.jks \
    -alias "$(prop keyAlias)" -keyalg RSA -keysize 3072 -validity 10000 \
    -storepass:env SP -keypass:env KP -dname "CN=HerdrDroid sideload" >/dev/null 2>&1
fi
[ -f gradlew ] || "$G" wrapper --gradle-version 8.9 -q
echo "sdk.dir=$ANDROID_HOME" > local.properties
"$G" --no-daemon --console=plain -q testReleaseUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk /w/out/HerdrDroid.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs /w/out/HerdrDroid.apk | head -3
chown -R "$HOST_UID:$HOST_GID" /w/src /w/out /w/.cache
INNER
# HD_DOCKER_ARGS adds docker run options, for example a network and HD_WS_* variables so the
# SSH-over-WebSocket end-to-end test can reach a test server (it is skipped without them).
# shellcheck disable=SC2086
sudo -n docker run --rm --name herdrdroid-build ${HD_DOCKER_ARGS:-} \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  -v "$W":/w eclipse-temurin:17-jdk bash /w/.cache/inner.sh
