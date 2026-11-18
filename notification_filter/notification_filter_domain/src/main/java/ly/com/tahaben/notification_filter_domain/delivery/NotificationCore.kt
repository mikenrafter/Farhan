package ly.com.tahaben.notification_filter_domain.delivery

import ly.com.tahaben.notification_filter_domain.schedule.*
import ly.com.tahaben.notification_filter_domain.use_cases.GetCurrentBudgetStatus
import java.time.Clock

/** Production composition root. All mutation entry points share this owner, including UI/automation writes. */
class NotificationCore(
    private val store: ScheduleBookStore,
    rows: DeliveryRowStore,
    budget: DeliveryBudgetStore,
    poster: ProxyNotificationPoster,
    private val environment: DeliveryEnvironment,
    private val clock: Clock,
    private val resetBudget: suspend () -> Unit,
) {
    private val gate = DeliveryGate()
    val writer = ScheduleWriter(store, clock, gate) { environment.timeContext().scheduleMinuteOfDay }
    val coordinator = NotificationDeliveryCoordinator(rows, budget, poster, environment, gate)
    suspend fun clearBudget() = gate.run { resetBudget() }
    suspend fun snapshot(): ScheduleSnapshot = store.read()
    suspend fun status(): ly.com.tahaben.notification_filter_domain.model.CurrentBudgetStatus {
        val snapshot = store.read()
        val time = environment.timeContext()
        return GetCurrentBudgetStatus.statusAt(snapshot.book.scheduleAt(clock.instant(), clock.zone), snapshot.budget,
            time.scheduleMinuteOfDay, time.localDay)
    }
}
