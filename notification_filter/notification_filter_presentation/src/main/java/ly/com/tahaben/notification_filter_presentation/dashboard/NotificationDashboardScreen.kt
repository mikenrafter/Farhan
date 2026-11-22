package ly.com.tahaben.notification_filter_presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.core_ui.OnLifecycleEvent
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.BudgetSpendWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.CategoryBreakdownWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.FloodStatusWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.NotificationHealthWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.NotificationScheduleWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.PriorityDeliveryWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.PriorityDistributionWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.RecentBatchesWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.StandingAlarmsWidget
import ly.com.tahaben.notification_filter_presentation.dashboard.widgets.TopChattersWidget

@Composable
fun NotificationDashboardScreen(
    viewModel: NotificationDashboardViewModel,
    onNavigateToScheduleEditor: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val state = viewModel.state
    ObserveDashboardDetails(viewModel)

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.spaceMedium),
                verticalArrangement = Arrangement.spacedBy(spacing.spaceMedium)
            ) {
                notificationDashboardItems(state, viewModel::onEvent, onNavigateToScheduleEditor)
            }
        }
    }
}

/** Keeps the batch and standing-alert observers running while the dashboard content is on screen and resumed. */
@Composable
fun ObserveDashboardDetails(viewModel: NotificationDashboardViewModel) {
    OnLifecycleEvent { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> viewModel.startObservingDetails()
            Lifecycle.Event.ON_PAUSE -> viewModel.stopObservingDetails()
            else -> Unit
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopObservingDetails() }
    }
}

/**
 * The dashboard widgets as list items, so a screen can place them inside its own list (the Summary tab does).
 * Callers show their own loading state while [NotificationDashboardState.isLoading].
 */
fun LazyListScope.notificationDashboardItems(
    state: NotificationDashboardState,
    onEvent: (NotificationDashboardEvent) -> Unit,
    onNavigateToScheduleEditor: () -> Unit,
) {
    val summary = state.summary
    val standingItems = state.standingItems

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

    if (summary.isFlooding) {
        item {
            FloodStatusWidget(
                recentCount = summary.totalToday,
                floodThreshold = state.floodThreshold,
                onClearAll = { onEvent(NotificationDashboardEvent.OnDeleteAll) }
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
                totalCount = summary.standingCount,
                standingItems = standingItems,
                standingThresholdHours = state.standingThresholdHours,
                onClearAll = { onEvent(NotificationDashboardEvent.OnClearAllStanding) }
            )
        }
    }

    if (summary.topChatters.isNotEmpty()) {
        item {
            TopChattersWidget(
                chatters = summary.topChatters,
                onSetLowPriority = { packageName ->
                    onEvent(NotificationDashboardEvent.OnSetLowPriority(packageName))
                }
            )
        }
    }

    item {
        PriorityDeliveryWidget(
            settings = state.priorityDeliverySettings,
            testingMode = state.deliveryTestingMode,
            onSettingChanged = { priority, setting ->
                onEvent(NotificationDashboardEvent.OnSetPriorityDeliverySetting(priority, setting))
            },
        )
    }

    item {
        BudgetSpendWidget(
            budgetStatus = state.budgetStatus,
        )
    }

    if (state.scheduleError != null) item {
        Text(state.scheduleError, color = MaterialTheme.colorScheme.error)
    } else item {
        NotificationScheduleWidget(
            schedule = state.schedule,
            isDatedToday = state.isScheduleDatedToday,
            onEdit = onNavigateToScheduleEditor,
        )
    }

    item {
        RecentBatchesWidget(
            onBatchPage = { key, offset -> onEvent(NotificationDashboardEvent.OnBatchPage(key, offset)) },
            batches = state.batches,
            expandedKeys = state.expandedBatchKeys,
            onToggleBatch = { key ->
                onEvent(NotificationDashboardEvent.OnToggleBatch(key))
            },
            onOpenNotification = { item ->
                onEvent(NotificationDashboardEvent.OnOpenNotification(item))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
