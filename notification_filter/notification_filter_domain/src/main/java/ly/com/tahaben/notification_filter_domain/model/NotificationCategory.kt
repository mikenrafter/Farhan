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

    companion object {
        fun fromName(name: String): NotificationCategory =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}
