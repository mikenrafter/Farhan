package ly.com.tahaben.notification_filter_presentation.dashboard

import ly.com.tahaben.notification_filter_domain.model.NotificationBatch
import ly.com.tahaben.notification_filter_domain.model.NotificationDashboardSummary

data class NotificationDashboardState(
    val summary: NotificationDashboardSummary = NotificationDashboardSummary(),
    val batches: List<NotificationBatch> = emptyList(),
    val expandedBatchKeys: Set<String> = emptySet(),
    val batchWindowMinutes: Int = 30,
    val standingThresholdHours: Int = 24,
    val floodThreshold: Int = 10,
    val isLoading: Boolean = true,
)
