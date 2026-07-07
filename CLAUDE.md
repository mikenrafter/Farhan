# Farhan

Android app (Kotlin, Jetpack Compose). Requires the Nix dev shell for all build commands.

## Nix

All tools (JDK 21, Android SDK 35, Gradle, adb) live in the Nix dev shell. Prefix every build/test command with `nix develop -c`:

```bash
nix develop -c ./gradlew <task>
nix develop -c make build
```

Or enter the shell once and run commands directly:

```bash
nix develop
./gradlew assembleDebug
```

## Common commands

```bash
# Build
nix develop -c make build

# Run unit tests for a module
nix develop -c ./gradlew :notification_filter:notification_filter_domain:test

# Install primary app on a connected device
nix develop -c make install

# Install primary app + all demo apps on a connected device
nix develop -c make install-all

# Install all on the emulator (starts one if needed)
nix develop -c make install/emulator

# Update the Nix lock (e.g. to pull a newer SDK)
nix flake update
```

## Module structure

- `app/` — application entry point
- `notification_filter/` — core feature, split into `_data`, `_domain`, `_presentation` modules
- `testing/` — standalone demo apps for manual testing (test-messenger, test-social, test-calendar, test-storm)
- `core/`, `core_ui/` — shared utilities and theming
