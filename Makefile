.PHONY: build install install-app install-demo-messenger install-demo-social \
        install-demo-calendar install-demo-storm install-demo \
        install/emulator install-emulator emulator

# Build all debug APKs
build:
	./gradlew assembleDebug
	( cd testing && ./gradlew assembleDebug )

# Install Farhan and all demo apps
install: install-app install-demo-messenger install-demo-social install-demo-calendar install-demo-storm

install-app:
	./gradlew :app:assembleDebug
	adb install -r app/build/outputs/apk/debug/app-debug.apk

install-demo-messenger:
	( cd testing && ./gradlew :test-messenger:assembleDebug )
	adb install -r testing/test-messenger/build/outputs/apk/debug/test-messenger-debug.apk

install-demo-social:
	( cd testing && ./gradlew :test-social:assembleDebug )
	adb install -r testing/test-social/build/outputs/apk/debug/test-social-debug.apk

install-demo-calendar:
	( cd testing && ./gradlew :test-calendar:assembleDebug )
	adb install -r testing/test-calendar/build/outputs/apk/debug/test-calendar-debug.apk

install-demo-storm:
	( cd testing && ./gradlew :test-storm:assembleDebug )
	adb install -r testing/test-storm/build/outputs/apk/debug/test-storm-debug.apk

install-demo: install-demo-messenger install-demo-social install-demo-calendar install-demo-storm

# Build, boot (or reuse) the Android emulator, then install Farhan + demo apps.
# Requires: nix develop shell (provides ANDROID_SDK_ROOT, adb, emulator, avdmanager).
# Must be launched from a desktop terminal (Wayland/X11). On Wayland, the Nix
# emulator requires QT_QPA_PLATFORM=xcb (set automatically by the script).
#
# Usage:
#   nix develop
#   make install/emulator
#
# Aliases:
#   make install-emulator
#   make emulator
#
# Optional env vars (forwarded to scripts/emulator-install.sh):
#   FARHAN_AVD_NAME                         default: farhan_test
#   FARHAN_BOOT_SETTLE_SECONDS              default: 10 (only when the script starts the emulator)
#   FARHAN_EMULATOR_START_TIMEOUT_SECONDS   default: 300

# Preserve the caller's graphical session when entering nix develop.
SESSION_ENV = \
  DISPLAY="$$DISPLAY" \
  WAYLAND_DISPLAY="$$WAYLAND_DISPLAY" \
  XDG_RUNTIME_DIR="$$XDG_RUNTIME_DIR" \
  XDG_SESSION_TYPE="$$XDG_SESSION_TYPE" \
  XDG_CURRENT_DESKTOP="$$XDG_CURRENT_DESKTOP" \
  QT_QPA_PLATFORM="$$QT_QPA_PLATFORM"

ifeq ($(IN_NIX_SHELL),)
  RUN = $(SESSION_ENV) nix develop -c
else
  RUN =
endif

install/emulator install-emulator emulator:
	@chmod +x scripts/emulator-install.sh
	$(RUN) bash scripts/emulator-install.sh
