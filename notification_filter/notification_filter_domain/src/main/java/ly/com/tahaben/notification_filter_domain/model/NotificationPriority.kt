package ly.com.tahaben.notification_filter_domain.model

enum class NotificationPriority(val level: Int) {
    CRITICAL(1),
    HIGH(2),
    MEDIUM(3),
    LOW(4);

    val pointCost: Int
        get() = when (this) {
            CRITICAL -> 0
            HIGH -> 2
            MEDIUM -> 4
            LOW -> 8
        }

    fun degraded(levels: Int = 1): NotificationPriority = fromLevel((level + levels).coerceAtMost(4))
    fun improved(levels: Int = 1): NotificationPriority = fromLevel((level - levels).coerceAtLeast(1))

    companion object {
        fun fromLevel(level: Int): NotificationPriority =
            entries.firstOrNull { it.level == level } ?: MEDIUM
    }
}
