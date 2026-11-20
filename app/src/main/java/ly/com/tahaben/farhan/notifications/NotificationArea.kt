package ly.com.tahaben.farhan.notifications

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ly.com.tahaben.core.R
import ly.com.tahaben.core_ui.OnLifecycleEvent
import ly.com.tahaben.core_ui.components.CommonAppBar
import ly.com.tahaben.notification_filter_presentation.NotificationFilterScreen
import ly.com.tahaben.notification_filter_presentation.apppriority.AppPriorityScreen
import ly.com.tahaben.notification_filter_presentation.dashboard.NotificationDashboardScreen
import ly.com.tahaben.notification_filter_presentation.dashboard.NotificationDashboardViewModel

@Composable
internal fun NotificationArea(
    onNavigateUp: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSchedule: () -> Unit,
    shouldShowRational: (String) -> Boolean,
    dashboard: NotificationDashboardViewModel = hiltViewModel(),
) {
    val tabs = rememberNavController()
    val entry by tabs.currentBackStackEntryAsState()
    val selectedTab = NotificationTab.fromRoute(entry?.destination?.route) ?: NotificationTab.Inbox

    OnLifecycleEvent { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> dashboard.startObservingSummary()
            Lifecycle.Event.ON_STOP -> dashboard.stopObservingSummary()
            else -> Unit
        }
    }
    DisposableEffect(dashboard) {
        onDispose { dashboard.stopObservingSummary() }
    }

    Scaffold(
        bottomBar = {
            NotificationTabBar(
                selectedTab = selectedTab,
                unreadCount = dashboard.state.summary.unreadCount,
                onSelect = { tab ->
                    if (tab != selectedTab) {
                        tabs.navigate(tab.route) {
                            popUpTo(NotificationTab.Inbox.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        }
    ) { padding ->
        NavHost(
            navController = tabs,
            startDestination = NotificationTab.Inbox.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(NotificationTab.Overview.route) {
                Column {
                    NotificationTabAppBar(R.string.notifications, onNavigateUp, onOpenSettings)
                    NotificationDashboardScreen(dashboard, onOpenSchedule)
                }
            }
            composable(NotificationTab.Inbox.route) {
                NotificationFilterScreen(
                    navigateToNotificationSettings = onOpenSettings,
                    onNavigateUp = onNavigateUp,
                    shouldShowRational = shouldShowRational,
                )
            }
            composable(NotificationTab.Apps.route) {
                Column {
                    NotificationTabAppBar(R.string.notification_apps, onNavigateUp, onOpenSettings)
                    AppPriorityScreen()
                }
            }
        }
        BackHandler(enabled = selectedTab != NotificationTab.Inbox, onBack = onBack)
    }
}

@Composable
private fun NotificationTabAppBar(
    title: Int,
    onNavigateUp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    CommonAppBar(
        title = stringResource(title),
        onNavigateUp = onNavigateUp,
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, stringResource(R.string.notifications_filter_settings))
            }
        },
    )
}

@Composable
private fun NotificationTabBar(
    selectedTab: NotificationTab,
    unreadCount: Int,
    onSelect: (NotificationTab) -> Unit,
) {
    NavigationBar {
        NotificationTab.entries.forEach { tab ->
            val badge = if (tab == NotificationTab.Inbox) unreadBadgeText(unreadCount) else null
            val badgeDescription = badge?.let {
                pluralStringResource(R.plurals.unread_notifications, unreadCount, unreadCount)
            }
            NavigationBarItem(
                modifier = Modifier.semantics {
                    if (badgeDescription != null) stateDescription = badgeDescription
                },
                selected = selectedTab == tab,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(
                        badge = { if (badge != null) Badge { Text(badge) } },
                    ) {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(stringResource(tab.label)) },
                alwaysShowLabel = true,
            )
        }
    }
}

private val NotificationTab.icon: ImageVector
    get() = when (this) {
        NotificationTab.Overview -> Icons.Filled.Dashboard
        NotificationTab.Inbox -> Icons.Filled.Inbox
        NotificationTab.Apps -> Icons.Filled.Flag
    }

private val NotificationTab.label: Int
    get() = when (this) {
        NotificationTab.Overview -> R.string.notification_overview
        NotificationTab.Inbox -> R.string.notification_inbox
        NotificationTab.Apps -> R.string.notification_apps
    }
