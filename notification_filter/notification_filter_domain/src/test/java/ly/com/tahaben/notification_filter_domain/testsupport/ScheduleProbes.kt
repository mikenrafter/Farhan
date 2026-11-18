package ly.com.tahaben.notification_filter_domain.testsupport

import ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryPolicy
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleSnapshot
import ly.com.tahaben.notification_filter_domain.use_cases.GetCurrentBudgetStatus
import java.time.Clock

/**
 * What the owner sees and what delivery allows, for one snapshot, at the clock's wall time:
 * [remaining] from the budget status the dashboard shows, [deliverable] from the real batch
 * policy given six HIGH candidates (cost 2 each). Both must agree after any accepted write.
 */
data class BudgetProbe(val remaining: Int, val deliverable: Int)

fun probe(snapshot: ScheduleSnapshot, clock: Clock): BudgetProbe {
    val local = clock.instant().atZone(clock.zone)
    val minuteOfDay = local.hour * 60 + local.minute
    val schedule = snapshot.book.scheduleAt(clock.instant(), clock.zone)
    val candidates = (1..6).map { item("probe-$it", timestamp = it.toLong(), priority = NotificationPriority.HIGH) }
    val (delivered, _) = NotificationDeliveryPolicy.selectForBatchDelivery(
        candidates = candidates,
        settings = PriorityDeliverySetting.DEFAULTS,
        schedule = schedule,
        timeContext = productionContext(local.hour, local.minute),
        budget = snapshot.budget,
    )
    return BudgetProbe(
        remaining = GetCurrentBudgetStatus.statusAt(schedule, snapshot.budget, minuteOfDay).remainingPoints,
        deliverable = delivered.size,
    )
}
