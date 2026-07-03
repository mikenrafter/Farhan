package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class ClassifyNotification(private val preferences: Preferences) {

    operator fun invoke(
        androidCategory: String?,
        packageName: String
    ): Pair<NotificationPriority, NotificationCategory> {
        val priorityOverride = preferences.getAppPriorityOverride(packageName)

        val defaultPriority = when (androidCategory) {
            "msg", "email" -> NotificationPriority.HIGH
            "reminder", "event" -> NotificationPriority.HIGH
            "promo", "recommendation" -> NotificationPriority.LOW
            "social" -> NotificationPriority.LOW
            else -> NotificationPriority.MEDIUM
        }

        val category = when (androidCategory) {
            "msg", "email" -> NotificationCategory.COMMUNICATION
            "call", "missed_call" -> NotificationCategory.COMMUNICATION
            "social", "promo", "recommendation" -> NotificationCategory.SOCIAL
            "reminder", "event" -> NotificationCategory.PRODUCTIVITY
            "sys", "service" -> NotificationCategory.SYSTEM
            "workout", "navigation", "location" -> NotificationCategory.HEALTH
            else -> NotificationCategory.OTHER
        }

        return Pair(priorityOverride ?: defaultPriority, category)
    }
}
