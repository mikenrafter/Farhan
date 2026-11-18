package ly.com.tahaben.notification_filter_presentation.dashboard

import ly.com.tahaben.notification_filter_domain.model.CurrentBudgetStatus
import ly.com.tahaben.notification_filter_domain.model.NotificationBatch
import ly.com.tahaben.notification_filter_domain.model.NotificationDashboardSummary
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting

data class NotificationDashboardState(
    val summary: NotificationDashboardSummary = NotificationDashboardSummary(),
    val standingItems: List<ly.com.tahaben.notification_filter_domain.model.NotificationItem> = emptyList(),
    val batches: List<NotificationBatch> = emptyList(),
    val expandedBatchKeys: Set<String> = emptySet(),
    val batchWindowMinutes: Int = 30,
    val standingThresholdHours: Int = 3,
    val floodThreshold: Int = 10,
    /** The schedule in force today: today's one-date schedule when it has one, else the every-day schedule. */
    val schedule: List<NotificationSchedulePeriod> = NotificationSchedulePeriod.DEFAULT_SCHEDULE,
    val isScheduleDatedToday: Boolean = false,
    val budgetStatus: CurrentBudgetStatus = CurrentBudgetStatus(),
    val priorityDeliverySettings: Map<NotificationPriority, PriorityDeliverySetting> = PriorityDeliverySetting.DEFAULTS,
    val deliveryTestingMode: Boolean = false,
    val isLoading: Boolean = true,
    val scheduleError: String? = null,
)
