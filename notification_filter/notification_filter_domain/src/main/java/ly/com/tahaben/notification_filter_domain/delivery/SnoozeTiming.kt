package ly.com.tahaben.notification_filter_domain.delivery

import java.time.Instant
import java.time.ZoneId
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting

object SnoozeTiming {
    // CLAIM(snooze-next-tick): the delay is positive and lands on the first cadence tick [Snooze pilot spec D4, I7].
    fun delayUntilNextMoment(
        priority: NotificationPriority,
        setting: PriorityDeliverySetting,
        now: Instant,
        zone: ZoneId,
        testingMode: Boolean,
    ): Long = 0L
}
