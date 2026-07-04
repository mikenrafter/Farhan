package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.ChannelPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.KnownPackageSource
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class GetAppPriorityInfoList(
    private val preferences: Preferences,
    private val classifyNotification: ClassifyNotification,
    private val scanInstalledNotificationSources: ScanInstalledNotificationSources,
    private val scanVisibleNotificationSources: ScanVisibleNotificationSources,
) {
    suspend operator fun invoke(): List<AppPriorityInfo> {
        scanInstalledNotificationSources()
        scanVisibleNotificationSources()
        val knownPackages = preferences.getKnownPackageSources().toMutableMap()

        preferences.getNotificationFilterExceptionsList().forEach { packageName ->
            if (packageName !in knownPackages) {
                knownPackages[packageName] = KnownPackageSource(
                    packageName = packageName,
                    appName = packageName,
                )
            }
        }

        return knownPackages.values.map { source ->
            val userOverride = preferences.getAppPriorityOverride(source.packageName)
            val defaultCategory = source.androidCategories.firstOrNull()?.let { androidCategory ->
                when (androidCategory) {
                    "msg", "email", "call", "missed_call" -> NotificationCategory.COMMUNICATION
                    "social", "promo", "recommendation" -> NotificationCategory.SOCIAL
                    "reminder", "event" -> NotificationCategory.PRODUCTIVITY
                    "sys", "service" -> NotificationCategory.SYSTEM
                    "workout", "navigation", "location" -> NotificationCategory.HEALTH
                    else -> NotificationCategory.OTHER
                }
            } ?: NotificationCategory.OTHER

            val (basePriority, _) = classifyNotification(
                androidCategory = source.androidCategories.firstOrNull(),
                packageName = source.packageName,
                channelId = null,
            )
            val currentPriority = userOverride ?: basePriority
            val priorityReason = when {
                userOverride != null -> "Set by user"
                source.androidCategories.isNotEmpty() -> "Category default"
                else -> "Default priority"
            }

            val channels = source.channelIds.sorted().map { channelId ->
                ChannelPriorityInfo(
                    channelId = channelId,
                    override = preferences.getChannelPriorityOverride(source.packageName, channelId),
                )
            }

            AppPriorityInfo(
                packageName = source.packageName,
                appName = source.appName,
                currentPriority = currentPriority,
                priorityReason = priorityReason,
                isLocked = preferences.isAppPriorityLocked(source.packageName),
                userOverride = userOverride,
                channels = channels,
            )
        }.sortedBy { it.appName.lowercase() }
    }
}
