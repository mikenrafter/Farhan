package ly.com.tahaben.notification_filter_domain.model

data class NotificationSchedulePeriod(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val budgetPoints: Int
) {
    val startMinuteOfDay: Int get() = startHour * 60 + startMinute
    val endMinuteOfDay: Int get() = endHour * 60 + endMinute

    companion object {
        const val DAY_START_MINUTE = 0
        const val DAY_END_HOUR = 24
        const val DAY_END_MINUTE = 0
        const val DAY_END_MINUTE_OF_DAY = DAY_END_HOUR * 60 + DAY_END_MINUTE

        val DEFAULT_SCHEDULE = listOf(
            NotificationSchedulePeriod(0, 0, 9, 0, 2),
            NotificationSchedulePeriod(9, 0, 12, 0, 8),
            NotificationSchedulePeriod(12, 0, 15, 0, 4),
            NotificationSchedulePeriod(15, 0, 18, 0, 12),
            NotificationSchedulePeriod(18, 0, 22, 0, 20),
            NotificationSchedulePeriod(22, 0, 24, 0, 0),
        )

        fun isValid(schedule: List<NotificationSchedulePeriod>): Boolean =
            ly.com.tahaben.notification_filter_domain.schedule.DaySchedule.validate(schedule).isEmpty()

        /**
         * Adjusts the shared boundary between [boundaryIndex] and [boundaryIndex + 1].
         * [boundaryIndex] must be in `0 until schedule.lastIndex`.
         * Returns null when the new time would create gaps or inverted periods.
         */
        fun adjustBoundary(
            schedule: List<NotificationSchedulePeriod>,
            boundaryIndex: Int,
            newHour: Int,
            newMinute: Int,
        ): List<NotificationSchedulePeriod>? {
            if (boundaryIndex !in 0 until schedule.lastIndex) return null
            val newEndMinute = newHour * 60 + newMinute
            if (newHour !in 0..23 || newMinute !in 0..59) return null
            if (ly.com.tahaben.notification_filter_domain.schedule.DaySchedule.clampBoundary(schedule, boundaryIndex, newEndMinute) != newEndMinute) return null

            return schedule.mapIndexed { index, period ->
                when (index) {
                    boundaryIndex -> period.copy(endHour = newHour, endMinute = newMinute)
                    boundaryIndex + 1 -> period.copy(startHour = newHour, startMinute = newMinute)
                    else -> period
                }
            }
        }
    }
}
