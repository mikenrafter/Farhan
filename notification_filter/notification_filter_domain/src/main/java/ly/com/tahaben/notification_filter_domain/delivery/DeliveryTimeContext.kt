package ly.com.tahaben.notification_filter_domain.delivery

data class DeliveryTimeContext(
    val boundaryIndex: Int,
    val boundariesPerDay: Int,
    val boundaryIntervalMinutes: Int,
    val minuteOfDay: Int,
    val calendarHour: Int,
    val dayOfYear: Int,
    val testingMode: Boolean = false,
    val localDay: String = "",
) {
    /** Minute-of-day used for schedule/budget lookups (3 min = 1 h in testing mode). */
    val scheduleMinuteOfDay: Int
        get() = if (testingMode) boundaryIndex * PRODUCTION_BOUNDARY_MINUTES else minuteOfDay

    /** Key for hourly passthrough rate limiting (one virtual hour per boundary in testing). */
    val passthroughWindowKey: Int
        get() = if (testingMode) boundaryIndex else calendarHour

    companion object {
        const val TESTING_BOUNDARY_MINUTES = 3
        const val PRODUCTION_BOUNDARY_MINUTES = 60

        fun from(
            calendarHour: Int,
            minuteOfDay: Int,
            dayOfYear: Int,
            testingMode: Boolean,
            localDay: String = "",
        ): DeliveryTimeContext {
            return if (testingMode) {
                DeliveryTimeContext(
                    boundaryIndex = minuteOfDay / TESTING_BOUNDARY_MINUTES,
                    boundariesPerDay = 24 * 60 / TESTING_BOUNDARY_MINUTES,
                    boundaryIntervalMinutes = TESTING_BOUNDARY_MINUTES,
                    minuteOfDay = minuteOfDay,
                    calendarHour = calendarHour,
                    dayOfYear = dayOfYear,
                    testingMode = true,
                    localDay = localDay,
                )
            } else {
                DeliveryTimeContext(
                    boundaryIndex = calendarHour,
                    boundariesPerDay = 24,
                    boundaryIntervalMinutes = PRODUCTION_BOUNDARY_MINUTES,
                    minuteOfDay = minuteOfDay,
                    calendarHour = calendarHour,
                    dayOfYear = dayOfYear,
                    testingMode = false,
                    localDay = localDay,
                )
            }
        }

        fun msToNextBoundary(
            minute: Int,
            second: Int,
            millis: Int,
            testingMode: Boolean,
            localDay: String = "",
        ): Long {
            return if (testingMode) {
                val minuteInSlot = minute % TESTING_BOUNDARY_MINUTES
                val secondsRemaining = (TESTING_BOUNDARY_MINUTES - minuteInSlot) * 60 - second
                (secondsRemaining * 1000L - millis).coerceAtLeast(5_000L)
            } else {
                val minutesRemaining = 60 - minute
                ((minutesRemaining * 60 - second) * 1000L - millis).coerceAtLeast(60_000L)
            }
        }
    }
}
