package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test

/** The editor's reversible draft. Windows are (startMinute, endMinute, budget). proves: CLAIM(draft-reversible) */
class ScheduleDraftTest {

    private val saved = listOf(window(0, 540, 2), window(540, 720, 8), window(720, 1440, 0))

    // why: dragging edits a draft; the saved schedule must not move until Apply, and Cancel returns to it.
    // catches: draft that edits the saved list in place; moveBoundary without the 60-minute clamp.
    // Boundary 1 (720) range 540+60=600 .. 1440-60=1380; asking for 1400 yields 1380.
    @Test
    fun `a drag moves the draft only and cancel restores the saved schedule`() {
        val draft = ScheduleDraft(saved)

        val applied = draft.moveBoundary(1, 1400)

        assertThat(applied).isEqualTo(1380)
        assertThat(draft.current)
            .isEqualTo(listOf(window(0, 540, 2), window(540, 1380, 8), window(1380, 1440, 0)))
        assertThat(draft.saved).isEqualTo(saved)
        assertThat(draft.isDirty).isTrue()

        draft.cancel()

        assertThat(draft.current).isEqualTo(saved)
        assertThat(draft.isDirty).isFalse()
    }

    // why: edits are reversible one by one.
    // catches: undo that jumps to saved, or a no-op undo. Split of the 180-minute window then a move, undone twice.
    @Test
    fun `undo steps back one edit at a time`() {
        val draft = ScheduleDraft(saved)
        draft.split(1)
        val afterSplit = draft.current
        draft.moveBoundary(0, 500)
        val afterMove = draft.current
        assertThat(afterSplit).isNotEqualTo(saved)
        assertThat(afterMove).isNotEqualTo(afterSplit)

        assertThat(draft.undo()).isTrue()
        assertThat(draft.current).isEqualTo(afterSplit)
        assertThat(draft.undo()).isTrue()
        assertThat(draft.current).isEqualTo(saved)
        assertThat(draft.undo()).isFalse()
    }

    // why: a refused split (insufficient time) must be a no-op, not a partial mutation and not an undo step.
    // catches: borrowing applied before the feasibility check; a recorded undo step for a failed edit.
    @Test
    fun `a refused split leaves the draft and its history untouched`() {
        val tight = listOf(window(0, 480, 1), window(480, 555, 2), window(555, 645, 8), window(645, 720, 4), window(720, 1440, 9))
        val draft = ScheduleDraft(tight)

        val result = draft.split(2)

        assertThat(result).isEqualTo(ScheduleSplitResult.NotEnoughTime)
        assertThat(draft.current).isEqualTo(tight)
        assertThat(draft.isDirty).isFalse()
        assertThat(draft.undo()).isFalse()
    }
}
