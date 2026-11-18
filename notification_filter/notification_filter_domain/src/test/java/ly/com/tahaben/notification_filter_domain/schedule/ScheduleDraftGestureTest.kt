package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test

/** Additions to the draft for the timeline editor: gesture grouping, budgets, overflow-safe merge. */
class ScheduleDraftGestureTest {
    private val saved = listOf(window(0, 540, 2), window(540, 720, 8), window(720, 1440, 0))

    // why: undo must not walk a drag back frame by frame, and must not be offered mid-drag.
    // catches: history push per accepted edit while a gesture is open.
    @Test
    fun `a gesture is one undo step`() {
        val draft = ScheduleDraft(saved)
        draft.beginGesture()
        listOf(730, 740, 750).forEach { draft.moveBoundary(1, it) }
        assertThat(draft.canUndo).isFalse()
        assertThat(draft.undo()).isFalse()
        draft.endGesture()

        assertThat(draft.undo()).isTrue()
        assertThat(draft.current).isEqualTo(saved)
        assertThat(draft.undo()).isFalse()
    }

    // why: a drag that returns to its start is not an edit; an aborted one leaves no trace.
    // catches: a recorded no-op step; abort that keeps the dragged value.
    @Test
    fun `a no-op gesture and an aborted gesture record nothing`() {
        val draft = ScheduleDraft(saved)
        draft.beginGesture(); draft.moveBoundary(1, 750); draft.moveBoundary(1, 720); draft.endGesture()
        assertThat(draft.canUndo).isFalse()

        draft.beginGesture(); draft.moveBoundary(1, 800); draft.abortGesture()
        assertThat(draft.current).isEqualTo(saved)
        assertThat(draft.canUndo).isFalse()
        assertThat(draft.isInGesture).isFalse()
    }

    // why: a draft merge must not throw or wrap when budgets overflow; it reports false and keeps the draft.
    // catches: ArithmeticException escaping to the UI thread.
    @Test
    fun `an overflowing merge reports false and changes nothing`() {
        val draft = ScheduleDraft(listOf(window(0, 720, Int.MAX_VALUE), window(720, 1440, 1)))

        assertThat(draft.mergeAt(0)).isFalse()
        assertThat(draft.isDirty).isFalse()
        assertThat(draft.canUndo).isFalse()
    }
}
