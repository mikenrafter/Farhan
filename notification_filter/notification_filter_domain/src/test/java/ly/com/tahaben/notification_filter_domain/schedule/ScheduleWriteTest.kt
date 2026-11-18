package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.testsupport.BudgetProbe
import ly.com.tahaben.notification_filter_domain.testsupport.probe
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test
import java.time.LocalDate

/**
 * The one schedule write path (ScheduleWriter) and what the owner then sees and gets:
 * remaining budget (GetCurrentBudgetStatus.statusAt) AND real delivery authorization (the batch policy),
 * both read from the committed snapshot. A setter that stores the schedule but leaves spend keyed to the old
 * window start makes both wrong, which is today's behavior.
 *
 * Clock: 10:00 local (minute 600), so the active window is the one containing 09:00-12:00.
 * Six points are spent in the window that started at 09:00. HIGH costs 2.
 *
 * proves: CLAIM(edit-preserves-spend)
 */
class ScheduleWriteTest {

    private val writer = { store: FakeScheduleBookStore -> ScheduleWriter(store, TEN_AM_DENVER) }

    private fun storeWith(budget: DeliveryBudgetState = SIX_SPENT_IN_NINE_AM_WINDOW) =
        FakeScheduleBookStore(ScheduleSnapshot(ScheduleBook(repeating = threeWindows(8)), budget))

    // why: the probe itself must move. Before any edit: 8 - 6 = 2 remaining = one HIGH.
    // catches: a probe that always reports zero or everything, which would make every test below vacuous.
    @Test
    fun `control - before any edit the window has 2 points left and authorizes one HIGH`() {
        assertThat(probe(storeWith().snapshot, TEN_AM_DENVER)).isEqualTo(BudgetProbe(remaining = 2, deliverable = 1))
    }

    // why: owner example: spent 6, new budget 10 means 4 remaining, not 10 and not 8-6.
    // catches: schedule saved without rebasing spend; spend reset by an edit. Hand math: 10 - 6 = 4 = two HIGH.
    @Test
    fun `raising the active window budget from 8 to 10 leaves 4 and authorizes two`() = runTest {
        val store = storeWith()

        val result = writer(store).write(ScheduleWrite(threeWindows(10)))

        assertThat(result).isEqualTo(ScheduleWriteResult.Accepted)
        assertThat(store.commits).hasSize(1)
        assertThat(store.snapshot.book.repeating).isEqualTo(threeWindows(10))
        assertThat(store.snapshot.budget.spentPoints).isEqualTo(6)
        assertThat(probe(store.snapshot, TEN_AM_DENVER)).isEqualTo(BudgetProbe(remaining = 4, deliverable = 2))
    }

    // why: moving the active window's start (09:00 -> 09:30) must not manufacture allowance. Today spend is keyed
    //      by the start minute, so the move reads as a new window with zero spent.
    // catches: spend keyed by editable boundary position. Hand math: budget stays 8, spent 6: 2 left = one HIGH.
    @Test
    fun `moving the active window's start keeps its spend`() = runTest {
        val store = storeWith()

        val result = writer(store).write(ScheduleWrite(threeWindows(8, middleStart = 570)))

        assertThat(result).isEqualTo(ScheduleWriteResult.Accepted)
        assertThat(store.snapshot.book.repeating).isEqualTo(threeWindows(8, middleStart = 570))
        assertThat(probe(store.snapshot, TEN_AM_DENVER)).isEqualTo(BudgetProbe(remaining = 2, deliverable = 1))
    }

    // why: merging the active window into its predecessor changes its start (540 -> 0) and sums budgets (2 + 8).
    // catches: merge that forgets spend, or loses a budget. Hand math: 10 - 6 = 4 remaining = two HIGH.
    @Test
    fun `merging the active window into its predecessor keeps spend and sums budgets`() = runTest {
        val store = storeWith()
        val merged = listOf(window(0, 720, 10), window(720, 1440, 0))

        writer(store).write(ScheduleWrite(merged))

        assertThat(store.snapshot.book.repeating).isEqualTo(merged)
        assertThat(probe(store.snapshot, TEN_AM_DENVER)).isEqualTo(BudgetProbe(remaining = 4, deliverable = 2))
    }

    // why: natural passage into a later window may start fresh (owner decision). This control keeps the fix from
    //      over-reaching: spend from the 09:00 window must not follow the clock into the 12:00 window.
    // catches: a fix that carries spend across windows forever instead of only across edits.
    // Hand math: at 12:00 the active window is [720,1440) budget 8, spent there 0: remaining 8 = four HIGH.
    @Test
    fun `control - passage into the next window starts that window fresh`() {
        val next = ScheduleSnapshot(
            ScheduleBook(repeating = listOf(window(0, 540, 2), window(540, 720, 8), window(720, 1440, 8))),
            SIX_SPENT_IN_NINE_AM_WINDOW,
        )
        val noon = java.time.Clock.fixed(java.time.Instant.parse("2026-11-18T19:00:00Z"), TEN_AM_DENVER.zone)

        assertThat(probe(next, noon)).isEqualTo(BudgetProbe(remaining = 8, deliverable = 4))
    }

    // why: a malformed write leaves schedule and spend exactly as they were, with no commit at all.
    // catches: partial write before validation; accepting a 59-minute window. [540,599) is 59 minutes.
    @Test
    fun `an invalid write is rejected with its reasons and commits nothing`() = runTest {
        val store = storeWith()
        val before = store.snapshot
        val tooShort = listOf(window(0, 540, 2), window(540, 599, 8), window(599, 1440, 0))

        val result = writer(store).write(ScheduleWrite(tooShort))

        assertThat(result).isEqualTo(
            ScheduleWriteResult.Rejected(ScheduleWriteError.INVALID_SCHEDULE, listOf(ScheduleViolation.WINDOW_TOO_SHORT))
        )
        assertThat(store.commits).isEmpty()
        assertThat(store.snapshot).isEqualTo(before)
    }

    // why: a dated write names its date; without one there is no scope to apply it to.
    // catches: silently treating a dated write without a date as repeating.
    @Test
    fun `a dated write without a date is rejected`() = runTest {
        val store = storeWith()

        val result = writer(store).write(ScheduleWrite(threeWindows(10), ScheduleMode.DATED, date = null))

        assertThat(result).isEqualTo(ScheduleWriteResult.Rejected(ScheduleWriteError.DATE_REQUIRED))
        assertThat(store.commits).isEmpty()
    }

    // why: Apply goes through the common write path exactly once. Sixty drag frames edit the draft only; nothing
    //      is committed or delivered per frame, and the committed schedule is the draft's final clamped state.
    // catches: committing on each pointer move; Apply writing something other than the draft.
    @Test
    fun `sixty drag frames commit nothing and Apply commits the draft once`() = runTest {
        val store = storeWith()
        val draft = ScheduleDraft(store.snapshot.book.repeating)

        repeat(60) { frame -> draft.moveBoundary(1, 720 + frame) }
        assertThat(draft.current).isNotEqualTo(draft.saved)
        assertThat(store.commits).isEmpty()

        writer(store).write(ScheduleWrite(draft.current))

        assertThat(store.commits).hasSize(1)
        assertThat(store.snapshot.book.repeating).isEqualTo(draft.current)
        assertThat(store.snapshot.book.repeating[1].endMinuteOfDay).isEqualTo(779)
        assertThat(store.snapshot.budget.spentPoints).isEqualTo(6)
    }

    // why: the date a dated write targets is explicit; a repeating write never invents one.
    // catches: a dated write that also overwrites the repeating schedule.
    @Test
    fun `a dated write is stored only under its own date`() = runTest {
        val store = storeWith()
        val day = LocalDate.of(2026, 11, 19)

        writer(store).write(ScheduleWrite(threeWindows(10), ScheduleMode.DATED, day))

        assertThat(store.snapshot.book.dated.keys).containsExactly(day)
        assertThat(store.snapshot.book.repeating).isEqualTo(threeWindows(8))
    }
}
