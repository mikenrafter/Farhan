package ly.com.tahaben.notification_filter_presentation

import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

sealed class NotificationFilterEvent {
    data class OnOpenNotification(val notificationItem: NotificationItem) :
        NotificationFilterEvent()

    data class OnDismissNotification(val notificationItem: NotificationItem) :
        NotificationFilterEvent()

    data class OnToggleThread(val threadKey: String) : NotificationFilterEvent()

    /** Ticks or unticks rows; a conversation toggles all of its messages together. */
    data class OnToggleSelected(val ids: List<String>) : NotificationFilterEvent()

    /** Clears the ticked rows. */
    object OnClearSelected : NotificationFilterEvent()

    data class OnClearNotifications(val scope: InboxClearScope, val standingCutoff: Long, val search: String) : NotificationFilterEvent()
    data class OnExcludeAppClick(val appPackageName: String) : NotificationFilterEvent()
    data class OnLaunchAppInfoClick(val appPackageName: String) : NotificationFilterEvent()
    data class OnSetPriorityFilter(val priority: NotificationPriority?) : NotificationFilterEvent()
    object OnToggleClearedView : NotificationFilterEvent()
    data class OnFireNotificationAction(val notificationId: String, val actionIndex: Int) :
        NotificationFilterEvent()
}
