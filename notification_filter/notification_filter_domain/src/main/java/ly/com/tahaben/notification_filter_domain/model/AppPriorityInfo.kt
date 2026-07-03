package ly.com.tahaben.notification_filter_domain.model

data class AppPriorityInfo(
    val packageName: String,
    val appName: String,
    val currentPriority: NotificationPriority,
    val priorityReason: String,
    val isLocked: Boolean,
    val userOverride: NotificationPriority?,
    val channels: List<ChannelPriorityInfo>,
)

data class ChannelPriorityInfo(
    val channelId: String,
    val override: NotificationPriority?,  // null = inherits from app
)
