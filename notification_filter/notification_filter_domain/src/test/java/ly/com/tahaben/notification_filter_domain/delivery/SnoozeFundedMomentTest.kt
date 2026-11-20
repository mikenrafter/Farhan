package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.LOW
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleBook
import org.junit.Test

class SnoozeFundedMomentTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-20T22:30:00Z")

    // why: 23:00 is an hourly tick but there are zero points then under the shipped schedule.
    // proves: CLAIM(snooze-funded-tick)
    // catches: looking at cadence alone or choosing a period start that cannot pay the cost.
    @Test
    fun `default high skips twenty three hundred and takes the first funded tick`() {
        assertThat(SnoozeTiming.nextDeliverableMoment(HIGH, PriorityDeliverySetting.defaultFor(HIGH),
            ScheduleBook()::scheduleAt, now, zone, false)).isEqualTo(Instant.parse("2026-11-21T00:00:00Z"))
    }

    // why: the LOW midnight tick gets only 2 points for an 8-point item, for every day in the search.
    // proves: CLAIM(snooze-funded-tick)
    // catches: searching for any positive budget or deferring to a non-cadence period.
    @Test
    fun `default low has no funded moment but ignoring budget permits midnight`() {
        val setting = PriorityDeliverySetting.defaultFor(LOW)
        assertThat(SnoozeTiming.nextDeliverableMoment(LOW, setting, ScheduleBook()::scheduleAt, now, zone, false)).isNull()
        assertThat(SnoozeTiming.nextDeliverableMoment(LOW, setting.copy(budgetBehavior = BudgetBehavior.IGNORING),
            ScheduleBook()::scheduleAt, now, zone, false)).isEqualTo(Instant.parse("2026-11-21T00:00:00Z"))
    }

    // why: searching past 48 elapsed hours could hide the native notification for days.
    // proves: CLAIM(snooze-funded-tick)
    // catches: using a calendar-day count or stopping before an in-range override.
    @Test
    fun `funded search stops at forty eight elapsed hours`() {
        val funded = listOf(NotificationSchedulePeriod(0, 0, 24, 0, 8))
        fun momentForOverride(date: String) = SnoozeTiming.nextDeliverableMoment(
            LOW, PriorityDeliverySetting.defaultFor(LOW),
            ScheduleBook(dated = mapOf(LocalDate.parse(date) to funded))::scheduleAt, now, zone, false,
        )

        assertThat(momentForOverride("2026-11-22")).isEqualTo(Instant.parse("2026-11-22T00:00:00Z"))
        assertThat(momentForOverride("2026-11-23")).isNull()
    }

    // why: tomorrow's override can fund LOW even though today's repeating schedule cannot.
    // proves: CLAIM(snooze-funded-tick)
    // catches: holding today's schedule fixed over the whole lookahead or querying days after the first eligible tick.
    @Test
    fun `tomorrows dated override funds midnight`() {
        val book = ScheduleBook(dated = mapOf(LocalDate.parse("2026-11-21") to
            listOf(NotificationSchedulePeriod(0, 0, 24, 0, 8))))
        val consulted = mutableListOf<Instant>()
        val moment = SnoozeTiming.nextDeliverableMoment(
            LOW, PriorityDeliverySetting.defaultFor(LOW), { at, inZone ->
                consulted += at
                book.scheduleAt(at, inZone)
            }, now, zone, false,
        )
        val midnight = Instant.parse("2026-11-21T00:00:00Z")
        assertThat(moment).isEqualTo(midnight)
        assertThat(consulted).contains(midnight)
    }
}
