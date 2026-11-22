package ly.com.tahaben.farhan.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core.R
import ly.com.tahaben.core_ui.components.CommonAppBar
import ly.com.tahaben.notification_filter_presentation.dashboard.HourlyTimelineCard
import ly.com.tahaben.notification_filter_presentation.dashboard.NotificationDashboardViewModel
import ly.com.tahaben.notification_filter_presentation.dashboard.ObserveDashboardDetails
import ly.com.tahaben.notification_filter_presentation.dashboard.notificationDashboardItems
import ly.com.tahaben.usage_overview_presentation.UsageOverviewSection

/**
 * Summary: today by hour, then the usage section (only while the Usage switch is on), then the notification
 * dashboard's widgets, all from the existing view models.
 */
@Composable
internal fun SummaryTab(
    dashboard: NotificationDashboardViewModel,
    showUsage: Boolean,
    onOpenSchedule: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    ObserveDashboardDetails(dashboard)
    val state = dashboard.state
    Column(Modifier.fillMaxSize()) {
        CommonAppBar(title = stringResource(R.string.shell_tab_summary), onNavigateUp = null)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "hourly") { HourlyTimelineCard() }
            if (showUsage) item(key = "usage") { UsageOverviewSection(onOpenFull = onOpenUsage) }
            if (state.isLoading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else {
                notificationDashboardItems(state, dashboard::onEvent, onOpenSchedule)
            }
        }
    }
}
