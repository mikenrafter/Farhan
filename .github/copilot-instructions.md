# Farhan

Android app (Kotlin, Jetpack Compose). All tools (JDK 21, Android SDK 35, Gradle, adb) are provided by the Nix dev shell — never assume they are on PATH.

## Running commands

Prefix every build/test/install command with `nix develop -c`:

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
nix develop -c make build                                                          # build all debug APKs
nix develop -c ./gradlew :notification_filter:notification_filter_domain:test      # run domain unit tests
nix develop -c make install                                                        # install primary app on connected device
nix develop -c make install-all                                                    # install primary app + all demo apps
nix develop -c make install/emulator                                               # install all on emulator (starts one if needed)
```

## Module structure

- `app/` — application entry point
- `notification_filter/` — core feature (`_data`, `_domain`, `_presentation` modules)
- `testing/` — standalone demo apps (test-messenger, test-social, test-calendar, test-storm)
- `core/`, `core_ui/` — shared utilities and theming
