# Farhan — Performance & Technical Considerations

This document captures architectural decisions, threading strategies, and known constraints as they exist in the codebase. It covers only what the code explicitly shows.

---

## Background Work Architecture

All background work is managed via WorkManager. Four worker types are registered.

### CacheWorker

**File:** `usage_overview/usage_overview_data/src/main/java/ly/com/tahaben/usage_overview_data/local/CacheWorker.kt`

- **Schedule:** `PeriodicWorkRequestBuilder<CacheWorker>(Duration.ofHours(12))` with an initial delay calculated as minutes remaining until the next midnight plus one. Runs approximately at midnight and noon after the first trigger.
- **Policy:** `ExistingPeriodicWorkPolicy.KEEP` — does not replace or restart an already-queued job.
- **What it does:** Checks whether yesterday's usage data is already fully cached (`usageRepository.isDayDataFullyUpdated(yesterday)`). If it is, returns `Result.success()` immediately. Otherwise, it caches usage events for yesterday and cancels the progress notification on completion.
- **Notification:** Silent, ongoing, with an indeterminate progress bar (notification ID 102). Shown before the cache operation begins. Cancelled on both success and failure.
- **Failure handling:** Returns `Result.failure()` with no retry. WorkManager will retry according to its default backoff policy.
- **No `setExpedited()`:** Runs as a regular background worker, subject to battery optimization deferrals.

### ReportsWorker

**File:** `usage_overview/usage_overview_data/src/main/java/ly/com/tahaben/usage_overview_data/local/ReportsWorker.kt`

- **Schedule (weekly):** `PeriodicWorkRequestBuilder<ReportsWorker>(Duration.ofDays(7))`, initial delay set to the start of the next first-day-of-week as determined by `WeekFields.of(Locale.getDefault()).firstDayOfWeek`. The report period is locale-aware.
- **Schedule (monthly):** `PeriodicWorkRequestBuilder<ReportsWorker>(Duration.ofDays(30))`, initial delay set to the first day of next month at midnight.
- **Policy:** `ExistingPeriodicWorkPolicy.KEEP` for both.
- **Input data:** A `REPORT_TYPE` string (`"weekly_usage_report"` or `"monthly_usage_report"`) is passed via `WorkerKeys.REPORT_TYPE`. The worker dispatches on this value in `doWork()`.
- **What it does:** Iterates day-by-day over the last completed period and the period before it, reads usage events from the database, filters and calculates durations, sums total time, computes the delta, and posts a user-visible notification with a deep-link `PendingIntent` to the usage screen.
- **Notification:** Not silent, auto-cancels, includes a content intent. The deep-link URI is `app://<packageName>/usage/<startDate>/<endDate>`.
- **Failure handling:** Returns `Result.failure()`. No retry.

### LaunchAttemptCleanupWorker

**File:** `launcher/launcher_data/src/main/java/ly/com/tahaben/launcher_data/local/db/LaunchAttemptCleanupWorker.kt`

- **Schedule:** `PeriodicWorkRequestBuilder<LaunchAttemptCleanupWorker>(24.hours.toJavaDuration())`, no initial delay override.
- **Policy:** `ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE` — unlike the other workers this will cancel and reschedule an already-queued job. This matches the intent of always keeping a fresh 24-hour window.
- **What it does:** Deletes all `LaunchAttemptEntity` rows with a timestamp earlier than `System.currentTimeMillis() - 24 hours`.
- **No notification.** Silent background pruning.
- **Failure handling:** Returns `Result.failure()` with no retry.

### WidgetWorker

**File:** `usage_overview/usage_overview_presentation/src/main/java/ly/com/tahaben/usage_overview_presentation/widget/WidgetWorker.kt`

- **Schedule (periodic):** `PeriodicWorkRequestBuilder<WidgetWorker>(duration.toJavaDuration())` where `duration` is user-configured in `WidgetConfigActivity` as one of: 15, 30, 60, 120, 180, 360, or 720 minutes. Policy is `ExistingPeriodicWorkPolicy.UPDATE` (replaces any existing periodic request on config save).
- **Schedule (one-time):** `OneTimeWorkRequestBuilder<WidgetWorker>()` enqueued with `ExistingWorkPolicy.REPLACE` when the user taps the refresh icon on the widget.
- **Foreground service:** Calls `setForeground(createForegroundInfo(...))` immediately on start. On API 29+ uses `FOREGROUND_SERVICE_TYPE_DATA_SYNC`. Shows a silent, ongoing notification (ID 104) for the duration of the work.
- **What it does:** Reads today's usage from the system (`getUsageDataForDate`), reads yesterday's usage from the database (`getUsageEventsFromDb`), applies configured filters (ignore launcher / ignore Farhan), calculates durations, stores totals to DataStore preferences, then calls `UsageWidget.updateAll(context)`.
- **Today vs yesterday data source distinction:** Today's data is read live from the usage stats API; yesterday's is read from the local Room cache.
- **Cancellation on widget removal:** `UsageWidget.onDelete()` cancels `WorkerKeys.WIDGET_UPDATE` only when `GlanceAppWidgetManager.getGlanceIds(UsageWidget::class.java).isEmpty()` — i.e., the last widget instance was removed.

---

## Accessibility Service: Scroll Detection Heuristic

**File:** `app/src/main/java/ly/com/tahaben/farhan/service/AccessibilityService.kt`

### State cache

`recentScrollViews: HashMap<Int, ScrollViewInfo>` tracks in-progress scroll sessions. The key is a composite hash:

```
event.className.hashCode() +
event.packageName.hashCode() +
(event.source!!.viewIdResourceName?.hashCode() ?: 1) +
event.source!!.getBoundsInScreen(Rect()).hashCode()
```

This produces an integer identifier intended to be unique per scroll view instance within a given app. When `viewIdResourceName` is null (unlabelled views), the constant `1` is used.

### ScrollViewInfo fields

```kotlin
data class ScrollViewInfo(
    var maxY: Int,
    var addedAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var timesGrown: Int = 0
)
```

`maxY` is the largest `event.itemCount` seen for this scroll view. `timesGrown` counts how many times `maxY` has been surpassed (the list loaded more content). `addedAt` is when the entry was first seen; `updatedAt` is the last time the list grew.

### Detection logic

On each `TYPE_VIEW_SCROLLED` event (after the package is confirmed not to be in the exceptions list):

1. `maxY` is taken from `event.itemCount`. If `itemCount <= 0` or `event.source` is null, the event is ignored.
2. If no entry exists for this scroll view ID, a new `ScrollViewInfo(maxY)` is inserted and the function returns.
3. If an entry exists:
   - **Staleness check:** `timeSinceLastUpdate = (now - scrollViewInfo.updatedAt).inWholeMinutes`. If greater than 3, the **entire map** is cleared and the function returns. The 3-minute threshold is the staleness guard — it prevents a view that was scrolled long ago from being matched to a new session.
   - **Growth check:** If `maxY > scrollViewInfo.maxY`, `timesGrown` is incremented, `maxY` and `updatedAt` are updated.
   - **Trigger check:** If `timesGrown >= 3` (the list has grown at least 3 times) AND `scrollingTime >= infiniteScrollUseCases.getTimeOutDuration()` (the user has been in the session long enough), the blocking dialog is shown and the map is cleared.

### Map management

The map has no maximum size limit. It accumulates entries until a stale event clears it, or until `onUnbind()` / `onDestroy()` clear it explicitly. In a session where a user switches between many scrollable apps without triggering the staleness timeout, entries from all apps remain in memory.

Clearing is always total (`recentScrollViews.clear()`), not per-entry. This is a deliberate simplification: when any scroll view's tracking is reset (stale or triggered), all tracking state is reset.

---

## Coroutine & Threading Model

### AccessibilityService

**File:** `app/src/main/java/ly/com/tahaben/farhan/service/AccessibilityService.kt`

A manual `CoroutineScope(Dispatchers.IO + SupervisorJob())` is created at class level. It is started in `onCreate()` and cancelled in `onDestroy()`. The scope runs two persistent `collectLatest` collectors: one for `isDelayedLaunchEnabled`, one for the delayed-launch whitelist set. These keep in-memory booleans and sets up to date without reading from storage on every event.

`onAccessibilityEvent()` itself calls `runBlocking` twice on the main thread (the thread that delivers accessibility events):

```kotlin
val infiniteScrollBlockEnabled = runBlocking { infiniteScrollUseCases.isServiceEnabled() }
val grayscaleEnabled = runBlocking { grayscaleUseCases.isGrayscaleEnabled() }
```

These block the accessibility event thread on every event to perform synchronous preference reads. The approach avoids race conditions that would arise from reading async state inside a non-suspend callback, at the cost of blocking the delivery thread.

### MainActivity

**File:** `app/src/main/java/ly/com/tahaben/farhan/MainActivity.kt`

A manual `CoroutineScope(Dispatchers.IO + SupervisorJob())` is created as a local variable inside `onCreate()`. It is used only for the `DatabaseCombineHelper.combineDatabases()` call triggered by user confirmation. There is no explicit cancellation of this scope in `onDestroy()`.

One `runBlocking` call occurs before `setContent {}`:

```kotlin
runBlocking {
    shouldShowSelectThemeScreen = (onBoardingPref.loadThemeColors() == "Unknown")
}
```

Inside the `NavHost` composable lambda for `Routes.MAIN`, four additional `runBlocking` calls occur during composition:

```kotlin
val delayedLaunchEnabled = runBlocking { launcherPref.isDelayedLaunchEnabled().first() }
val notificationFilterEnabled = runBlocking { notificationFilterUseCases.checkIfNotificationServiceIsEnabled() }
val infiniteScrollBlockEnabled = runBlocking { infiniteScrollUseCases.isServiceEnabled() }
val grayscaleEnabled = runBlocking { grayscaleUseCases.isGrayscaleEnabled() }
```

These run on the composition thread each time the `Routes.MAIN` composable is entered (initial navigation to it). They are used to set the initial displayed state of feature toggles on the main screen.

### DelayedLaunchActivity

**File:** `launcher/launcher_presentation/src/main/java/ly/com/tahaben/launcher_presentation/wait/DelayedLaunchActivity.kt`

One `runBlocking` call in `onCreate()` before `setContent {}`:

```kotlin
val delayDuration = runBlocking { prefs.getDelayedLaunchDuration().first() }
```

Used to read the configured delay duration synchronously so it can be passed as a plain value to the composable.

### ViewModels

All ViewModels use `viewModelScope`. `MainScreenViewModel` drives the splash screen via `isLoading: MutableStateFlow<Boolean>`, which is set to `false` after `loadUiPreferencesOnce()` completes (a single `first()` collection from each of two DataStore flows).

### UsageWidget

**File:** `usage_overview/usage_overview_presentation/src/main/java/ly/com/tahaben/usage_overview_presentation/widget/UsageWidget.kt`

A `CoroutineScope(Dispatchers.Main + SupervisorJob())` is created inside `provideGlance()` each time Glance calls that method. It is used for the manual refresh flow triggered by the in-widget refresh button. There is no explicit `cancel()` call on this scope; it is held only by the closure passed to the refresh button's `clickable` action.

### DatabaseCombineHelper

**File:** `app/src/main/java/ly/com/tahaben/farhan/db/DatabaseCombineHelper.kt`

`combineDatabases()` is a `suspend` function that uses `runBlocking` inside Room's `runInTransaction {}` callback (which does not accept a suspend lambda):

```kotlin
farhanDatabase.runInTransaction {
    runBlocking {
        notifications.forEach { farhanDatabase.notificationDao.insertNotificationItem(it) }
    }
}
```

This pattern is used three times (notifications, usage events, day-last-updated records). It is intentional: `runInTransaction` requires a synchronous `Runnable`-style block, and `runBlocking` bridges the suspend insert methods into that context.

---

## Database Architecture & Migration

### Original architecture (three databases)

The codebase originally had three separate Room databases:

| Database file | Contents |
|---|---|
| `notifications_db` | Notification filter records |
| `usage_db` | Usage events + day-last-updated tracking |
| *(launcher data)* | App list, time limits (in the main FarhanDatabase) |

### Current architecture

All data is consolidated into a single `FarhanDatabase` (version 2):

**File:** `app/src/main/java/ly/com/tahaben/farhan/db/FarhanDatabase.kt`

```kotlin
@Database(
    entities = [UsageDataItemEntity::class, NotificationItemEntity::class, AppEntity::class,
                TimeLimitEntity::class, DayLastUpdatedEntity::class, LaunchAttemptEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
```

The database schema is exported to `app/schemas/` via the Room KSP plugin (`schemaDirectory("$projectDir/schemas")`).

### One-time data migration helper

`DatabaseCombineHelper.combineDatabases()` handles moving data from the legacy separate databases into `FarhanDatabase`:

1. Scans `context.databaseList()` for `"notifications_db"` and `"usage_db"`.
2. Opens each as a temporary Room instance.
3. Reads all rows into memory, then inserts them row-by-row into `FarhanDatabase` inside a `runInTransaction` block.
4. Deletes all three files for each old database (`.db`, `-wal`, `-shm`).
5. Calls `preferences.saveShouldCombineDb(false)` to prevent re-running.

This migration is user-triggered (via `MainScreenEvent.OnCombineDbAgreeClick` in `MainActivity`) rather than automatic on upgrade. The result (success or failure) is shown as a Snackbar. On failure the activity calls `finish()`.

No baseline profiles are present in the repository.

---

## App Startup Sequence

### Process start

`FarhanApplication.onCreate()` runs:
1. `Timber.plant(Timber.DebugTree())` in debug builds only (guarded by `BuildConfig.DEBUG`).
2. `initAcra { ... }` — configures ACRA crash reporting with a limited field set (version code, version name, Android version, phone model, stack trace).
3. Notification channel creation for API 26+ (two channels: usage fetch, crash reports).
4. `WorkManager.initialize(this, farhanWorkerConfiguration)` — the configuration is Hilt-provided and includes the `HiltWorkerFactory` necessary for dependency injection into workers.

### Activity onCreate

`MainActivity.onCreate()` runs in order:
1. `installSplashScreen().setKeepOnScreenCondition { mainScreenViewModel.isLoading.value }` — extends the splash screen until `MainScreenViewModel.isLoading` becomes false.
2. `onBoardingPref.loadShouldShowOnBoarding()` — synchronous DataStore read (returns cached value).
3. `runBlocking { onBoardingPref.loadThemeColors() }` — blocks the main thread to determine start destination.
4. `getTip()` — reads a random string from resources.
5. `setContent {}` — begins composition.

### Splash screen gate

`MainScreenViewModel.init` launches `loadUiPreferencesOnce()` which collects `first()` from two DataStore flows (UI mode, theme colors). When both emit, `_isLoading.value = false` and the splash screen dismisses. Two additional coroutines are launched for ongoing preference observation (these use `collectLatest` and run for the lifetime of the ViewModel).

### First composition of Routes.MAIN

When the NavHost navigates to `Routes.MAIN` (the common case for returning users), the composable body executes four `runBlocking` calls on the composition thread before rendering the main screen content. These reads are one-time per navigation entry.

---

## Edge-to-Edge & Window Insets

### enableEdgeToEdge() call sites

`enableEdgeToEdge()` is called in three activities:

| Activity | File |
|---|---|
| `DelayedLaunchActivity` | `launcher/launcher_presentation/.../wait/DelayedLaunchActivity.kt` |
| `CrashDetailsActivity` | `app/.../farhan/CrashDetailsActivity.kt` |
| `WidgetConfigActivity` | `app/.../farhan/widget/UsageWidgetConfigActivity.kt` |

`MainActivity` does **not** call `enableEdgeToEdge()`.

### Scaffold padding in MainActivity

`MainActivity`'s root `Scaffold` receives the `PaddingValues` lambda parameter but discards it:

```kotlin
Scaffold(
    modifier = Modifier.fillMaxSize(),
    snackbarHost = { SnackbarHost(snackbarHostState) }
) {
    it.calculateBottomPadding()  // result discarded
    NavHost(
        navController = navController,
        startDestination = ...
    ) { ... }
}
```

`NavHost` and all child composables receive no inset padding from the Scaffold. Each feature screen is responsible for its own window inset handling. Screens that do not apply inset modifiers will render content behind system bars.

### Activities that propagate insets correctly

`CrashDetailsActivity` and `WidgetConfigActivity` both call `enableEdgeToEdge()` and pass Scaffold `innerPadding` to their content via `Modifier.padding(innerPadding)`.

`DelayedLaunchActivity` calls `enableEdgeToEdge()` but renders `DelayedLaunchOverlay` with `Modifier` (no padding applied).

---

## Memory Management

### LeakCanary

LeakCanary version 2.10 is declared in `gradle/libs.versions.toml`:

```toml
leakCanary = { module = "com.squareup.leakcanary:leakcanary-android", version.ref = "leak_canary" }
```

It is commented out in `app/build.gradle.kts`:

```kotlin
//    debugImplementation(libs.leakCanary)
```

The dependency declaration remains in the version catalog while the actual dependency is disabled. This indicates it was used during development and has been turned off, but the declaration is kept for potential future reactivation.

### Overlay ComposeView lifecycle in AccessibilityService

When `showDialog()` creates a `ComposeView` to display the scroll-blocking overlay, it constructs a synthetic lifecycle:

```kotlin
val viewModelStore = ViewModelStore()
val lifecycleOwner = ComposeOverlayLifecycleOwner()
lifecycleOwner.performRestore(null)
lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
composeView.setViewTreeLifecycleOwner(lifecycleOwner)
composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
windowManager.addView(composeView, params)
```

The `ComposeOverlayLifecycleOwner` is a custom class that wraps a `LifecycleRegistry` and a `SavedStateRegistryController`. The lifecycle is driven to `ON_RESUME` but there is no corresponding `ON_PAUSE` / `ON_DESTROY` path; the overlay is removed via `windowManager.removeView(composeView)` and the lifecycle owner is abandoned. The `ViewModelStore` created here is empty (no ViewModels are stored in it).

### recentScrollViews map bounds

The `recentScrollViews: HashMap<Int, ScrollViewInfo>` in `AccessibilityService` has no size cap. Growth is bounded only by the staleness timeout (3 minutes of inactivity clears the whole map) and explicit clears in `onUnbind()` and `onDestroy()`.

---

## Home Screen Widget Refresh

**Files:**
- `usage_overview/usage_overview_presentation/src/main/java/ly/com/tahaben/usage_overview_presentation/widget/UsageWidget.kt`
- `usage_overview/usage_overview_presentation/src/main/java/ly/com/tahaben/usage_overview_presentation/widget/WidgetWorker.kt`
- `app/src/main/java/ly/com/tahaben/farhan/widget/UsageWidgetConfigActivity.kt`

### Periodic refresh

`WidgetConfigActivity.updateConfiguration()` enqueues a `PeriodicWorkRequest` for `WidgetWorker` with a user-selected interval (15 minutes to 12 hours), using `ExistingPeriodicWorkPolicy.UPDATE`. The work is named `WorkerKeys.WIDGET_UPDATE`.

`WidgetWorker.doWork()` runs as a foreground service (silent, ongoing notification). It reads today's live usage and yesterday's cached usage, stores the totals in DataStore, then calls `UsageWidget.updateAll(context)` which triggers Glance to re-render all widget instances.

Widget state (usage totals, theme, layout style, compare-with-yesterday flag, text size) is persisted in Glance's `DataStore<Preferences>` per widget ID, written via `updateAppWidgetState`.

### Manual refresh

Tapping the refresh icon in the widget calls `UsageWidget.refresh()` via `widgetCoroutineScope`:

1. Sets `isLoadingKey = true` in widget state and calls `update()` to show the loading indicator.
2. Enqueues a `OneTimeWorkRequest` for `WidgetWorker` named `"refresh"` with `ExistingWorkPolicy.REPLACE`.
3. Collects `wm.getWorkInfosForUniqueWorkFlow("refresh")` as a flow.
4. When a finished `WorkInfo` is observed, sets `isLoadingKey = false` and calls `update()` to dismiss the loading indicator.

### Widget removal cleanup

`UsageWidget.onDelete()` is called by Glance when a widget instance is removed. It checks whether any other instances of `UsageWidget` remain via `GlanceAppWidgetManager.getGlanceIds(UsageWidget::class.java).isEmpty()`. Only if the list is empty does it call `cancelExistingWorkers()`, which cancels `WorkerKeys.WIDGET_UPDATE`. Multiple widget instances on the home screen share the same periodic worker; the worker is cancelled only when all instances are gone.

### Widget configuration

`WidgetConfigActivity` reads the current widget state via `UsageWidget.getAppWidgetState()` before presenting the configuration UI, so existing settings are pre-populated. On save, it updates the Glance widget state and enqueues/replaces the periodic `WidgetWorker`.
