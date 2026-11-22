# Farhan — Feature Specification

## Overview

Farhan is an open-source Android productivity app designed to reduce mindless phone use. It sends no data off-device. Its one internet permission is used only for a loopback connection to the phone's own wireless-debugging service, and only if the user chooses to allow special access. The app bundles five independent behaviour-modification features — Notification Filter, Infinite Scroll Blocker, Screen Grayscale, App Usage Overview, and Delayed Launch — plus a custom Launcher (present in code but currently hidden from the main navigation). A global main switch pauses all active features simultaneously. The first time the app is installed, a two-step first-run flow (welcome carousel then theme selection) is shown before the main screen.

---

## Feature: Notification Filter

### Observable Behaviour

- When enabled, the feature silently intercepts incoming notifications from all apps (except those on the exceptions list) before they reach the user and stores them internally.
- The user views stored notifications on a dedicated screen (titled "Notifications") reached from the main screen card.
- Each stored notification is displayed as a card showing enough context to identify the sending app, the title, and the content.
- Tapping a notification card opens the originating app and/or surfaces the notification in the system-standard way.
- Swiping a notification card to the left reveals a red background with a delete icon and removes that notification from the list; the swipe direction is always left-to-right visually (regardless of device locale/RTL setting — a layout-direction override corrects an upstream platform bug in RTL locales).
- A persistent "Clear All" button sits pinned at the bottom of the list; tapping it removes every stored notification at once.
- When the list is empty, a centred "No notifications" message is displayed.
- A context menu on each notification card offers two inline actions: "Exclude this app" and "App info". Selecting "Exclude this app" adds the app to the exceptions list immediately and shows a short toast confirmation.
- Settings are reached from the gear icon in the top-right corner of the notifications screen (visible only when the notification-listener permission is granted).
- Settings contain:
  - A toggle to enable/disable the notification filter service.
  - A link to the Exceptions screen.
  - A "Notify me" toggle. When turned on, the user picks a clock time via a time-picker dialog; at that time a system alarm fires and delivers a notification reminding the user to check their stored notifications. Disabling "Notify me" cancels the alarm.
  - When "Notify me" is enabled and the alarm fires, the delivered notification deep-links directly to the Notifications screen.
- The first time the Notifications screen is opened (after permission is granted), an interactive feature tour (showcase) highlights the settings icon and explains its purpose.
- The first time the Settings screen is opened, a warning dialog explains the implications of holding notifications, with "I understand" and "Tell me more" options. "Tell me more" replays the settings showcase tour. The user can tick "Do not show again" before dismissing.
- The Exceptions screen lists all installed apps and lets the user toggle each one in or out of the exceptions list. An app on the exceptions list passes its notifications through normally (they are never filtered). The screen supports text search, a "Show system apps" filter, and a "Show exceptions only" filter.

### Invariants & Constraints

- The feature requires the `BIND_NOTIFICATION_LISTENER_SERVICE` permission; the screen shows a blocked/grant-access state when this permission is absent.
- Notifications from apps on the exceptions list are never intercepted.
- When the notification filter service is toggled off, no further interception happens, but previously stored notifications remain visible until manually dismissed.
- The "Notify me" alarm requires `POST_NOTIFICATIONS` (Android 13+) and `SCHEDULE_EXACT_ALARM` permissions; if either is declined the user is shown a rationale snackbar and permission dialogs.
- Swipe-to-dismiss only works in the left direction; swiping right has no effect.
- The main switch (global pause) prevents the filter service from running when off.

### Permissions Required

- `BIND_NOTIFICATION_LISTENER_SERVICE` (special, granted via Notification Access settings)
- `POST_NOTIFICATIONS` (runtime, Android 13+, required for the "Notify me" reminder)
- `SCHEDULE_EXACT_ALARM` (runtime, Android 12+, required for the "Notify me" alarm)

---

## Feature: Infinite Scroll Blocker

### Observable Behaviour

- When enabled, the feature monitors scroll events inside every app except those on the exceptions list.
- Detection logic: the feature tracks, per scroll view, how many times the total item count of the view has grown. When a single scroll view grows at least three times without a 3-minute gap between updates, it is classified as an infinite scroll view. When the cumulative time spent scrolling within that view reaches the configured timeout duration, a blocking overlay is shown.
- The blocking overlay is a full-screen bottom-sheet-style dialog drawn on top of all other apps via the system overlay layer. It contains:
  - A red "Hey" heading.
  - The configured or randomly-selected reminder message.
  - A countdown timer (visible as a number) if a countdown is configured; the dismiss action is disabled until the countdown expires.
  - A prominent "Take me out of here" button that dismisses the overlay and sends the user to the device home screen.
  - A "Continue scrolling" label that, once the countdown has expired, dismisses the overlay and lets the user resume.
  - A "Exclude this app (press and hold)" label; a long-press immediately adds the current app to the exceptions list, dismisses the overlay, and shows a toast confirmation.
- Settings are accessed from the Infinite Scroll Blocker screen:
  - A main enable/disable toggle.
  - A link to the Exceptions screen.
  - A tappable timeout-duration field; tapping opens a number-picker dialog (range 1–60 minutes).
  - A message selector field; tapping opens a dialog where the user chooses a message from a dropdown list or selects "Random" (shuffle icon) to display a different message each time. An edit mode within the same dialog lets the user add new messages (text field + add button) or delete existing ones (delete button per item). A long-press on "Reset messages" restores the built-in defaults.
  - A countdown toggle; enabling it opens a number-picker dialog (range 1–30 seconds) to set how long the dismiss button stays locked after the overlay appears.
- The first time the Infinite Scroll Blocker screen is reached, a per-feature onboarding screen is shown (with a "Next" button) before the settings screen.
- The Exceptions screen works identically to the Notification Filter exceptions screen: full app list, text search, system-apps toggle, exceptions-only filter, checkbox per app.

### Invariants & Constraints

- The feature requires the "Appear on top" (display-over-other-apps) permission and the Accessibility Service to be running; if either is absent, the settings screen shows a blocked/grant-access state with an animated how-to guide.
- A scroll view whose item-count stops growing for more than 3 minutes is no longer considered active; the internal scroll state is cleared.
- The overlay is dismissed automatically when the user taps "Take me out of here" or "Continue scrolling" (the latter only after the countdown, if any, has expired).
- Apps on the exceptions list never trigger the overlay.
- The main switch (global pause) suppresses overlay display when off.

### Permissions Required

- `SYSTEM_ALERT_WINDOW` ("Appear on top", special permission)
- `BIND_ACCESSIBILITY_SERVICE` (special, granted via Accessibility settings)

---

## Feature: Screen Grayscale

### Observable Behaviour

- When enabled, the feature makes the entire device display grayscale whenever the foreground app is on the whitelist; it restores full colour when a non-whitelisted app comes to the foreground.
- The Grayscale settings screen contains:
  - A toggle labelled "Enable grayscale for whitelisted apps" that activates or deactivates the feature.
  - A link to the Whitelisted Apps screen.
- Whitelisted Apps screen: same searchable, filterable full-app-list pattern as the exceptions screens for other features; toggling an app on the whitelist means grayscale will activate for that app.
- The first time the Grayscale screen is reached, a per-feature onboarding screen is shown before the settings screen.
- On rooted devices, the `WRITE_SECURE_SETTINGS` permission can be granted automatically via a button in the setup screen. On non-rooted devices, the screen shows instructions and links to a web page explaining the manual ADB grant method.

### Invariants & Constraints

- Grayscale is implemented by writing to secure system settings (`accessibility_display_daltonizer_enabled` and `accessibility_display_daltonizer`); this requires the `WRITE_SECURE_SETTINGS` permission, which cannot be granted normally — it requires ADB or root.
- If `WRITE_SECURE_SETTINGS` is not granted, the settings controls are not shown; instead a setup screen guides the user through granting it.
- If the Accessibility Service is not running, the settings controls are also blocked and the user is prompted to enable it (with an animated how-to guide).
- The Accessibility Service monitors foreground-app changes to switch grayscale on/off; keyboard/input-method app events are ignored to avoid flickering when the software keyboard appears.
- Grayscale state persists as long as the Accessibility Service is running; if the service is killed, the OS display setting retains whatever state it was last left in until the service restarts.
- The main switch (global pause) does not independently disable grayscale once it is active at the OS level; grayscale is tied to the Accessibility Service running.

### Permissions Required

- `WRITE_SECURE_SETTINGS` (granted via ADB or root)
- `BIND_ACCESSIBILITY_SERVICE`

---

## Feature: App Usage Overview

### Observable Behaviour

- The Usage Overview screen shows a chronological list of apps used on a given day or over a date range, with each entry showing the app name, icon, and total time spent in that app.
- A header above the list shows aggregate information (e.g. total screen time for the selected period).
- Navigation between days: left/right arrow buttons step through days one at a time. The right arrow is disabled when today's date is selected.
- Tapping the date label opens a bottom sheet with two options: "Select a day" (single-day calendar picker) or "Select a range" (date-range picker). Only dates for which data exists are selectable; future dates are not selectable.
- Date-range mode: when a range is active, the header and list reflect the aggregate of all days in that range; the day-step arrows are hidden.
- The overflow menu (top-right) provides:
  - "Delete cache for day" — deletes the locally cached data for the currently viewed single day (not available in range mode). A confirmation dialog is shown before deletion.
  - "Usage settings" — navigates to the usage settings screen.
- When the Usage Stats permission is not granted, the full screen shows a grant-access state with an animated how-to guide and a button to open the system Usage Access settings.
- **Home-screen widget**: a widget titled "Usage Overview" can be added to the home screen; it displays a summary of today's app usage (or the cached daily data). The widget can be configured from a configuration activity when first added. Deep links from the widget open the Usage Overview screen filtered to a specific date or date range.
- **Background caching**: when caching is enabled (configured in Usage Settings), the app periodically collects and stores daily usage data in the background so historical data is available for range queries.

### Invariants & Constraints

- The `PACKAGE_USAGE_STATS` permission is required; without it, no usage data is displayed.
- The date-range picker limits selectable years to 2022 through the current year.
- The "Delete cache for day" option is only available in single-day mode and only when caching is enabled.
- Usage data is read from the Android system's UsageStatsManager; no data is computed or stored outside the device.
- The widget receives updates via a standard `AppWidgetProvider` broadcast receiver.

### Permissions Required

- `PACKAGE_USAGE_STATS` (special, granted via Usage Access settings)
- `QUERY_ALL_PACKAGES` (normal)

---

## Feature: Launcher

### Observable Behaviour

- Farhan can be set as the default home screen launcher; the system then shows Farhan's launcher when the user presses the Home button.
- The launcher home screen displays a live clock (12-hour format with seconds) and two quick-launch icon buttons in the bottom corners: a phone/dialler button and a camera button. Tapping the clock opens the default alarm app.
- The app list is revealed by swiping right from the home screen. The swipe animates via a MotionLayout transition. Pressing the hardware Home button while the app list is open slides it back.
- The app list is a vertically scrollable list of all installed apps, sorted alphabetically and grouped by first letter. A sidebar on the right side of the list shows the initial letters of each group; tapping or dragging on the sidebar scrolls the list to the corresponding letter section.
- The app list has a search bar at the top; typing filters the visible apps in real time. Clearing the search restores the full list.
- Long-pressing an app in the list opens the system app-info screen for that app.
- Short-tapping an app in the list either launches it immediately (if no per-app limit applies) or shows a time-limit selection dialog.
- **Time limits**: a Time Limiter feature (accessed from Launcher Settings → App Time Limiter) allows configuring session limits per app. When a limited app is tapped in the launcher, a dialog offers preset limits (5 min, 10 min, 15 min); selecting one launches the app and starts an internal timer. When the timer expires, the user is blocked from that app.
- A whitelist for the Time Limiter exempts certain apps from limit checks.
- **Delayed Launch** (also accessible from the main screen, independent of whether the Launcher is the default): when enabled, the Accessibility Service intercepts each new full-screen app launch and, if the launched app is in the Delayed Launch whitelist, shows a full-screen interstitial ("Delayed Launch") before continuing. The delay duration is configurable in seconds. A message (chosen from a user-managed list or randomly from that list) is displayed during the delay.
- Delayed Launch settings include: enable/disable toggle, whitelist management (searchable app list), delay duration in seconds, and message management (add/delete/reset custom messages, random selection option).

### Invariants & Constraints

- Enabling/disabling the launcher redirects the user to the system Default Apps settings; the toggle state reflects whether Farhan is currently the default home app.
- The launcher home screen is separate from the main Farhan settings activity; it is registered with `android.intent.category.HOME` and runs in its own activity with its own theme.
- The launcher app list refreshes on every resume to reflect newly installed or uninstalled apps.
- Delayed Launch requires the Accessibility Service; the settings screen shows a blocked state and guidance when the service is not running.
- An app in the Delayed Launch whitelist will show the interstitial every time it is brought to the foreground as a full-screen window (provided more than 5 seconds have elapsed since the same app was last launched).
- Apps belonging to enabled input-method (keyboard) packages are excluded from Delayed Launch interception to avoid interrupting typing.
- The Launcher feature card is present in the codebase but is currently commented out in the main navigation; the Delayed Launch settings card is the visible entry point for launcher-related friction features.

### Permissions Required

- `BIND_ACCESSIBILITY_SERVICE` (for Delayed Launch)
- `QUERY_ALL_PACKAGES`

---

## Feature: Onboarding & Global Settings

### Observable Behaviour

- **First-run onboarding**: on first launch, the app shows a three-page horizontal carousel (swipe or "Next" button to advance; "Skip" button available on pages 1 and 2; "Get started" button on page 3). The three pages cover: (1) nothing leaves the phone, and the one internet permission is only for the phone's own debugging service, (2) open-source nature, (3) the app's purpose.
- Completing or skipping onboarding navigates to the theme-selection screen.
- **Theme selection screen**: presented once after onboarding (or whenever the saved theme preference is unknown). The user selects:
  - **Display mode**: Dark, Follow System, or Light — shown as an animated toggle row with three icon buttons.
  - **Colour palette**: Classic, Vibrant, or Dynamic (Dynamic is only shown on Android 12+). Each option is shown as a selectable card with four colour swatches (primary, primary-container, surface, tertiary). The selected card expands to show a description.
  - Tapping "OK" saves both choices and proceeds to the main screen.
- **Main screen**: shows the app name as a title bar and a scrollable column of feature cards. Above the cards a random tip from a curated list is displayed.
- **Global main switch**: a card at the top of the feature list with a toggle. When on, it reads "Farhan is ON — tap to pause all Farhan features". When off, it reads "Farhan is OFF — tap to resume". All feature cards (except Usage Overview, which is always accessible) are disabled when the main switch is off; tapping a disabled card shows a snackbar prompting the user to turn the main switch on.
- Feature cards navigate to: Usage Overview, Notification Filter, Grayscale, Infinite Scroll Blocker, Delayed Launch.
- Each feature card shows the feature name and its current status ("Enabled" or "Disabled").
- **Overflow menu** (top-right of main screen): "About app", "Appearance" (opens dark/light/system mode dialog), "Theme colors" (opens palette selection dialog).
- **Appearance and theme dialogs** can be accessed at any time from the main screen menu; choices apply immediately and are persisted across restarts.
- **About screen**: shows the app version name and version code, reachable from the main screen overflow menu.
- **Feature-level onboarding**: each of Infinite Scroll Blocker, Grayscale, and Notification Filter shows its own per-feature onboarding screen the first time that feature is navigated to. After the onboarding is completed, it is never shown again for that feature.
- **Database migration dialog**: if the app detects that an older database schema needs consolidation, a non-dismissible dialog appears with "Start migrating" and "Close app" buttons. A progress indicator is shown while migration runs. Success or failure is reported via a snackbar.

### Invariants & Constraints

- Onboarding is shown exactly once; after completion the app always starts on the main screen.
- Theme-selection is shown once after onboarding (when the stored theme preference is "Unknown"); it can be revisited any time via the menu.
- The main switch state is persisted; the app restores the last known state on next launch.
- Usage Overview is always enabled regardless of the main switch.
- Theme changes take effect immediately without requiring an app restart.
- The three colour-palette modes are "Classic", "Vibrant", and "Dynamic" (Dynamic requires Android 12+).

### Permissions Required

- None specific to onboarding itself; each feature requests its own permissions on first access.

---

## Feature: Crash Reporting

### Observable Behaviour

- When the app crashes, a crash report is automatically captured in the background (no user interaction required at crash time).
- The crash report includes: app version name, app version code, Android version, device model, and the full stack trace.
- The report is saved as a plain-text file (`last_crash.log`) in the app's private internal storage.
- After the crash report is saved, a system notification is posted in a dedicated "Crash Reports" channel. The notification reads "App Crashed — Tap to view crash details". Tapping the notification opens the Crash Details screen.
- The Crash Details screen displays:
  - A "Crash Report" headline.
  - An instructional message reminding the user to check whether the issue already exists before filing.
  - A secondary message noting that if the crash is unknown, it can be ignored.
  - A read-only, scrollable text field containing the full crash log content.
  - A "Create GitHub issue" button.
  - A "Copy to clipboard" button — copies the crash log text and shows a "Copied to clipboard" toast.
  - A "Close" button that dismisses the screen.
- Tapping "Create GitHub issue" opens a confirmation dialog asking the user to confirm they have checked for existing issues. The dialog contains a checkbox labelled "I have checked that this issue does not already exist". The "Continue" button (which opens the GitHub new-issue URL) is enabled only when the checkbox is ticked.
- The GitHub issue URL is pre-filled with: a title derived from the exception type and the innermost app method where the crash occurred, a body containing a checklist template (reproducibility, steps to reproduce, additional context), and the full crash log in a code block. The label "crash" is pre-applied.
- The Crash Details screen always uses dark mode with the Classic colour palette, regardless of the user's theme preference.

### Invariants & Constraints

- Only the most recent crash is stored; each new crash overwrites `last_crash.log`.
- The crash report is collected entirely on-device; no data is sent to any remote server.
- The "Continue" button on the GitHub dialog is disabled until the duplicate-check checkbox is ticked.
- The Crash Details screen is not exported and cannot be opened from outside the app.

### Permissions Required

- `POST_NOTIFICATIONS` (to show the crash notification on Android 13+)

---

## Feature: Accessibility Service (shared backbone)

### Observable Behaviour

- A single Accessibility Service runs in the background and serves as the backbone for three features: Infinite Scroll Blocker, Screen Grayscale, and Delayed Launch.
- The service is registered with a `BIND_ACCESSIBILITY_SERVICE` permission and is configured via an XML metadata file (`accessibility_service_config`).
- When the service starts, it reads the list of currently enabled input-method (keyboard) packages so that events from those packages can be distinguished from regular app events.
- The service reacts to two categories of Android accessibility events:
  1. **Scroll events** (`TYPE_VIEW_SCROLLED`) — used exclusively by the Infinite Scroll Blocker. Events from the exceptions list are ignored. For all other events, the service tracks per-scroll-view growth counts and elapsed time (see Infinite Scroll Blocker section).
  2. **Window-state change events** (`TYPE_WINDOW_STATE_CHANGED`) — used by Grayscale and Delayed Launch. Full-screen transitions drive both features.
- For **Grayscale**: whenever a full-screen window appears (or a content-type-undefined event fires for a non-keyboard package), the service checks whether the foreground app is in the grayscale whitelist. If yes, it enables grayscale (Daltonizer mode 0); if the window is full-screen and the app is not whitelisted, it disables grayscale.
- For **Delayed Launch**: whenever a new full-screen window appears from a package that differs from the last-launched package, is not the Farhan app itself, is not a keyboard package, and more than 5 seconds have elapsed since the last launch, the service checks whether the package is in the Delayed Launch whitelist. If it is, a Delayed Launch interstitial activity is started.
- The overlay for the Infinite Scroll Blocker is drawn directly by the service using a `WindowManager.addView` call with `TYPE_APPLICATION_OVERLAY` (or `TYPE_PHONE` on older Android versions).
- When the service is unbound or destroyed, all tracked scroll-view state and the soft-input package list are cleared.

### Invariants & Constraints

- Only one Accessibility Service instance exists for the entire app; all three consuming features share it.
- The service reads feature-enabled states synchronously at event time to decide whether to act.
- Grayscale and Delayed Launch both ignore events originating from packages belonging to active input-method services.
- The Delayed Launch 5-second de-bounce prevents the same app from triggering the interstitial repeatedly when, for example, the user switches between recent apps.
- Scroll-view tracking state is keyed by a hash derived from the scroll view's class name, package name, view resource ID, and on-screen bounds; different scroll views within the same app are tracked independently.
- If the Accessibility Service is stopped by the system or the user, all three features cease to function until it is re-enabled.

### Permissions Required

- `BIND_ACCESSIBILITY_SERVICE` (special, granted via Android Accessibility settings)
- `SYSTEM_ALERT_WINDOW` (for the Infinite Scroll Blocker overlay)
- `WRITE_SECURE_SETTINGS` (for Grayscale daltonizer writes)
