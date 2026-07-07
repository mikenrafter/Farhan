package ly.com.tahaben.notification_filter_domain.model

data class AppPriorityInfo(
    val packageName: String,
    val appName: String,
    val currentPriority: NotificationPriority,
    val defaultPriority: NotificationPriority,
    val resolvedCategory: NotificationCategory,
    val priorityReason: String,
    val appCategoryLabel: String?,
    val androidNotificationCategoryLabels: List<String>,
    val isLocked: Boolean,
    val userOverride: NotificationPriority?,
    val channels: List<ChannelPriorityInfo>,
    val notificationsEnabled: Boolean = true,
)

data class ChannelPriorityInfo(
    val channelId: String,
    val override: NotificationPriority?,  // null = inherits from app
)
