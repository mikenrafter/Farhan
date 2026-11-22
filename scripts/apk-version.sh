#!/usr/bin/env bash
# Print package, versionCode, versionName, size and sha256 of a built APK, read from the file itself.
# Usage: scripts/apk-version.sh path/to/app.apk
set -euo pipefail
apk="${1:?usage: apk-version.sh app.apk}"
aapt2="$(command -v aapt2 || find /nix/store -path '*build-tools*' -name aapt2 2>/dev/null | head -1)"
[ -n "$aapt2" ] || { echo "aapt2 not found" >&2; exit 1; }
"$aapt2" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/package=\1 versionCode=\2 versionName=\3/p"
echo "size=$(stat -c %s "$apk") sha256=$(sha256sum "$apk" | cut -d' ' -f1)"
