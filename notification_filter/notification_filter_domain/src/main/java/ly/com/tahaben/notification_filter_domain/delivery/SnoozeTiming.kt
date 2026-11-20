package ly.com.tahaben.notification_filter_domain.delivery

import java.time.Instant
import java.time.ZoneId
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
    ): Long = 0L

    // CLAIM(snooze-funded-tick): look up the schedule that applies on each candidate date [Snooze pilot spec V2].
    fun nextDeliverableMoment(
        priority: NotificationPriority,
        setting: PriorityDeliverySetting,
        scheduleAt: (Instant, ZoneId) -> List<NotificationSchedulePeriod>,
        now: Instant,
        zone: ZoneId,
        testingMode: Boolean,
    ): Instant? = null
}
