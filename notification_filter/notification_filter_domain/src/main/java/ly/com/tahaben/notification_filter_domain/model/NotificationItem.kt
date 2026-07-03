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
)
