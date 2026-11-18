package ly.com.tahaben.notification_filter_domain.schedule

import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod

/**
 * The editor's reversible draft: edits touch [current] only; [saved] is
 * never mutated; Apply hands [current] to the common write path, Cancel restores [saved].
 */
class ScheduleDraft(val saved: List<NotificationSchedulePeriod>) {
    var current: List<NotificationSchedulePeriod> = saved
        private set

    val isDirty: Boolean get() = current != saved

    private val history = java.util.ArrayDeque<List<NotificationSchedulePeriod>>()

    private fun accept(schedule: List<NotificationSchedulePeriod>) {
        if (schedule != current) {
            history.addLast(current)
            current = schedule
        }
    }

    /** Returns the minute actually applied after clamping. */
    fun moveBoundary(boundaryIndex: Int, minute: Int): Int {
        val clamped = DaySchedule.clampBoundary(current, boundaryIndex, minute)
        accept(NotificationSchedulePeriod.adjustBoundary(current, boundaryIndex, clamped / 60, clamped % 60)!!)
        return clamped
    }

    fun split(index: Int): ScheduleSplitResult = DaySchedule.split(current, index).also {
        if (it is ScheduleSplitResult.Split) accept(it.schedule)
    }

    fun mergeAt(boundaryIndex: Int) = accept(DaySchedule.merge(current, boundaryIndex))

    /** Reverts the latest accepted edit. False when there is nothing to undo. */
    fun undo(): Boolean {
        if (history.isEmpty()) return false
        current = history.removeLast()
        return true
    }

    fun cancel() { current = saved; history.clear() }
}
