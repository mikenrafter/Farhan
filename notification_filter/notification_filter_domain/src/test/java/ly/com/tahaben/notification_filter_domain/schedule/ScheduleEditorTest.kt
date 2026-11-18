@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test

/**
 * The timeline editor's behaviour without a widget. The Compose screen only forwards pointer events and draws
 * [EditorState]; [ScheduleEditor.runDrag] is the loop it runs, driven here with virtual time.
 * proves: CLAIM(edit-draft-only), CLAIM(select-then-drag), CLAIM(drag-one-undo-step), CLAIM(hold-timer)
 */
class ScheduleEditorTest {
    private val saved = listOf(window(0, 540, 2), window(540, 720, 8), window(720, 1440, 0))

    private fun sample(timeMillis: Long, px: Float, minute: Float) = DragSample(timeMillis, px, minute, minutesPerPx = 1f)

    // why: selecting a zone or a boundary must never move time; the owner selects first, then edits.
    // catches: a select that snaps or rounds the boundary; a select that marks the draft dirty.
    @Test
    fun `selecting changes no time and no history`() {
        val editor = ScheduleEditor(saved)

        editor.selectBoundary(1)
        editor.selectZone(0)

        assertThat(editor.current).isEqualTo(saved)
        assertThat(editor.state.isDirty).isFalse()
        assertThat(editor.state.canUndo).isFalse()
    }

    // why: only the focused boundary is draggable. A drag aimed at another boundary, or started with nothing or a
    //      zone selected, must not move anything.
    // catches: a drag that hit-tests all handles; a drag that ignores selection.
    @Test
    fun `only the selected boundary can start a drag`() {
        val editor = ScheduleEditor(saved)

        assertThat(editor.beginDrag(1, 0)).isFalse()
        editor.selectZone(1)
        assertThat(editor.beginDrag(1, 0)).isFalse()
        editor.selectBoundary(0)
        assertThat(editor.beginDrag(1, 0)).isFalse()
        editor.dragTo(sample(0, 10f, 800f))

        assertThat(editor.current).isEqualTo(saved)
        assertThat(editor.state.isDragging).isFalse()
    }

    // why: a whole drag is one undo step, however many pointer events it had; nothing persists mid-drag.
    // catches: one undo step per event (Undo would walk the drag back a pixel at a time).
    @Test
    fun `a drag of many frames is one undo step`() = runTest {
        val editor = ScheduleEditor(saved)
        editor.selectBoundary(1)
        val now = { testScheduler.currentTime }
        assertThat(editor.beginDrag(1, now())).isTrue()
        val samples = Channel<DragSample>(Channel.UNLIMITED)
        val loop = launch { editor.runDrag(samples, now) }

        for (frame in 1..30) { samples.send(sample(now(), frame * 5f, 720f + frame * 5)); advanceTimeBy(16); runCurrent() }
        assertThat(editor.state.canUndo).isFalse()
        samples.close(); loop.join()
        editor.endDrag()

        assertThat(editor.current[1].endMinuteOfDay).isEqualTo(870)
        assertThat(editor.undo()).isTrue()
        assertThat(editor.current).isEqualTo(saved)
        assertThat(editor.undo()).isFalse()
    }

    // why: a drag that ends where it began, and a cancelled pointer, leave no undo step and the saved schedule.
    // catches: an empty undo step; a cancel that keeps the half-dragged boundary.
    @Test
    fun `a no-op drag and an aborted drag leave no undo step`() {
        val editor = ScheduleEditor(saved)
        editor.selectBoundary(1)

        editor.beginDrag(1, 0); editor.dragTo(sample(0, 5f, 750f)); editor.dragTo(sample(10, 0f, 720f)); editor.endDrag()
        assertThat(editor.state.canUndo).isFalse()
        assertThat(editor.state.isDirty).isFalse()

        editor.beginDrag(1, 20); editor.dragTo(sample(20, 5f, 800f)); editor.abortDrag()
        assertThat(editor.current).isEqualTo(saved)
        assertThat(editor.state.canUndo).isFalse()
        assertThat(editor.state.isDragging).isFalse()
    }

    // why: the hold timer must start at the last movement and restart on movement, through the real loop.
    //      After 600 ms still, steps are 1 minute at quarter speed from where the boundary was (no jump).
    // catches: a timer from touch-down (fires at 600 ms despite movement at 599); a timer that never restarts;
    //      hold() works but the loop never calls it; a fine drag that jumps to the finger's time (900).
    // Hand math: moves at t=0 and t=599, so the hold fires at t=1199, not 600 or 1198. Fine from 745: +8 px * 0.25 = +2.
    @Test
    fun `holding still 600 ms after the last movement switches to one-minute steps without a jump`() = runTest {
        val editor = ScheduleEditor(saved, stillnessTolerancePx = 4f)
        editor.selectBoundary(1)
        val now = { testScheduler.currentTime }
        editor.beginDrag(1, now())
        val samples = Channel<DragSample>(Channel.UNLIMITED)
        val loop = launch { editor.runDrag(samples, now) }

        samples.send(sample(now(), 100f, 735f)); runCurrent()
        assertThat(editor.current[1].endMinuteOfDay).isEqualTo(735)
        advanceTimeBy(599); runCurrent()
        assertThat(editor.state.isFineDrag).isFalse()
        samples.send(sample(now(), 140f, 745f)); runCurrent()
        advanceTimeBy(599); runCurrent()
        assertThat(editor.state.isFineDrag).isFalse()
        advanceTimeBy(1); runCurrent()

        assertThat(editor.state.isFineDrag).isTrue()
        assertThat(editor.current[1].endMinuteOfDay).isEqualTo(745)
        samples.send(sample(now(), 140f, 900f)); runCurrent()
        assertThat(editor.current[1].endMinuteOfDay).isEqualTo(745)
        samples.send(sample(now(), 148f, 900f)); runCurrent()
        assertThat(editor.current[1].endMinuteOfDay).isEqualTo(747)
        samples.close(); loop.join(); editor.endDrag()
        assertThat(editor.undo()).isTrue()
        assertThat(editor.current).isEqualTo(saved)
    }

    // why: Cancel discards every edit and writes nothing; Apply hands the draft to the common writer once and the
    //      spend already incurred survives (6 spent in the 09:00 window).
    // catches: a draft that edits storage directly; Apply that resets spend; undo history surviving Apply.
    @Test
    fun `cancel writes nothing and apply commits the draft once keeping spend`() = runTest {
        val store = FakeScheduleBookStore(ScheduleSnapshot(ScheduleBook(repeating = saved), SIX_SPENT_IN_NINE_AM_WINDOW))
        val editor = ScheduleEditor(saved)
        editor.selectBoundary(0)
        editor.nudgeSelectedBoundary(30)
        editor.cancel()
        assertThat(editor.current).isEqualTo(saved)
        assertThat(editor.state.isDirty).isFalse()
        assertThat(store.commits).isEmpty()

        editor.selectBoundary(0)
        editor.nudgeSelectedBoundary(30)
        val result = ScheduleWriter(store, TEN_AM_DENVER).write(ScheduleWrite(editor.current))
        editor.markApplied()

        assertThat(result).isEqualTo(ScheduleWriteResult.Accepted)
        assertThat(store.commits).hasSize(1)
        assertThat(store.snapshot.book.repeating[0].endMinuteOfDay).isEqualTo(570)
        assertThat(store.snapshot.budget.spentPoints).isEqualTo(6)
        assertThat(editor.state.isDirty).isFalse()
        assertThat(editor.state.canUndo).isFalse()
    }

    // why: exact controls are the non-drag path; they obey the same one-hour clamp.
    // catches: a nudge that skips the clamp. Boundary 1 may reach 1380 (1440 - 60).
    @Test
    fun `an exact nudge is clamped and is one step`() {
        val editor = ScheduleEditor(saved)
        editor.selectBoundary(1)

        assertThat(editor.nudgeSelectedBoundary(10_000)).isEqualTo(1380)
        assertThat(editor.undo()).isTrue()
        assertThat(editor.current).isEqualTo(saved)
        editor.selectZone(0)
        assertThat(editor.nudgeSelectedBoundary(5)).isNull()
    }

    // why: a budget can be any non-negative number, including above the old slider's 30 (automation supplies them).
    // catches: a clamp to 30; a negative budget accepted.
    @Test
    fun `a budget above the old slider maximum is accepted and a negative one is refused`() {
        val editor = ScheduleEditor(saved)

        assertThat(editor.setBudget(1, 5_000)).isTrue()
        assertThat(editor.current[1].budgetPoints).isEqualTo(5_000)
        assertThat(editor.setBudget(1, -1)).isFalse()
        assertThat(editor.current[1].budgetPoints).isEqualTo(5_000)
        assertThat(editor.undo()).isTrue()
        assertThat(editor.current).isEqualTo(saved)
    }

    // why: the owner's failure case. When neither neighbour can lend an hour the schedule is unchanged, no undo step
    //      is recorded, and a modal reason is raised until dismissed.
    // catches: partial borrowing before failing; a failed split in the undo history; a silent failure.
    @Test
    fun `a split with no time to borrow raises the modal and changes nothing`() {
        val tight = listOf(window(0, 480, 1), window(480, 555, 2), window(555, 645, 8), window(645, 720, 4), window(720, 1440, 9))
        val editor = ScheduleEditor(tight)
        editor.selectZone(2)

        assertThat(editor.splitSelectedZone()).isEqualTo(ScheduleSplitResult.NotEnoughTime)

        assertThat(editor.state.notice).isEqualTo(EditorNotice.SPLIT_NEEDS_MORE_TIME)
        assertThat(editor.current).isEqualTo(tight)
        assertThat(editor.state.canUndo).isFalse()
        editor.dismissNotice()
        assertThat(editor.state.notice).isNull()
    }

    // why: split keeps focus on the focused window, gives the new window budget 0 and keeps the focused budget.
    // catches: a new window that copies the budget; focus jumping to the new window.
    // Hand math: [540,720) is 180 min, midpoint 540 + 90 = 630.
    @Test
    fun `splitting a long window keeps its budget and adds a zero-budget window after it`() {
        val editor = ScheduleEditor(saved)
        editor.selectZone(1)

        editor.splitSelectedZone()

        assertThat(editor.current).isEqualTo(listOf(window(0, 540, 2), window(540, 630, 8), window(630, 720, 0), window(720, 1440, 0)))
        assertThat(editor.state.selection).isEqualTo(EditorSelection.Zone(1))
        assertThat(editor.undo()).isTrue()
        assertThat(editor.current).isEqualTo(saved)
    }

    // why: removing a boundary merges two windows and keeps their configured points. A sum too large for an Int
    //      must be refused with a reason, not crash and not wrap.
    // catches: merge that drops a budget; ArithmeticException escaping; overflow wrapping negative.
    @Test
    fun `merge sums budgets and refuses an overflowing sum`() {
        val editor = ScheduleEditor(saved)
        editor.selectBoundary(0)
        assertThat(editor.mergeSelectedBoundary()).isTrue()
        assertThat(editor.current).isEqualTo(listOf(window(0, 720, 10), window(720, 1440, 0)))
        assertThat(editor.state.selection).isEqualTo(EditorSelection.Zone(0))

        val huge = listOf(window(0, 720, Int.MAX_VALUE), window(720, 1440, 1))
        val big = ScheduleEditor(huge)
        big.selectBoundary(0)
        assertThat(big.mergeSelectedBoundary()).isFalse()
        assertThat(big.current).isEqualTo(huge)
        assertThat(big.state.notice).isEqualTo(EditorNotice.MERGE_BUDGET_TOO_LARGE)
    }

    // why: zoom and pan change the view only; the schedule still covers the whole day. Reset returns the full day.
    // catches: zoom that edits or re-validates the schedule; pan that leaves the day.
    @Test
    fun `zoom and pan move the view and never the schedule`() {
        val editor = ScheduleEditor(saved)
        editor.selectBoundary(1)

        editor.zoomIn()
        assertThat(editor.state.view).isEqualTo(TimelineView(360f, 1080f))
        editor.pan(100_000f)
        assertThat(editor.state.view.endMinute).isEqualTo(1440f)
        editor.resetZoom()

        assertThat(editor.state.view).isEqualTo(TimelineView.FullDay)
        assertThat(editor.current).isEqualTo(saved)
        assertThat(editor.state.isDirty).isFalse()
    }
}
