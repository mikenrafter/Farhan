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

    /** Set while a drag is in progress: every frame edits [current], and the whole drag becomes one undo step. */
    private var gestureStart: List<NotificationSchedulePeriod>? = null

    val isInGesture: Boolean get() = gestureStart != null

    val canUndo: Boolean get() = history.isNotEmpty() && gestureStart == null

    private fun accept(schedule: List<NotificationSchedulePeriod>) {
        if (schedule != current) {
            if (gestureStart == null) history.addLast(current)
            current = schedule
        }
    }

    /** Start grouping edits. Everything until [endGesture] is one undo step, however many frames it has. */
    fun beginGesture() {
        check(gestureStart == null) { "A gesture is already in progress" }
        gestureStart = current
    }

    /** Close the gesture: one undo step when it changed anything, none when the finger came back to the start. */
    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (current != start) history.addLast(start)
    }

    /** Abandon the gesture and return to where it began, leaving no undo step. */
    fun abortGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        current = start
    }

    /**
     * Sets one window's budget. False (nothing changed) for a negative amount; any non-negative amount is valid,
     * including amounts above the old slider's 30, because automation may supply them.
     */
    fun setBudget(index: Int, points: Int): Boolean {
        require(index in current.indices)
        if (points < 0) return false
        accept(current.toMutableList().also { it[index] = it[index].copy(budgetPoints = points) }.toList())
        return true
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

    /** Merges the two windows at [boundaryIndex], summing budgets. False (nothing changed) when the sum would overflow. */
    fun mergeAt(boundaryIndex: Int): Boolean {
        val merged = try { DaySchedule.merge(current, boundaryIndex) } catch (_: ArithmeticException) { return false }
        accept(merged)
        return true
    }

    /** Reverts the latest accepted edit. False when there is nothing to undo. */
    fun undo(): Boolean {
        if (history.isEmpty() || gestureStart != null) return false
        current = history.removeLast()
        return true
    }

    fun cancel() { current = saved; history.clear(); gestureStart = null }
}
