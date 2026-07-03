package ly.com.tahaben.notification_filter_presentation.dashboard

import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod

sealed class NotificationDashboardEvent {
    data class OnToggleBatch(val batchKey: String) : NotificationDashboardEvent()
    data class OnOpenNotification(val notificationItem: NotificationItem) : NotificationDashboardEvent()
    data class OnDismissNotification(val notificationItem: NotificationItem) : NotificationDashboardEvent()
    data class OnSetLowPriority(val packageName: String) : NotificationDashboardEvent()
    data class OnMarkAppRead(val packageName: String) : NotificationDashboardEvent()
    data class OnSetAppPriority(val packageName: String, val priority: NotificationPriority?) : NotificationDashboardEvent()
    data class OnSetAppPriorityLocked(val packageName: String, val locked: Boolean) : NotificationDashboardEvent()
    data class OnSetCategoryPriority(val category: NotificationCategory, val priority: NotificationPriority?) : NotificationDashboardEvent()
    data class OnSetBatchWindow(val minutes: Int) : NotificationDashboardEvent()
    data class OnSetStandingThreshold(val hours: Int) : NotificationDashboardEvent()
    data class OnSetFloodThreshold(val count: Int) : NotificationDashboardEvent()
    data class OnSetSchedulePeriod(val index: Int, val period: NotificationSchedulePeriod) : NotificationDashboardEvent()
    object OnClearAllStanding : NotificationDashboardEvent()
    object OnDeleteAll : NotificationDashboardEvent()
}
