package ly.com.tahaben.notification_filter_presentation.dashboard

import ly.com.tahaben.notification_filter_domain.model.NotificationBatch
import ly.com.tahaben.notification_filter_domain.model.NotificationDashboardSummary
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod

data class NotificationDashboardState(
    val summary: NotificationDashboardSummary = NotificationDashboardSummary(),
    val batches: List<NotificationBatch> = emptyList(),
    val expandedBatchKeys: Set<String> = emptySet(),
    val batchWindowMinutes: Int = 30,
    val standingThresholdHours: Int = 3,
    val floodThreshold: Int = 10,
    val schedule: List<NotificationSchedulePeriod> = NotificationSchedulePeriod.DEFAULT_SCHEDULE,
    val isLoading: Boolean = true,
)
