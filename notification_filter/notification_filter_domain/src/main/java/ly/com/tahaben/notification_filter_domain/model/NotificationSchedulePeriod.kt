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
        const val DAY_END_HOUR = 23
        const val DAY_END_MINUTE = 59
        const val DAY_END_MINUTE_OF_DAY = DAY_END_HOUR * 60 + DAY_END_MINUTE

        val DEFAULT_SCHEDULE = listOf(
            NotificationSchedulePeriod(0, 0, 9, 0, 2),
            NotificationSchedulePeriod(9, 0, 12, 0, 8),
            NotificationSchedulePeriod(12, 0, 15, 0, 4),
            NotificationSchedulePeriod(15, 0, 18, 0, 12),
            NotificationSchedulePeriod(18, 0, 22, 0, 20),
            NotificationSchedulePeriod(22, 0, 23, 59, 0),
        )

        fun isValid(schedule: List<NotificationSchedulePeriod>): Boolean {
            if (schedule.isEmpty()) return false
            val first = schedule.first()
            if (first.startMinuteOfDay != DAY_START_MINUTE) return false
            val last = schedule.last()
            if (last.endHour != DAY_END_HOUR || last.endMinute != DAY_END_MINUTE) return false
            for (index in schedule.indices) {
                val period = schedule[index]
                if (period.startMinuteOfDay >= period.endMinuteOfDay) return false
                if (index > 0) {
                    val previous = schedule[index - 1]
                    if (period.startMinuteOfDay != previous.endMinuteOfDay) return false
                }
            }
            return true
        }

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
            val previousStart = schedule[boundaryIndex].startMinuteOfDay
            val nextEnd = schedule[boundaryIndex + 1].endMinuteOfDay
            if (newEndMinute <= previousStart || newEndMinute >= nextEnd) return null

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
