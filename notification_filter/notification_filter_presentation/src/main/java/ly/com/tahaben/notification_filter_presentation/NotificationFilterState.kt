package ly.com.tahaben.notification_filter_presentation

import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

data class NotificationFilterState(
    val isServiceEnabled: Boolean = false,
    val isPermissionGranted: Boolean = true,
    val isFirstTimeOpened: Boolean = false,
    val filteredNotifications: List<NotificationItem> = emptyList(),
    val allNotifications: List<NotificationItem> = emptyList(),
    val clearedNotifications: List<NotificationItem> = emptyList(),
    val priorityFilter: NotificationPriority? = null,
    val showCleared: Boolean = false,
    val notificationActions: Map<String, List<String>> = emptyMap(),
    val displayPriorities: Map<String, NotificationPriority> = emptyMap(),
)
