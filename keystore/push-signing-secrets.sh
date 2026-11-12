#!/usr/bin/env bash
# Copies HerdrDroid's sideload signing key to the GitHub repository's Actions secrets, so CI signs
# the APK with the same key as local builds (and its APKs install over the app).
#
# Run it from anywhere; it reads the key from the folder it lives in (keystore/, which git ignores
# except for this script). Needs the gh CLI signed in to an account that can administer
# BillJr99/HerdrDroid. Nothing is printed except the secret names.
#
#   bash keystore/push-signing-secrets.sh            # BillJr99/HerdrDroid
#   bash keystore/push-signing-secrets.sh owner/repo # another repository
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="${1:-BillJr99/HerdrDroid}"
[ -f "$here/herdrdroid-sideload.jks" ] || { echo "No herdrdroid-sideload.jks in $here" >&2; exit 1; }
[ -f "$here/signing.properties" ] || { echo "No signing.properties in $here" >&2; exit 1; }
base64 -w0 "$here/herdrdroid-sideload.jks" | gh secret set KEYSTORE_B64 -R "$repo" >/dev/null
gh secret set SIGNING_PROPERTIES -R "$repo" < "$here/signing.properties" >/dev/null
gh secret list -R "$repo" | cut -f1
