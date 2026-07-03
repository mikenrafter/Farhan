package ly.com.tahaben.notification_filter_presentation

import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

sealed class NotificationFilterEvent {
    data class OnOpenNotification(val notificationItem: NotificationItem) :
        NotificationFilterEvent()

    data class OnDismissNotification(val notificationItem: NotificationItem) :
        NotificationFilterEvent()

    object OnDeleteAllNotifications : NotificationFilterEvent()
    data class OnExcludeAppClick(val appPackageName: String) : NotificationFilterEvent()
    data class OnLaunchAppInfoClick(val appPackageName: String) : NotificationFilterEvent()
    data class OnSetPriorityFilter(val priority: NotificationPriority?) : NotificationFilterEvent()
    object OnToggleClearedView : NotificationFilterEvent()
}
