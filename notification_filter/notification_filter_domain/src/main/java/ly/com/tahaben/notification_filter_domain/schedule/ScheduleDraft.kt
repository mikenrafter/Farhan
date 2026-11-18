package ly.com.tahaben.notification_filter_domain.schedule

import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod

/**
 * RED-PHASE STUB. The editor's reversible draft: edits touch [current] only; [saved] is
 * never mutated; Apply hands [current] to the common write path, Cancel restores [saved].
 */
class ScheduleDraft(val saved: List<NotificationSchedulePeriod>) {
    var current: List<NotificationSchedulePeriod> = saved
        private set

    val isDirty: Boolean get() = current != saved

    /** Returns the minute actually applied after clamping. */
    fun moveBoundary(boundaryIndex: Int, minute: Int): Int = minute

    fun split(index: Int): ScheduleSplitResult = ScheduleSplitResult.Split(current, index, index)

    fun mergeAt(boundaryIndex: Int) = Unit

    /** Reverts the latest accepted edit. False when there is nothing to undo. */
    fun undo(): Boolean = false

    fun cancel() = Unit
}
