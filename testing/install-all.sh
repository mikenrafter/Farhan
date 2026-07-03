#!/usr/bin/env bash
# Builds all four test APKs and installs them on the running emulator.
# Run from inside 'nix develop' with an emulator already started.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if ! adb devices 2>/dev/null | grep -qE "emulator|device"; then
    echo "WARNING: No device detected via adb. Proceeding anyway — install will fail if none is connected."
fi

if [ ! -f local.properties ]; then
    echo "ERROR: local.properties missing. Run ./setup.sh first."
    exit 1
fi

echo "Building all test APKs…"
./gradlew assembleDebug

echo ""
for module in test-messenger test-social test-calendar test-storm; do
    APK="$module/build/outputs/apk/debug/$module-debug.apk"
    echo "  → Installing $module"
    adb install -r "$APK"
done

echo ""
echo "Done! All 4 test apps installed:"
echo "  • Test Messenger  (ly.com.tahaben.test.messenger)"
echo "  • Test Social     (ly.com.tahaben.test.social)"
echo "  • Test Calendar   (ly.com.tahaben.test.calendar)"
echo "  • Test Storm      (ly.com.tahaben.test.storm)"
