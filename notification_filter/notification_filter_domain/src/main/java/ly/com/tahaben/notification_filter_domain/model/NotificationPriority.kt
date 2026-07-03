package ly.com.tahaben.notification_filter_domain.model

enum class NotificationPriority(val level: Int) {
    CRITICAL(1),
    HIGH(2),
    MEDIUM(3),
    LOW(4);

    companion object {
        fun fromLevel(level: Int): NotificationPriority =
            entries.firstOrNull { it.level == level } ?: MEDIUM
    }
}
