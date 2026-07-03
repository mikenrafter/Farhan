package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.ChannelPriorityInfo
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository

class GetAppPriorityInfoList(
    private val repository: NotificationRepository,
    private val preferences: Preferences,
) {
    suspend operator fun invoke(): List<AppPriorityInfo> {
        val allNotifications = repository.getAllNotifications()
        val grouped = allNotifications.groupBy { it.packageName }

        return grouped.map { (packageName, notifications) ->
            val mostRecent = notifications.maxByOrNull { it.timestamp }
            val appName = mostRecent?.appName ?: packageName
            val currentPriority = mostRecent?.priority
                ?: ly.com.tahaben.notification_filter_domain.model.NotificationPriority.MEDIUM
            val priorityReason = mostRecent?.priorityReason ?: "Category default"

            val channels = notifications
                .mapNotNull { it.channelId }
                .filter { it.isNotBlank() }
                .distinct()
                .map { channelId ->
                    ChannelPriorityInfo(
                        channelId = channelId,
                        override = preferences.getChannelPriorityOverride(packageName, channelId),
                    )
                }

            AppPriorityInfo(
                packageName = packageName,
                appName = appName ?: packageName,
                currentPriority = currentPriority,
                priorityReason = priorityReason,
                isLocked = preferences.isAppPriorityLocked(packageName),
                userOverride = preferences.getAppPriorityOverride(packageName),
                channels = channels,
            )
        }.sortedBy { it.appName }
    }
}
