package ly.com.tahaben.notification_filter_domain.model

enum class NotificationCategory {
    COMMUNICATION,
    SOCIAL,
    PRODUCTIVITY,
    FINANCE,
    HEALTH,
    ENTERTAINMENT,
    SYSTEM,
    OTHER;

    val defaultPriority: NotificationPriority
        get() = when (this) {
            COMMUNICATION -> NotificationPriority.HIGH
            SOCIAL -> NotificationPriority.LOW
            PRODUCTIVITY -> NotificationPriority.MEDIUM
            FINANCE -> NotificationPriority.MEDIUM
            HEALTH -> NotificationPriority.MEDIUM
            ENTERTAINMENT -> NotificationPriority.LOW
            SYSTEM -> NotificationPriority.MEDIUM
            OTHER -> NotificationPriority.MEDIUM
        }

    companion object {
        fun fromName(name: String): NotificationCategory =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}
