.PHONY: install/emulator install-emulator emulator

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
