package ly.com.tahaben.notification_filter_domain.model

data class NotificationSchedulePeriod(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val budgetPoints: Int
) {
    companion object {
        val DEFAULT_SCHEDULE = listOf(
            NotificationSchedulePeriod(0, 0, 9, 0, 2),
            NotificationSchedulePeriod(9, 0, 12, 0, 8),
            NotificationSchedulePeriod(12, 0, 15, 0, 4),
            NotificationSchedulePeriod(15, 0, 18, 0, 12),
            NotificationSchedulePeriod(18, 0, 22, 0, 20),
            NotificationSchedulePeriod(22, 0, 23, 59, 0),
        )
    }
}
