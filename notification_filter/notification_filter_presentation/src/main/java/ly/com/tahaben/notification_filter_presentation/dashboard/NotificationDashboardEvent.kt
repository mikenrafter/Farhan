package ly.com.tahaben.notification_filter_presentation.dashboard

import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

sealed class NotificationDashboardEvent {
    data class OnToggleBatch(val batchKey: String) : NotificationDashboardEvent()
    data class OnOpenNotification(val notificationItem: NotificationItem) : NotificationDashboardEvent()
    data class OnDismissNotification(val notificationItem: NotificationItem) : NotificationDashboardEvent()
    data class OnExcludeApp(val packageName: String) : NotificationDashboardEvent()
    data class OnMarkAppRead(val packageName: String) : NotificationDashboardEvent()
    data class OnSetAppPriority(val packageName: String, val priority: NotificationPriority?) : NotificationDashboardEvent()
    data class OnSetBatchWindow(val minutes: Int) : NotificationDashboardEvent()
    data class OnSetStandingThreshold(val hours: Int) : NotificationDashboardEvent()
    data class OnSetFloodThreshold(val count: Int) : NotificationDashboardEvent()
    object OnClearAllStanding : NotificationDashboardEvent()
    object OnDeleteAll : NotificationDashboardEvent()
}
