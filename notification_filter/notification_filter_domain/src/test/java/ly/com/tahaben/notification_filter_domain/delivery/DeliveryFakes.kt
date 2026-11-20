package ly.com.tahaben.notification_filter_domain.delivery

import kotlinx.coroutines.CompletableDeferred
import ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.testsupport.productionContext

/**
 * Port fakes for NotificationDeliveryCoordinator. The coordinator under test is the real
 * class; these fakes only stand in for Room, SharedPreferences and NotificationManager and
 * copy their semantics (REPLACE upsert, no-op UPDATE on a missing row, failing posts).
 */
class EventLog {
    val events = mutableListOf<String>()
    fun add(event: String) {
        events += event
    }
}

class FakeRowStore : DeliveryRowStore {
    val rows = linkedMapOf<String, NotificationItem>()

    /** When set, the next [pending] returns its already-computed snapshot only after this completes. */
    var pendingGate: CompletableDeferred<Unit>? = null

    override suspend fun upsert(item: NotificationItem) {
        rows[item.id] = item // Room REPLACE: every column, including isCleared and isDelivered.
    }

    override suspend fun find(id: String): NotificationItem? = rows[id]

    override suspend fun markCleared(id: String) {
        rows[id]?.let { rows[id] = it.copy(isCleared = true) }
    }

    override suspend fun markDelivered(id: String) {
        rows[id]?.let { rows[id] = it.copy(isDelivered = true) }
    }

    override suspend fun completeSnoozeReturn(id: String) {
        rows[id]?.let {
            rows[id] = it.copy(isDelivered = true, snoozeDeadline = null, snoozePriority = null, snoozeBootCount = null)
        }
    }

    override suspend fun liveSnoozes() =
        rows.values.filter { !it.isCleared && !it.isDelivered && it.snoozeDeadline != null }

    override suspend fun pending(maxPriorityLevel: Int): List<NotificationItem> {
        val snapshot = rows.values.filter { !it.isDelivered && !it.isCleared && !it.deliveryInvalidated && it.priority.level <= maxPriorityLevel }
        val gate = pendingGate
        pendingGate = null
        gate?.await()
        return snapshot
    }

    override suspend fun invalidateBefore(session: String, order: Long) {
        rows.replaceAll { _, row ->
            if (row.sourceSession == session && row.sourceEventOrder in 1..order && !row.isDelivered && !row.isCleared)
                row.copy(deliveryInvalidated = true, priorityReason = "Not proxied after intake overflow. Open the source app.") else row
        }
    }

    fun pendingIds(): List<String> = rows.values.filter { !it.isDelivered && !it.isCleared && !it.deliveryInvalidated }.map { it.id }
}

class FakeBudgetStore(var state: DeliveryBudgetState = DeliveryBudgetState()) : DeliveryBudgetStore {
    val entries = mutableListOf<BudgetSpendEntry>()
    override suspend fun read(): DeliveryBudgetState = state
    override suspend fun write(state: DeliveryBudgetState) {
        this.state = state
    }

    override suspend fun appendSpend(entry: BudgetSpendEntry) {
        entries += entry
    }
}

class FakePoster(private val log: EventLog) : ProxyNotificationPoster {
    enum class Mode { OK, FAIL, THROW }

    var mode = Mode.OK

    /** Every post waits here when set, so a test can hold a delivery in flight. */
    var gate: CompletableDeferred<Unit>? = null
    val posted = mutableListOf<NotificationItem>()

    override suspend fun post(item: NotificationItem): PostResult {
        gate?.await()
        return when (mode) {
            Mode.OK -> {
                posted += item
                log.add("post:${item.id}")
                PostResult.Posted
            }
            Mode.FAIL -> PostResult.Failed("blocked")
            Mode.THROW -> throw SecurityException("POST_NOTIFICATIONS revoked")
        }
    }
}

class FakeEnvironment(
    var schedule: List<NotificationSchedulePeriod>,
    var settings: Map<NotificationPriority, PriorityDeliverySetting>,
    var time: DeliveryTimeContext = productionContext(10),
    var preferences: Map<String, AppQueuePreference> = emptyMap(),
) : DeliveryEnvironment {
    var schedulesByDate: ly.com.tahaben.notification_filter_domain.schedule.ScheduleBook? = null

    override fun schedule() = schedule
    override fun scheduleAt(instant: java.time.Instant, zone: java.time.ZoneId) =
        schedulesByDate?.scheduleAt(instant, zone) ?: schedule
    override fun settings() = settings
    override fun timeContext() = time
    override fun queuePreferences() = preferences
}

class DeliveryHarness(
    schedule: List<NotificationSchedulePeriod>,
    settings: Map<NotificationPriority, PriorityDeliverySetting> = PriorityDeliverySetting.DEFAULTS,
) {
    val log = EventLog()
    val rows = FakeRowStore()
    val budget = FakeBudgetStore()
    val poster = FakePoster(log)
    val environment = FakeEnvironment(schedule, settings)
    val coordinator = NotificationDeliveryCoordinator(rows, budget, poster, environment)
}
