package ly.com.tahaben.notification_filter_domain.model

data class NotificationBatch(
    val key: String,
    val appName: String,
    val packageName: String,
    val category: NotificationCategory,
    val priority: NotificationPriority,
    val count: Int,
    val latestTimestamp: Long,
    val oldestTimestamp: Long,
    val preview: String?,
    val isExpanded: Boolean = false,
    val items: List<NotificationItem> = emptyList()
)
