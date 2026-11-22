package ly.com.tahaben.farhan.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import ly.com.tahaben.core.R
import ly.com.tahaben.core.navigation.Routes
import ly.com.tahaben.core.util.UiEvent
import ly.com.tahaben.core.util.UiText
import ly.com.tahaben.core_ui.OnLifecycleEvent
import ly.com.tahaben.farhan.settings.SettingsScreen
import ly.com.tahaben.farhan.settings.SettingsTab
import ly.com.tahaben.notification_filter_presentation.NotificationFilterScreen
import ly.com.tahaben.notification_filter_presentation.dashboard.NotificationDashboardViewModel
import ly.com.tahaben.notification_filter_presentation.onboarding.NotificationFilterOnBoardingScreen
import ly.com.tahaben.onboarding_presentaion.main.CombineDbDialog
import ly.com.tahaben.onboarding_presentaion.main.MainScreenEvent
import ly.com.tahaben.onboarding_presentaion.main.MainScreenState

/**
 * The home of the app: a bottom bar of Inbox, Summary, Launcher, Focus and Settings. Launcher and Focus show only
 * while their feature switch is on; Inbox, Summary and Settings are always there. Each tab keeps its own
 * remembered state while another tab is showing.
 */
@Composable
internal fun MainShell(
    navController: NavHostController,
    mainState: MainScreenState,
    onMainEvent: (MainScreenEvent) -> Unit,
    mainUiEvent: UiEvent,
    snackbarHostState: SnackbarHostState,
    isDarkMode: Boolean,
    shouldShowRational: (String) -> Boolean,
    shouldShowNotificationOnboarding: () -> Boolean,
    initialTab: ShellTab = ShellTab.Inbox,
    shellViewModel: ShellViewModel = hiltViewModel(),
    dashboard: NotificationDashboardViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val switches by shellViewModel.switches.collectAsState()
    var selected by rememberSaveable { mutableStateOf(initialTab) }
    var settingsTab by rememberSaveable { mutableStateOf(SettingsTab.Features) }
    val tab = resolveTab(selected, switches)
    val tabs = visibleTabs(switches)
    val stateHolder = rememberSaveableStateHolder()

    LaunchedEffect(Unit) { onMainEvent(MainScreenEvent.OnScreenLaunched) }
    LaunchedEffect(mainUiEvent) {
        if (mainUiEvent is UiEvent.ShowSnackbar) {
            snackbarHostState.showSnackbar(mainUiEvent.message.asString(context))
            onMainEvent(MainScreenEvent.HideSnackBar)
        }
    }

    // The unread count on the Inbox tab comes from the dashboard's summary, which Summary shows too.
    OnLifecycleEvent { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> dashboard.startObservingSummary()
            Lifecycle.Event.ON_STOP -> dashboard.stopObservingSummary()
            else -> Unit
        }
    }
    DisposableEffect(dashboard) { onDispose { dashboard.stopObservingSummary() } }

    // Back from any other tab returns to the Inbox first; only the Inbox lets back leave the app.
    androidx.activity.compose.BackHandler(enabled = tab != ShellTab.Inbox) { selected = ShellTab.Inbox }

    val openSettings: (SettingsTab) -> Unit = { target ->
        settingsTab = target
        selected = ShellTab.Settings
    }

    Scaffold(
        bottomBar = {
            ShellNavigationBar(
                tabs = tabs,
                selected = tab,
                unreadCount = dashboard.state.summary.unreadCount,
                onSelect = { selected = it },
            )
        },
    ) { padding ->
        stateHolder.SaveableStateProvider(tab.name) {
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                when (tab) {
                    ShellTab.Inbox -> InboxTab(
                        shouldShowNotificationOnboarding,
                        shouldShowRational,
                        onOpenSettings = { openSettings(SettingsTab.Notifications) },
                    )
                    ShellTab.Summary -> SummaryTab(
                        dashboard = dashboard,
                        showUsage = switches.usage,
                        onOpenSchedule = { navController.navigate(Routes.NOTIFICATION_SCHEDULE_EDITOR) },
                        onOpenUsage = { navController.navigate(Routes.USAGE) },
                    )
                    ShellTab.Launcher -> LauncherTab()
                    ShellTab.Focus -> FocusTab(
                        mainSwitchEnabled = mainState.isMainSwitchEnabled,
                        onOpen = { navController.navigate(it) },
                        onMainSwitchOff = {
                            onMainEvent(MainScreenEvent.ShowSnackBar(UiText.StringResource(R.string.please_turn_on_main_switch)))
                        },
                    )
                    ShellTab.Settings -> SettingsScreen(
                        selectedTab = settingsTab,
                        onSelectTab = { settingsTab = it },
                        state = mainState,
                        onEvent = onMainEvent,
                        switches = switches,
                        onUsageChange = shellViewModel::setUsage,
                        onLauncherChange = shellViewModel::setLauncher,
                        onFocusChange = shellViewModel::setFocus,
                        snackbarHostState = snackbarHostState,
                        isDarkMode = isDarkMode,
                        shouldShowRational = shouldShowRational,
                        onOpen = { navController.navigate(it) },
                    )
                }
            }
        }
    }

    CombineDbDialog(mainState, onMainEvent)
}

/** The Inbox: the existing screen, behind the notification filter's first-run explanation. */
@Composable
private fun InboxTab(
    shouldShowOnboarding: () -> Boolean,
    shouldShowRational: (String) -> Boolean,
    onOpenSettings: () -> Unit,
) {
    var onboarding by rememberSaveable { mutableStateOf(shouldShowOnboarding()) }
    if (onboarding) {
        NotificationFilterOnBoardingScreen(onNextClick = { onboarding = false })
    } else {
        NotificationFilterScreen(
            navigateToNotificationSettings = onOpenSettings,
            onNavigateUp = null,
            shouldShowRational = shouldShowRational,
        )
    }
}

/** Calm bar: a hairline above it, no filled pill, and the active icon is the only strong colour. */
@Composable
private fun ShellNavigationBar(
    tabs: List<ShellTab>,
    selected: ShellTab,
    unreadCount: Int,
    onSelect: (ShellTab) -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            tabs.forEach { tab ->
                val badge = if (tab == ShellTab.Inbox) unreadBadgeText(unreadCount) else null
                val badgeDescription = badge?.let {
                    pluralStringResource(R.plurals.unread_notifications, unreadCount, unreadCount)
                }
                NavigationBarItem(
                    modifier = Modifier.semantics {
                        if (badgeDescription != null) stateDescription = badgeDescription
                    },
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                    icon = {
                        BadgedBox(badge = { if (badge != null) Badge { Text(badge) } }) {
                            Icon(tab.icon, contentDescription = null)
                        }
                    },
                    label = { Text(stringResource(tab.label)) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

private val ShellTab.icon: ImageVector
    get() = when (this) {
        ShellTab.Inbox -> Icons.Filled.Inbox
        ShellTab.Summary -> Icons.Filled.Dashboard
        ShellTab.Launcher -> Icons.Filled.GridView
        ShellTab.Focus -> Icons.Filled.Timelapse
        ShellTab.Settings -> Icons.Filled.Settings
    }

private val ShellTab.label: Int
    get() = when (this) {
        ShellTab.Inbox -> R.string.notification_inbox
        ShellTab.Summary -> R.string.shell_tab_summary
        ShellTab.Launcher -> R.string.launcher
        ShellTab.Focus -> R.string.shell_tab_focus
        ShellTab.Settings -> R.string.settings
    }
