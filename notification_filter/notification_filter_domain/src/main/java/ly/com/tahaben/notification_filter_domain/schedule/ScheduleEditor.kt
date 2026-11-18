package ly.com.tahaben.notification_filter_domain.schedule

import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod

/** What the owner has picked on the timeline. Picking never changes the schedule. */
sealed interface EditorSelection {
    data object None : EditorSelection
    /** A window: its budget is editable and it can be split. */
    data class Zone(val index: Int) : EditorSelection
    /** The shared edge between window [index] and [index] + 1: the only thing a drag can move. */
    data class Boundary(val index: Int) : EditorSelection
}

/** A refusal the owner must read. Each shows as a modal with instructions. */
enum class EditorNotice {
    /** Neither the focused window nor an adjacent one has an hour to spare. */
    SPLIT_NEEDS_MORE_TIME,
    /** The two budgets together exceed the largest whole number a schedule can hold. */
    MERGE_BUDGET_TOO_LARGE,
}

data class EditorState(
    val schedule: List<NotificationSchedulePeriod>,
    val selection: EditorSelection,
    val view: TimelineView,
    val isDirty: Boolean,
    val canUndo: Boolean,
    val isDragging: Boolean,
    /** True once a held finger switched the drag to one-minute steps. */
    val isFineDrag: Boolean,
    val notice: EditorNotice?,
)

/**
 * The timeline editor's whole behaviour without any widget: selection, drag, zoom, split/merge, budget edits and
 * the reversible draft behind Apply/Cancel. The Compose screen draws [state] and forwards input.
 *
 * CLAIM(edit-draft-only): nothing here reaches storage. Apply hands [current] to [ScheduleWriter]; Cancel returns
 * to the saved schedule [ScheduleEditorTest].
 * CLAIM(select-then-drag): a drag starts only on the selected boundary; selecting changes no time [ScheduleEditorTest].
 * CLAIM(drag-one-undo-step): a whole drag, however many frames, is one undo step [ScheduleEditorTest].
 *
 * Errors: edits that cannot apply leave the schedule and the undo history unchanged and, when the owner needs to
 * know why, set [EditorState.notice]. Invalid indices are programming errors and throw.
 */
class ScheduleEditor(
    saved: List<NotificationSchedulePeriod>,
    var stillnessTolerancePx: Float = 0f,
    /** Called with the new [state] after every change, on the caller's thread. The screen mirrors it into Compose state. */
    private val onState: (EditorState) -> Unit = {},
) {
    private var draft = ScheduleDraft(saved)
    private var gesture: BoundaryGesture? = null
    private var gestureBoundary = -1
    private var selection: EditorSelection = EditorSelection.None
    private var view = TimelineView.FullDay
    private var notice: EditorNotice? = null

    var state: EditorState = snapshot()
        private set

    /** The schedule Apply would write. */
    val current: List<NotificationSchedulePeriod> get() = draft.current

    private fun snapshot() = EditorState(draft.current, selection, view, draft.isDirty, draft.canUndo,
        gesture != null, gesture?.isFine == true, notice)

    private fun publish() { state = snapshot(); onState(state) }

    // ── selection ───────────────────────────────────────────────────────────

    fun selectZone(index: Int) {
        require(index in draft.current.indices)
        if (gesture != null) return
        selection = EditorSelection.Zone(index); publish()
    }

    fun selectBoundary(index: Int) {
        require(index in 0 until draft.current.lastIndex)
        if (gesture != null) return
        selection = EditorSelection.Boundary(index); publish()
    }

    fun clearSelection() {
        if (gesture != null) return
        selection = EditorSelection.None; publish()
    }

    // ── drag ────────────────────────────────────────────────────────────────

    /**
     * Starts a drag on [boundaryIndex] at event time [nowMillis]. Refused (false) unless that boundary is the
     * selected one: an unselected boundary never moves.
     */
    fun beginDrag(boundaryIndex: Int, nowMillis: Long): Boolean {
        if (gesture != null || selection != EditorSelection.Boundary(boundaryIndex)) return false
        val schedule = draft.current
        draft.beginGesture()
        gestureBoundary = boundaryIndex
        gesture = BoundaryGesture(schedule[boundaryIndex].endMinuteOfDay, nowMillis,
            { DaySchedule.clampBoundary(schedule, boundaryIndex, it) }, stillnessTolerancePx)
        publish()
        return true
    }

    /** Applies one finger sample to the draft. No-op when no drag is active. */
    fun dragTo(sample: DragSample) {
        val active = gesture ?: return
        draft.moveBoundary(gestureBoundary, active.move(sample))
        publish()
    }

    /** Time passed without movement. True when the drag just turned fine. */
    fun holdTick(nowMillis: Long): Boolean {
        val fired = gesture?.tick(nowMillis) == true
        if (fired) publish()
        return fired
    }

    /**
     * The drag loop the screen runs between finger-down and finger-up: waits for the next [samples] value, but no
     * longer than the gesture's remaining hold time, so a still finger turns the drag fine without any polling.
     * Returns when [samples] closes. [nowMillis] must be on the same clock as the samples' event times.
     * CLAIM(hold-timer) [ScheduleEditorTest].
     */
    suspend fun runDrag(samples: kotlinx.coroutines.channels.ReceiveChannel<DragSample>, nowMillis: () -> Long) {
        while (true) {
            val wait = gesture?.millisUntilHold(nowMillis())
            val received = if (wait == null) samples.receiveCatching()
                else kotlinx.coroutines.withTimeoutOrNull(wait) { samples.receiveCatching() }
            if (received == null) { holdTick(nowMillis()); continue }
            dragTo(received.getOrNull() ?: return)
        }
    }

    /** Finger lifted: the drag becomes a single undo step. */
    fun endDrag() {
        if (gesture == null) return
        draft.endGesture()
        gesture = null; gestureBoundary = -1
        publish()
    }

    /** Pointer cancelled by the system: back to where the drag began. */
    fun abortDrag() {
        if (gesture == null) return
        draft.abortGesture()
        gesture = null; gestureBoundary = -1
        publish()
    }

    // ── exact controls ──────────────────────────────────────────────────────

    /** Moves boundary [boundaryIndex] to [minute], clamped to keep both windows an hour. One undo step. */
    fun setBoundaryMinute(boundaryIndex: Int, minute: Int): Int? {
        if (gesture != null) return null
        val applied = draft.moveBoundary(boundaryIndex, minute)
        publish()
        return applied
    }

    /** Moves the selected boundary by [deltaMinutes]. Null when no boundary is selected. */
    fun nudgeSelectedBoundary(deltaMinutes: Int): Int? {
        val selected = selection as? EditorSelection.Boundary ?: return null
        return setBoundaryMinute(selected.index, draft.current[selected.index].endMinuteOfDay + deltaMinutes)
    }

    /** False (unchanged) for a negative amount. Any non-negative amount is accepted. */
    fun setBudget(zoneIndex: Int, points: Int): Boolean {
        if (gesture != null) return false
        val accepted = draft.setBudget(zoneIndex, points)
        publish()
        return accepted
    }

    // ── split and merge ─────────────────────────────────────────────────────

    /** Splits the selected window. Sets [EditorNotice.SPLIT_NEEDS_MORE_TIME] and changes nothing when it cannot. */
    fun splitSelectedZone(): ScheduleSplitResult? {
        val selected = selection as? EditorSelection.Zone ?: return null
        if (gesture != null) return null
        val result = draft.split(selected.index)
        if (result is ScheduleSplitResult.NotEnoughTime) notice = EditorNotice.SPLIT_NEEDS_MORE_TIME
        publish()
        return result
    }

    /** Removes the selected boundary, merging its two windows and summing their budgets. */
    fun mergeSelectedBoundary(): Boolean {
        val selected = selection as? EditorSelection.Boundary ?: return false
        if (gesture != null) return false
        val merged = draft.mergeAt(selected.index)
        if (merged) selection = EditorSelection.Zone(selected.index) else notice = EditorNotice.MERGE_BUDGET_TOO_LARGE
        publish()
        return merged
    }

    fun dismissNotice() { notice = null; publish() }

    // ── undo, cancel, apply ─────────────────────────────────────────────────

    fun undo(): Boolean {
        val undone = draft.undo()
        if (undone) keepSelectionInRange()
        publish()
        return undone
    }

    /** Cancel: every edit is discarded and the schedule shown is the saved one. */
    fun cancel() {
        draft.cancel(); gesture = null; gestureBoundary = -1
        selection = EditorSelection.None; view = TimelineView.FullDay; notice = null
        publish()
    }

    /** After the writer accepted [current]: it is now the saved schedule and the undo history starts over. */
    fun markApplied() {
        gesture = null; gestureBoundary = -1
        draft = ScheduleDraft(draft.current)
        publish()
    }

    /** Replaces the base with a schedule loaded from storage (another entry point wrote). Discards the draft. */
    fun reload(saved: List<NotificationSchedulePeriod>) {
        draft = ScheduleDraft(saved); gesture = null; gestureBoundary = -1
        keepSelectionInRange(); publish()
    }

    private fun keepSelectionInRange() {
        val last = draft.current.lastIndex
        selection = when (val s = selection) {
            is EditorSelection.Zone -> if (s.index <= last) s else EditorSelection.None
            is EditorSelection.Boundary -> if (s.index < last) s else EditorSelection.None
            EditorSelection.None -> s
        }
    }

    // ── zoom and pan (view only) ────────────────────────────────────────────

    fun zoomIn() { view = view.zoomedIn(focusMinute()); publish() }
    fun zoomOut() { view = view.zoomedOut(); publish() }
    fun resetZoom() { view = TimelineView.FullDay; publish() }
    fun pan(deltaMinutes: Float) { view = view.panned(deltaMinutes); publish() }

    private fun focusMinute(): Float = when (val s = selection) {
        is EditorSelection.Boundary -> draft.current[s.index].endMinuteOfDay.toFloat()
        is EditorSelection.Zone -> draft.current[s.index].let { (it.startMinuteOfDay + it.endMinuteOfDay) / 2f }
        EditorSelection.None -> (view.startMinute + view.endMinute) / 2
    }
}
