package ly.com.tahaben.notification_filter_domain.model

data class NotificationItem(
    val id: String,
    val appName: String?,
    val title: String?,
    val text: String?,
    val time: String,
    val packageName: String,
    val timestamp: Long = 0L,
    val priority: NotificationPriority = NotificationPriority.MEDIUM,
    val category: NotificationCategory = NotificationCategory.OTHER,
    val isRead: Boolean = false,
    val channelId: String? = null,
    val isCleared: Boolean = false,
    val isDelivered: Boolean = false,
    val priorityReason: String? = null,
    val sourceSession: String = "",
    val sourceEventOrder: Long = 0,
    val deliveryInvalidated: Boolean = false,
    val snoozeDeadline: Long? = null,
    val snoozePriority: NotificationPriority? = null,
    val snoozeBootCount: Int? = null,
    val snoozeGroupKey: String? = null,
) {
    val hasLiveSnooze: Boolean
        get() = snoozeDeadline != null && !isCleared && !isDelivered && !deliveryInvalidated
}
