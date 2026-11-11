#!/usr/bin/env bash
# Builds the signed HerdrDroid APK in a throwaway container.
# Usage: bash build-in-docker.sh <work-folder>
#   <work-folder>/src   the source (this folder's contents)
#   <work-folder>/.cache   SDK and Gradle caches (reused between builds; delete to clean)
#   <work-folder>/out   the APK
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
[ -f keystore/herdrdroid-sideload.jks ] || keytool -genkeypair -keystore keystore/herdrdroid-sideload.jks \
  -alias herdrdroid -keyalg RSA -keysize 3072 -validity 10000 \
  -storepass herdrdroid -keypass herdrdroid -dname "CN=HerdrDroid sideload" >/dev/null 2>&1
[ -f gradlew ] || "$G" wrapper --gradle-version 8.9 -q
echo "sdk.dir=$ANDROID_HOME" > local.properties
"$G" --no-daemon --console=plain -q assembleRelease
cp app/build/outputs/apk/release/app-release.apk /w/out/HerdrDroid.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs /w/out/HerdrDroid.apk | head -3
chown -R "$HOST_UID:$HOST_GID" /w/src /w/out /w/.cache
INNER
sudo -n docker run --rm --name herdrdroid-build \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  -v "$W":/w eclipse-temurin:17-jdk bash /w/.cache/inner.sh
