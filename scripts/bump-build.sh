#!/usr/bin/env bash
# Owner builds: raise the build number (Android versionCode) by one and print what to pass to Gradle.
#
#   scripts/bump-build.sh          bump gradle/libs.versions.toml, print the number and the build tag
#   scripts/bump-build.sh --show   print them without bumping
#
# Run it once before each build the owner will install, then build with the printed -PbuildTag. Afterwards check
# the APK with scripts/apk-version.sh. Never reuse a number for different contents.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOML="$ROOT/gradle/libs.versions.toml"
current="$(sed -n 's/^app-versionCode = "\([0-9]*\)"$/\1/p' "$TOML")"
[ -n "$current" ] || { echo "app-versionCode not found in $TOML" >&2; exit 1; }

if [ "${1:-}" = "--show" ]; then
  number="$current"
else
  number=$((current + 1))
  sed -i "s/^app-versionCode = \"[0-9]*\"$/app-versionCode = \"$number\"/" "$TOML"
fi

tag=""
if git -C "$ROOT" rev-parse --short=7 HEAD >/dev/null 2>&1; then
  tag="+g$(git -C "$ROOT" rev-parse --short=7 HEAD)"
  [ -z "$(git -C "$ROOT" status --porcelain -- . ':!gradle/libs.versions.toml')" ] || tag="$tag-dirty"
fi
echo "versionCode=$number"
echo "buildTag=$tag"
echo "gradle: -PbuildTag=$tag"
