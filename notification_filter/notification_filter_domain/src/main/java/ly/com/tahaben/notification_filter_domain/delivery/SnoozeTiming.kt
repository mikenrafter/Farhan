package ly.com.tahaben.notification_filter_domain.delivery

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting

enum class BatchCadence { EVERY_HOUR, EVERY_OTHER_HOUR, EVERY_THIRD_HOUR, ONCE_DAILY }

object SnoozeTiming {
    // CLAIM(snooze-next-tick): the delay is positive and lands on the first cadence tick [Snooze pilot spec D4, I7].
    fun delayUntilNextMoment(
        cadence: BatchCadence,
        now: Instant,
        zone: ZoneId,
        testingMode: Boolean,
    ): Long {
        val tick = ticks(DeliveryCadence.valueOf(cadence.name), now, zone, testingMode).first()
        return Duration.between(now, tick).toMillis().coerceAtLeast(1)
    }

    // CLAIM(snooze-funded-tick): look up the schedule that applies on each candidate date [Snooze pilot spec V2].
    fun nextDeliverableMoment(
        priority: NotificationPriority,
        setting: PriorityDeliverySetting,
        scheduleAt: (Instant, ZoneId) -> List<NotificationSchedulePeriod>,
        now: Instant,
        zone: ZoneId,
        testingMode: Boolean,
        accepts: (Instant, DeliveryTimeContext, NotificationSchedulePeriod?) -> Boolean = { _, _, _ -> true },
    ): Instant? {
        if (setting.cadence == DeliveryCadence.IMMEDIATELY) return null
        return ticks(setting.cadence, now, zone, testingMode).firstOrNull { tick ->
            val time = DeliveryClock.current(testingMode, Clock.fixed(tick, zone))
            val period = NotificationDeliveryPolicy.findActivePeriod(scheduleAt(tick, zone), time.scheduleMinuteOfDay)
            (setting.budgetBehavior == BudgetBehavior.IGNORING || (period?.budgetPoints ?: 0) >= priority.pointCost) &&
                accepts(tick, time, period)
        }
    }

    private fun ticks(cadence: DeliveryCadence, now: Instant, zone: ZoneId, testingMode: Boolean) =
        generateSequence(now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)) { it.plusSeconds(60) }
            .takeWhile { it <= now.plus(48, ChronoUnit.HOURS) }
            .filter { tick ->
                val time = DeliveryClock.current(testingMode, Clock.fixed(tick, zone))
                time.minuteOfDay % time.boundaryIntervalMinutes == 0 &&
                    NotificationDeliveryPolicy.isBatchTickForCadence(cadence, time)
            }
}
