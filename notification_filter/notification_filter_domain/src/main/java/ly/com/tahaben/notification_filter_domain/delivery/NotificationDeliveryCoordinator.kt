package ly.com.tahaben.notification_filter_domain.delivery

import ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting

enum class NotificationRemovalReason {
    /** The source app cancelled its notification: explicit, clears a queued row. */
    SOURCE_APP_CANCELLED,

    /** The user swiped or tapped away a Farhan proxy: explicit. */
    USER_DISMISSED,

    /** Farhan's own interception or snooze removal: not a cancellation. */
    FARHAN_INTERCEPTED,

    /** The user opened the source app: not a cancellation by itself. */
    SOURCE_APP_OPENED,
}

sealed interface PostResult {
    data object Posted : PostResult
    data class Failed(val reason: String) : PostResult
}

sealed interface DeliveryOutcome {
    val id: String
    data class Delivered(override val id: String) : DeliveryOutcome
    /** Stored and waiting: cadence not due, or no budget. */
    data class Deferred(override val id: String) : DeliveryOutcome
    /** An explicit cancellation won: nothing posted, nothing spent. */
    data class Cleared(override val id: String) : DeliveryOutcome
    data class PostFailed(override val id: String, val reason: String) : DeliveryOutcome
}

/** Row storage with Room semantics: [upsert] replaces every column, [markCleared] on a missing id does nothing. */
interface DeliveryRowStore {
    suspend fun upsert(item: NotificationItem)
    suspend fun find(id: String): NotificationItem?
    suspend fun markCleared(id: String)
    suspend fun markDelivered(id: String)
    suspend fun pending(maxPriorityLevel: Int): List<NotificationItem>
}

interface DeliveryBudgetStore {
    suspend fun read(): DeliveryBudgetState
    suspend fun write(state: DeliveryBudgetState)
    suspend fun appendSpend(entry: BudgetSpendEntry)
}

/** Android-bound in production (NotificationManager). Must report failure, not hide it. */
interface ProxyNotificationPoster {
    suspend fun post(item: NotificationItem): PostResult
}

/** Reads of settings that can change between calls. */
interface DeliveryEnvironment {
    fun schedule(): List<NotificationSchedulePeriod>
    fun settings(): Map<NotificationPriority, PriorityDeliverySetting>
    fun timeContext(): DeliveryTimeContext
    fun queuePreferences(): Map<String, AppQueuePreference>
}

/**
 * RED-PHASE STUB. The single owner of delivery decisions: it serializes arrival, explicit
 * removal and batch delivery, rechecks pending state at authorization, spends only after a
 * successful post. Not yet wired into NotificationDeliveryEngine / NotificationService
 * (platform wiring is not covered by JVM tests).
 */
class NotificationDeliveryCoordinator(
    private val rows: DeliveryRowStore,
    private val budget: DeliveryBudgetStore,
    private val poster: ProxyNotificationPoster,
    private val environment: DeliveryEnvironment,
) {
    suspend fun onArrival(item: NotificationItem, effectivePriority: NotificationPriority): DeliveryOutcome =
        DeliveryOutcome.Deferred(item.id)

    suspend fun onRemoved(id: String, reason: NotificationRemovalReason, atMillis: Long) = Unit

    suspend fun onBatchTick(): List<DeliveryOutcome> = emptyList()
}
