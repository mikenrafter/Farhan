#!/usr/bin/env bash
# Run this script from inside 'nix develop' to bootstrap the test project.
set -euo pipefail

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "ERROR: \$ANDROID_HOME is not set. Run this script from inside 'nix develop'."
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PARENT_DIR="$(dirname "$SCRIPT_DIR")"

# Point Gradle at the Nix-managed SDK.
echo "sdk.dir=$ANDROID_HOME" > "$SCRIPT_DIR/local.properties"
echo "✓ local.properties written (sdk.dir=$ANDROID_HOME)"

# Reuse the parent project's Gradle wrapper JAR — same Gradle version, no re-download.
cp -f "$PARENT_DIR/gradlew" "$SCRIPT_DIR/gradlew"
chmod +x "$SCRIPT_DIR/gradlew"
mkdir -p "$SCRIPT_DIR/gradle/wrapper"
cp -f "$PARENT_DIR/gradle/wrapper/gradle-wrapper.jar" \
       "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"
echo "✓ Gradle wrapper bootstrapped from parent project."

echo ""
echo "Next: ensure an emulator is running, then run ./install-all.sh"
echo ""
echo "  # Create AVD (once):"
echo "  avdmanager create avd --name farhan_test \\"
echo "    --package 'system-images;android-35;default;x86_64' --device pixel_6"
echo ""
echo "  # Start emulator:"
echo "  emulator @farhan_test -no-snapshot-load"
echo ""
echo "  # Install Farhan (from parent project):"
echo "  cd .. && ./gradlew assembleDebug && \\"
echo "    adb install -r app/build/outputs/apk/debug/app-debug.apk && cd testing"
echo ""
echo "  # Then grant notification listener: Settings → Notifications → Notification access → Farhan → Allow"
