package ly.com.tahaben.notification_filter_domain.schedule

import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod

/** Every way a day schedule can be wrong. [DaySchedule.validate] reports all that apply. */
enum class ScheduleViolation {
    EMPTY,
    DOES_NOT_START_AT_MIDNIGHT,
    DOES_NOT_END_AT_MIDNIGHT,
    MALFORMED_TIME,
    GAP,
    OVERLAP,
    WINDOW_TOO_SHORT,
    NEGATIVE_BUDGET,
}

sealed interface ScheduleSplitResult {
    /** [schedule] has the new window at [newIndex], immediately after the focused one at [focusedIndex]. */
    data class Split(
        val schedule: List<NotificationSchedulePeriod>,
        val focusedIndex: Int,
        val newIndex: Int,
    ) : ScheduleSplitResult

    /** Neither the focused window nor an adjacent one can give enough time. Nothing changed. */
    data object NotEnoughTime : ScheduleSplitResult
}

/**
 * RED-PHASE STUB. Pure day-schedule rules for the notification pilot (see
 * research/notification-pilot-contract.md, "Schedule model" and "Timeline editor").
 * Every function below is a placeholder that does no real work; green replaces the bodies.
 */
object DaySchedule {
    const val DAY_MINUTES = 1440
    const val MINIMUM_WINDOW_MINUTES = 60

    fun validate(schedule: List<NotificationSchedulePeriod>): List<ScheduleViolation> = emptyList()

    /** The minute a drag may actually reach for boundary [boundaryIndex] (between windows i and i+1). */
    fun clampBoundary(
        schedule: List<NotificationSchedulePeriod>,
        boundaryIndex: Int,
        requestedMinute: Int,
    ): Int = requestedMinute

    fun split(schedule: List<NotificationSchedulePeriod>, index: Int): ScheduleSplitResult =
        ScheduleSplitResult.Split(schedule, index, index)

    /** Removes the boundary between [boundaryIndex] and the next window, summing their budgets. */
    fun merge(schedule: List<NotificationSchedulePeriod>, boundaryIndex: Int): List<NotificationSchedulePeriod> =
        schedule

    /** Rewrites a stored 23:59 final endpoint as 24:00, keeping every budget. */
    fun migrateLegacyEndpoint(schedule: List<NotificationSchedulePeriod>): List<NotificationSchedulePeriod> =
        schedule
}
