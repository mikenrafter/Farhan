package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class ClassifyNotification(private val preferences: Preferences) {

    operator fun invoke(
        androidCategory: String?,
        packageName: String,
        channelId: String? = null,
    ): Pair<NotificationPriority, NotificationCategory> {
        val category = when (androidCategory) {
            "msg", "email" -> NotificationCategory.COMMUNICATION
            "call", "missed_call" -> NotificationCategory.COMMUNICATION
            "social", "promo", "recommendation" -> NotificationCategory.SOCIAL
            "reminder", "event" -> NotificationCategory.PRODUCTIVITY
            "sys", "service" -> NotificationCategory.SYSTEM
            "workout", "navigation", "location" -> NotificationCategory.HEALTH
            else -> NotificationCategory.OTHER
        }

        // Channel-level override has highest priority
        if (channelId != null) {
            val channelOverride = preferences.getChannelPriorityOverride(packageName, channelId)
            if (channelOverride != null) {
                return Pair(channelOverride, category)
            }
        }

        val isLocked = preferences.isAppPriorityLocked(packageName)
        val priorityOverride = preferences.getAppPriorityOverride(packageName)

        // If the priority is locked, use the override (or category default) without any dynamic adjustment
        if (isLocked) {
            val lockedPriority = priorityOverride ?: (preferences.getCategoryPriorityOverride(category) ?: category.defaultPriority)
            return Pair(lockedPriority, category)
        }

        // Manual app-level override takes precedence over category default
        if (priorityOverride != null) {
            return Pair(priorityOverride, category)
        }

        // Use category-level override if set, otherwise use the category's default priority
        val categoryPriority = preferences.getCategoryPriorityOverride(category) ?: category.defaultPriority

        return Pair(categoryPriority, category)
    }
}
