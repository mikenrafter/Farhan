package ly.com.tahaben.notification_filter_domain.model

/**
 * Mirrors [android.content.pm.ApplicationInfo] category constants (API 26+).
 */
object AppStoreCategory {
    const val UNDEFINED = 0
    const val GAME = 1
    const val AUDIO = 2
    const val VIDEO = 3
    const val IMAGE = 4
    const val SOCIAL = 5
    const val NEWS = 6
    const val MAPS = 7
    const val PRODUCTIVITY = 8
    const val ACCESSIBILITY = 9
}

object NotificationCategoryResolver {

    fun fromAndroidNotificationCategory(androidCategory: String?): NotificationCategory =
        when (androidCategory) {
            "msg", "email", "call", "missed_call", "alarm" -> NotificationCategory.COMMUNICATION
            "social", "promo", "recommendation" -> NotificationCategory.SOCIAL
            "reminder", "event", "transport" -> NotificationCategory.PRODUCTIVITY
            "sys", "service", "error", "status", "progress" -> NotificationCategory.SYSTEM
            "workout", "navigation", "location" -> NotificationCategory.HEALTH
            else -> NotificationCategory.OTHER
        }

    fun fromAppStoreCategory(appCategoryCode: Int?): NotificationCategory =
        when (appCategoryCode) {
            AppStoreCategory.SOCIAL -> NotificationCategory.SOCIAL
            AppStoreCategory.GAME,
            AppStoreCategory.AUDIO,
            AppStoreCategory.VIDEO,
            AppStoreCategory.IMAGE -> NotificationCategory.ENTERTAINMENT
            AppStoreCategory.PRODUCTIVITY -> NotificationCategory.PRODUCTIVITY
            AppStoreCategory.NEWS -> NotificationCategory.OTHER
            AppStoreCategory.MAPS -> NotificationCategory.HEALTH
            AppStoreCategory.ACCESSIBILITY -> NotificationCategory.SYSTEM
            else -> NotificationCategory.OTHER
        }

    fun resolve(
        primaryAndroidCategory: String?,
        knownAndroidCategories: Set<String>,
        appCategoryCode: Int?,
    ): NotificationCategory {
        if (!primaryAndroidCategory.isNullOrBlank()) {
            return fromAndroidNotificationCategory(primaryAndroidCategory)
        }

        val fromNotifications = knownAndroidCategories
            .map { fromAndroidNotificationCategory(it) }
            .filter { it != NotificationCategory.OTHER }
        if (fromNotifications.isNotEmpty()) {
            return fromNotifications.minBy { it.defaultPriority.level }
        }

        return fromAppStoreCategory(appCategoryCode)
    }

    fun androidNotificationCategoryLabel(androidCategory: String): String =
        when (androidCategory) {
            "msg" -> "Messages"
            "email" -> "Email"
            "call" -> "Calls"
            "missed_call" -> "Missed calls"
            "alarm" -> "Alarms"
            "social" -> "Social"
            "promo" -> "Promotions"
            "recommendation" -> "Recommendations"
            "reminder" -> "Reminders"
            "event" -> "Events"
            "transport" -> "Transport"
            "sys" -> "System"
            "service" -> "Services"
            "error" -> "Errors"
            "status" -> "Status"
            "progress" -> "Progress"
            "workout" -> "Workout"
            "navigation" -> "Navigation"
            "location" -> "Location"
            else -> androidCategory.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
}
