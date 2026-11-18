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

/** Shared full-day rules. CLAIM(day-1440), CLAIM(min-window-60): [DayScheduleTest]. */
object DaySchedule {
    const val DAY_MINUTES = 1440
    const val MINIMUM_WINDOW_MINUTES = 60

    fun validate(schedule: List<NotificationSchedulePeriod>): List<ScheduleViolation> {
        if (schedule.isEmpty()) return listOf(ScheduleViolation.EMPTY)
        val violations = linkedSetOf<ScheduleViolation>()
        if (schedule.first().startMinuteOfDay != 0) violations += ScheduleViolation.DOES_NOT_START_AT_MIDNIGHT
        if (schedule.last().endMinuteOfDay != DAY_MINUTES) violations += ScheduleViolation.DOES_NOT_END_AT_MIDNIGHT
        schedule.forEachIndexed { index, period ->
            if (period.startHour !in 0..23 || period.startMinute !in 0..59 ||
                period.endHour !in 0..24 || period.endMinute !in 0..59 ||
                (period.endHour == 24 && period.endMinute != 0)) violations += ScheduleViolation.MALFORMED_TIME
            if (period.endMinuteOfDay - period.startMinuteOfDay < MINIMUM_WINDOW_MINUTES) violations += ScheduleViolation.WINDOW_TOO_SHORT
            if (period.budgetPoints < 0) violations += ScheduleViolation.NEGATIVE_BUDGET
            if (index > 0) {
                val previousEnd = schedule[index - 1].endMinuteOfDay
                if (period.startMinuteOfDay > previousEnd) violations += ScheduleViolation.GAP
                if (period.startMinuteOfDay < previousEnd) violations += ScheduleViolation.OVERLAP
            }
        }
        return violations.toList()
    }

    fun clampBoundary(schedule: List<NotificationSchedulePeriod>, boundaryIndex: Int, requestedMinute: Int): Int {
        require(boundaryIndex in 0 until schedule.lastIndex)
        return requestedMinute.coerceIn(schedule[boundaryIndex].startMinuteOfDay + MINIMUM_WINDOW_MINUTES,
            schedule[boundaryIndex + 1].endMinuteOfDay - MINIMUM_WINDOW_MINUTES)
    }

    fun split(schedule: List<NotificationSchedulePeriod>, index: Int): ScheduleSplitResult {
        require(validate(schedule).isEmpty())
        require(index in schedule.indices)
        val focused = schedule[index]
        val duration = focused.endMinuteOfDay - focused.startMinuteOfDay
        val changed = schedule.toMutableList()
        var start = focused.startMinuteOfDay
        var end = focused.endMinuteOfDay
        val midpoint: Int
        if (duration >= 2 * MINIMUM_WINDOW_MINUTES) {
            midpoint = start + duration / 2
        } else {
            val deficit = 2 * MINIMUM_WINDOW_MINUTES - duration
            val following = schedule.getOrNull(index + 1)
            val preceding = schedule.getOrNull(index - 1)
            when {
                following != null && following.endMinuteOfDay - following.startMinuteOfDay >= MINIMUM_WINDOW_MINUTES + deficit -> {
                    end += deficit
                    changed[index + 1] = following.copy(startHour = end / 60, startMinute = end % 60)
                }
                preceding != null && preceding.endMinuteOfDay - preceding.startMinuteOfDay >= MINIMUM_WINDOW_MINUTES + deficit -> {
                    start -= deficit
                    changed[index - 1] = preceding.copy(endHour = start / 60, endMinute = start % 60)
                }
                else -> return ScheduleSplitResult.NotEnoughTime
            }
            midpoint = start + MINIMUM_WINDOW_MINUTES
        }
        changed[index] = focused.copy(startHour = start / 60, startMinute = start % 60,
            endHour = midpoint / 60, endMinute = midpoint % 60)
        changed.add(index + 1, NotificationSchedulePeriod(midpoint / 60, midpoint % 60, end / 60, end % 60, 0))
        return ScheduleSplitResult.Split(changed.toList(), index, index + 1)
    }

    fun merge(schedule: List<NotificationSchedulePeriod>, boundaryIndex: Int): List<NotificationSchedulePeriod> {
        require(boundaryIndex in 0 until schedule.lastIndex)
        val left = schedule[boundaryIndex]
        val right = schedule[boundaryIndex + 1]
        val total = Math.addExact(left.budgetPoints, right.budgetPoints)
        return schedule.toMutableList().apply {
            this[boundaryIndex] = left.copy(endHour = right.endHour, endMinute = right.endMinute, budgetPoints = total)
            removeAt(boundaryIndex + 1)
        }.toList()
    }

    /** Repairs readable legacy short windows without losing configured points. Corrupt topology is refused. */
    fun repairLegacy(schedule: List<NotificationSchedulePeriod>): List<NotificationSchedulePeriod> {
        var repaired = migrateLegacyEndpoint(schedule)
        require(validate(repaired).all { it == ScheduleViolation.WINDOW_TOO_SHORT }) { "Saved schedule needs repair; original retained" }
        while (repaired.size > 1) {
            val short = repaired.indexOfFirst { it.endMinuteOfDay - it.startMinuteOfDay < MINIMUM_WINDOW_MINUTES }
            if (short < 0) break
            repaired = merge(repaired, if (short < repaired.lastIndex) short else short - 1)
        }
        require(validate(repaired).isEmpty()) { "Saved schedule needs repair; original retained" }
        return repaired
    }

    fun migrateLegacyEndpoint(schedule: List<NotificationSchedulePeriod>): List<NotificationSchedulePeriod> =
        if (schedule.lastOrNull()?.let { it.endHour == 23 && it.endMinute == 59 } == true)
            schedule.dropLast(1) + schedule.last().copy(endHour = 24, endMinute = 0)
        else schedule
}
