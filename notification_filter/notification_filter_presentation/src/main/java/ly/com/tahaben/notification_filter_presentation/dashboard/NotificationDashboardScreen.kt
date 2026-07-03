package ly.com.tahaben.notification_filter_presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.core_ui.mirror
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.CategoryBreakdownWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.FloodStatusWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.NotificationHealthWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.NotificationScheduleWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.PriorityDistributionWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.RecentBatchesWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.StandingAlarmsWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.TopChattersWidget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationDashboardScreen(
    viewModel: NotificationDashboardViewModel = hiltViewModel(),
    onNavigateUp: () -> Unit,
    onNavigateToInbox: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAppPriority: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val state = viewModel.state
    val summary = state.summary

    val standingItems = state.batches
        .flatMap { it.items }
        .filter { !it.isRead && it.timestamp < System.currentTimeMillis() - state.standingThresholdHours * 3600 * 1000L }
        .sortedBy { it.timestamp }

    val healthDescription = buildString {
        if (summary.standingCount > 0) append("${summary.standingCount} standing alert${if (summary.standingCount > 1) "s" else ""}")
        if (summary.isFlooding) {
            if (isNotEmpty()) append(", ")
            append("notification flood active")
        }
        val criticalUnread = summary.priorityBreakdown[NotificationPriority.CRITICAL] ?: 0
        if (criticalUnread > 0) {
            if (isNotEmpty()) append(", ")
            append("$criticalUnread critical unread")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            androidx.compose.foundation.layout.Column {
                TopAppBar(
                    title = { Text(text = "Notification Dashboard") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                modifier = Modifier.mirror(),
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToAppPriority) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "App Priorities"
                            )
                        }
                        IconButton(onClick = onNavigateToInbox) {
                            Icon(
                                imageVector = Icons.Filled.List,
                                contentDescription = "View Inbox"
                            )
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    }
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.spaceMedium),
                    verticalArrangement = Arrangement.spacedBy(spacing.spaceMedium)
                ) {
                    if (summary.isFlooding) {
                        item {
                            FloodStatusWidget(
                                recentCount = summary.totalToday,
                                floodThreshold = state.floodThreshold,
                                onClearAll = { viewModel.onEvent(NotificationDashboardEvent.OnDeleteAll) }
                            )
                        }
                    }

                    item {
                        NotificationHealthWidget(
                            score = summary.healthScore,
                            description = healthDescription
                        )
                    }

                    item {
                        PriorityDistributionWidget(breakdown = summary.priorityBreakdown)
                    }

                    item {
                        CategoryBreakdownWidget(breakdown = summary.categoryBreakdown)
                    }

                    if (standingItems.isNotEmpty()) {
                        item {
                            StandingAlarmsWidget(
                                standingItems = standingItems,
                                standingThresholdHours = state.standingThresholdHours,
                                onClearAll = { viewModel.onEvent(NotificationDashboardEvent.OnClearAllStanding) }
                            )
                        }
                    }

                    if (summary.topChatters.isNotEmpty()) {
                        item {
                            TopChattersWidget(
                                chatters = summary.topChatters,
                                onSetLowPriority = { packageName ->
                                    viewModel.onEvent(
                                        NotificationDashboardEvent.OnSetLowPriority(packageName)
                                    )
                                }
                            )
                        }
                    }

                    item {
                        NotificationScheduleWidget(
                            schedule = state.schedule,
                            onPeriodChanged = { index, period ->
                                viewModel.onEvent(
                                    NotificationDashboardEvent.OnSetSchedulePeriod(index, period)
                                )
                            }
                        )
                    }

                    item {
                        RecentBatchesWidget(
                            batches = state.batches,
                            expandedKeys = state.expandedBatchKeys,
                            onToggleBatch = { key ->
                                viewModel.onEvent(NotificationDashboardEvent.OnToggleBatch(key))
                            },
                            onOpenNotification = { item ->
                                viewModel.onEvent(NotificationDashboardEvent.OnOpenNotification(item))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
