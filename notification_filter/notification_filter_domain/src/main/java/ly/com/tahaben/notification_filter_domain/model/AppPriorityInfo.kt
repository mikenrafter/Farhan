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
    /** Order and rank among apps of the same notification priority. Never overrides priority. */
    val queuePreference: ly.com.tahaben.notification_filter_domain.delivery.AppQueuePreference = ly.com.tahaben.notification_filter_domain.delivery.AppQueuePreference(),
)

data class ChannelPriorityInfo(
    val channelId: String,
    // Relative offset from the app's current priority level.
    // offset > 0 → channel is higher priority (lower level number) than the app.
    // offset < 0 → channel is lower priority.
    // 0 (default) → follows the app exactly.
    val offset: Int = 0,
) {
    fun effectivePriority(appPriority: NotificationPriority): NotificationPriority =
        NotificationPriority.fromLevel((appPriority.level - offset).coerceIn(1, 4))
}
