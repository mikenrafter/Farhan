package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleViolation.DOES_NOT_END_AT_MIDNIGHT
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleViolation.DOES_NOT_START_AT_MIDNIGHT
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleViolation.EMPTY
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleViolation.GAP
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleViolation.MALFORMED_TIME
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleViolation.NEGATIVE_BUDGET
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleViolation.OVERLAP
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleViolation.WINDOW_TOO_SHORT
import ly.com.tahaben.notification_filter_domain.testsupport.lengthMinutes
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test

/**
 * Pure day-schedule rules: the [0, 1440) day, the 60-minute minimum, migration of the old 23:59 endpoint,
 * boundary clamping, split and merge. Windows are written as (startMinute, endMinute, budget).
 *
 * Existing controls that must stay green: NotificationSchedulePeriodTest (default schedule valid; boundary
 * 9:00-12:00 to 11:30 accepted; 8:00 rejected). No existing expectation is changed. At green the default
 * schedule's last window moves from 22:00-23:59 to 22:00-24:00, which that test accepts.
 *
 * proves: CLAIM(day-1440), CLAIM(min-window-60), CLAIM(split-owner-example)
 */
class DayScheduleTest {

    // ── 1440 endpoint and validation ──

    // why: the day is [0, 1440); 23:59 omits a minute. Today's validator demands 23:59 and rejects 24:00.
    // catches: leaving the validator and DEFAULT_SCHEDULE on 23:59.
    @Test
    fun `a day that ends at 24h00 is valid and 23h59 is not`() {
        assertThat(NotificationSchedulePeriod.isValid(listOf(window(0, 540, 2), window(540, 1440, 0)))).isTrue()
        assertThat(NotificationSchedulePeriod.isValid(listOf(window(0, 540, 2), window(540, 1439, 0)))).isFalse()
    }

    // why: the shipped default must itself cover all 1440 minutes.
    // catches: default schedule still ending 23:59 (1439 minutes covered).
    @Test
    fun `the default schedule covers all 1440 minutes`() {
        val defaults = NotificationSchedulePeriod.DEFAULT_SCHEDULE

        assertThat(defaults.last().endMinuteOfDay).isEqualTo(1440)
        assertThat(defaults.sumOf { it.lengthMinutes }).isEqualTo(1440)
    }

    // why: every entry point shares one validator, so each rule must name the reason it failed.
    // catches: one catch-all boolean; a rule missing (59-minute window, negative budget, gap vs overlap).
    // Each case has exactly one fault; expected sets are by construction.
    @Test
    fun `validate names exactly the broken rule`() {
        val cases: Map<String, Pair<List<NotificationSchedulePeriod>, List<ScheduleViolation>>> = mapOf(
            "valid full day" to (listOf(window(0, 540, 2), window(540, 1440, 0)) to emptyList()),
            "empty" to (emptyList<NotificationSchedulePeriod>() to listOf(EMPTY)),
            "starts at 00:05" to (listOf(window(5, 540, 2), window(540, 1440, 0)) to listOf(DOES_NOT_START_AT_MIDNIGHT)),
            "ends at 23:00" to (listOf(window(0, 540, 2), window(540, 1380, 0)) to listOf(DOES_NOT_END_AT_MIDNIGHT)),
            "legacy 23:59 end" to (listOf(window(0, 540, 2), window(540, 1439, 0)) to listOf(DOES_NOT_END_AT_MIDNIGHT)),
            "5 minute gap" to (listOf(window(0, 540, 2), window(545, 1440, 0)) to listOf(GAP)),
            "60 minute overlap" to (listOf(window(0, 600, 2), window(540, 1440, 0)) to listOf(OVERLAP)),
            "30 minute window" to (listOf(window(0, 30, 2), window(30, 1440, 0)) to listOf(WINDOW_TOO_SHORT)),
            "59 minute window" to (listOf(window(0, 540, 2), window(540, 599, 1), window(599, 1440, 0)) to listOf(WINDOW_TOO_SHORT)),
            "negative budget" to (listOf(window(0, 540, -1), window(540, 1440, 0)) to listOf(NEGATIVE_BUDGET)),
        )

        for ((name, case) in cases) {
            val (schedule, expected) = case
            assertWithMessage(name).that(DaySchedule.validate(schedule)).containsExactlyElementsIn(expected)
        }
    }

    // why: a minute field of 60 is malformed input, not "next hour".
    // catches: validating only the derived minute-of-day.
    @Test
    fun `a minute field outside 0 to 59 is malformed`() {
        val malformed = listOf(NotificationSchedulePeriod(0, 0, 9, 60, 2), window(600, 1440, 0))

        assertThat(DaySchedule.validate(malformed)).contains(MALFORMED_TIME)
    }

    // ── migration ──

    // why: saved schedules end at 23:59; they must become 24:00 with every budget kept.
    // catches: migration that drops or zeroes budgets, or rebuilds from DEFAULT_SCHEDULE.
    @Test
    fun `migration moves the 23h59 end to 24h00 and keeps every budget`() {
        val legacy = listOf(
            window(0, 540, 2), window(540, 720, 8), window(720, 900, 4),
            window(900, 1080, 12), window(1080, 1320, 20), NotificationSchedulePeriod(22, 0, 23, 59, 3),
        )

        val migrated = DaySchedule.migrateLegacyEndpoint(legacy)

        assertThat(migrated).isEqualTo(legacy.dropLast(1) + window(1320, 1440, 3))
    }

    // why: migration runs on every read; a current schedule must be left alone and twice must equal once.
    // catches: shifting an already-migrated end, or touching a middle window.
    @Test
    fun `migration leaves a current schedule alone and is idempotent`() {
        val legacy = listOf(window(0, 540, 2), NotificationSchedulePeriod(9, 0, 23, 59, 5))
        val current = listOf(window(0, 540, 2), window(540, 1440, 5))

        assertThat(DaySchedule.migrateLegacyEndpoint(current)).isEqualTo(current)
        assertThat(DaySchedule.migrateLegacyEndpoint(DaySchedule.migrateLegacyEndpoint(legacy)))
            .isEqualTo(DaySchedule.migrateLegacyEndpoint(legacy))
        assertThat(DaySchedule.migrateLegacyEndpoint(legacy)).isEqualTo(current)
    }

    // ── boundary clamp ──

    // why: dragging may never leave either neighbor under 60 minutes, and never reaches the day edge.
    // catches: no clamp; clamp of 0 instead of 60; exclusive bounds.
    // Windows [0,540) [540,720) [720,900) [900,1440). Boundary 1 (720): range 540+60=600 .. 900-60=840.
    //      Boundary 0 (540): range 0+60=60 .. 720-60=660.
    @Test
    fun `a boundary clamps so both neighbors keep 60 minutes`() {
        val s = listOf(window(0, 540, 1), window(540, 720, 2), window(720, 900, 3), window(900, 1440, 4))

        assertThat(DaySchedule.clampBoundary(s, 1, 570)).isEqualTo(600)
        assertThat(DaySchedule.clampBoundary(s, 1, 880)).isEqualTo(840)
        assertThat(DaySchedule.clampBoundary(s, 1, 700)).isEqualTo(700)
        assertThat(DaySchedule.clampBoundary(s, 1, 600)).isEqualTo(600)
        assertThat(DaySchedule.clampBoundary(s, 1, 840)).isEqualTo(840)
        assertThat(DaySchedule.clampBoundary(s, 0, -30)).isEqualTo(60)
    }

    // why: the existing adjustBoundary only checks order, so it lets a window shrink below the hour.
    // catches: leaving the pre-pilot ordering-only check. Default boundary 0 is 09:00 between 00:00-09:00 and 09:00-12:00.
    @Test
    fun `adjustBoundary refuses to leave a window under 60 minutes`() {
        val defaults = NotificationSchedulePeriod.DEFAULT_SCHEDULE

        assertThat(NotificationSchedulePeriod.adjustBoundary(defaults, 0, 1, 0)).isNotNull()
        assertThat(NotificationSchedulePeriod.adjustBoundary(defaults, 0, 0, 59)).isNull()
    }

    // ── split ──

    private fun split(schedule: List<NotificationSchedulePeriod>, index: Int): ScheduleSplitResult.Split {
        val result = DaySchedule.split(schedule, index)
        assertThat(result).isInstanceOf(ScheduleSplitResult.Split::class.java)
        return result as ScheduleSplitResult.Split
    }

    // why: owner default 1: a window of at least 120 minutes divides its own time, new window after, outer edges kept.
    // catches: borrowing from a neighbor when none is needed; new window before the focused one; budget copied.
    // Focused [600,720) is exactly 120, so the only valid split is 60 + 60 at 660.
    @Test
    fun `a window of 120 minutes divides in place with a zero-budget new window after it`() {
        val before = listOf(window(0, 600, 1), window(600, 720, 8), window(720, 1440, 0))

        val result = split(before, 1)

        assertThat(result.schedule).isEqualTo(
            listOf(window(0, 600, 1), window(600, 660, 8), window(660, 720, 0), window(720, 1440, 0))
        )
        assertThat(result.focusedIndex).isEqualTo(1)
        assertThat(result.newIndex).isEqualTo(2)
    }

    // why: a longer window splits into two valid windows; where exactly is unspecified (flagged in the report),
    //      so only the contract is pinned: outer edges kept, adjacent, both >= 60, focused keeps its budget.
    // catches: split that leaves a window under an hour or changes outer boundaries.
    @Test
    fun `a 180 minute window splits into two valid adjacent windows`() {
        val before = listOf(window(0, 540, 2), window(540, 720, 8), window(720, 1440, 0))

        val result = split(before, 1)
        val focused = result.schedule[1]
        val added = result.schedule[2]

        assertThat(result.schedule).hasSize(4)
        assertThat(result.schedule[0]).isEqualTo(before[0])
        assertThat(result.schedule[3]).isEqualTo(before[2])
        assertThat(focused.startMinuteOfDay).isEqualTo(540)
        assertThat(focused.budgetPoints).isEqualTo(8)
        assertThat(added.startMinuteOfDay).isEqualTo(focused.endMinuteOfDay)
        assertThat(added.endMinuteOfDay).isEqualTo(720)
        assertThat(added.budgetPoints).isEqualTo(0)
        assertThat(focused.lengthMinutes).isAtLeast(60)
        assertThat(added.lengthMinutes).isAtLeast(60)
    }

    // why: owner example, focused FIRST: two adjacent 90-minute windows become three one-hour windows. The deficit
    //      comes from the FOLLOWING window even though the preceding one is huge and could also lend.
    // catches: borrowing from the preceding window first; shrinking the huge preceding window.
    // Hand math: focused 90 -> 60, new 60 (needs 120, deficit 30); following 90 -> 60. 540-600, 600-660, 660-720.
    @Test
    fun `focused first of two 90 minute windows borrows from the following window`() {
        val before = listOf(window(0, 540, 2), window(540, 630, 8), window(630, 720, 4), window(720, 1440, 0))

        val result = split(before, 1)

        assertThat(result.schedule).isEqualTo(
            listOf(
                window(0, 540, 2), window(540, 600, 8), window(600, 660, 0),
                window(660, 720, 4), window(720, 1440, 0),
            )
        )
        assertThat(result.focusedIndex).isEqualTo(1)
        assertThat(result.newIndex).isEqualTo(2)
    }

    // why: owner example, focused LATTER: the following window is exactly 60 and cannot lend, so the preceding 90 lends 30.
    // catches: giving up when the following window cannot lend; touching the 60-minute following window.
    // Hand math: preceding 540-630 -> 540-600; focused 630-720 becomes 600-660; new 660-720. The following 720-780 is unchanged.
    @Test
    fun `focused latter of two 90 minute windows borrows from the preceding window`() {
        val before = listOf(
            window(0, 540, 2), window(540, 630, 8), window(630, 720, 4),
            window(720, 780, 1), window(780, 1440, 0),
        )

        val result = split(before, 2)

        assertThat(result.schedule).isEqualTo(
            listOf(
                window(0, 540, 2), window(540, 600, 8), window(600, 660, 4),
                window(660, 720, 0), window(720, 780, 1), window(780, 1440, 0),
            )
        )
        assertThat(result.focusedIndex).isEqualTo(2)
        assertThat(result.newIndex).isEqualTo(3)
    }

    // why: the last window has no following neighbor (never across midnight); it borrows from the preceding one.
    // catches: wrapping to the first window; refusing because "following" is missing.
    // Hand math: preceding 1260-1350 -> 1260-1320; focused 1350-1440 becomes 1320-1380; new 1380-1440.
    @Test
    fun `the last window borrows from its preceding window and not across midnight`() {
        val before = listOf(window(0, 1260, 2), window(1260, 1350, 8), window(1350, 1440, 4))

        val result = split(before, 2)

        assertThat(result.schedule).isEqualTo(
            listOf(window(0, 1260, 2), window(1260, 1320, 8), window(1320, 1380, 4), window(1380, 1440, 0))
        )
    }

    // why: no single adjacent window can retain 60 after lending the 30-minute deficit (each has 75, can lend 15),
    //      and a far window is huge. The result must be NotEnoughTime, not a partial borrow from both, not a far borrow.
    // catches: splitting the deficit across both neighbors; searching beyond adjacent windows.
    @Test
    fun `no single adjacent window can lend so the split is refused`() {
        val before = listOf(window(0, 480, 1), window(480, 555, 2), window(555, 645, 8), window(645, 720, 4), window(720, 1440, 9))

        assertThat(DaySchedule.split(before, 2)).isEqualTo(ScheduleSplitResult.NotEnoughTime)
    }

    // why: the first window of the day is adjacent to midnight, not to the last window. Same for the last.
    // catches: modular neighbor indexing. Last focused 1350-1440 (90), preceding 60 cannot lend, first window is huge.
    @Test
    fun `the last window never borrows from the first window`() {
        val before = listOf(window(0, 600, 9), window(600, 1290, 1), window(1290, 1350, 2), window(1350, 1440, 4))

        assertThat(DaySchedule.split(before, 3)).isEqualTo(ScheduleSplitResult.NotEnoughTime)
    }

    // ── merge ──

    // why: owner decision: removing a boundary merges two zones and preserves total configured points.
    // catches: keeping only one budget; dropping the merged window's span.
    @Test
    fun `merging a boundary sums both budgets over the joined span`() {
        val before = listOf(window(0, 540, 2), window(540, 630, 8), window(630, 720, 4), window(720, 1440, 0))

        assertThat(DaySchedule.merge(before, 1))
            .isEqualTo(listOf(window(0, 540, 2), window(540, 720, 12), window(720, 1440, 0)))
    }
}
