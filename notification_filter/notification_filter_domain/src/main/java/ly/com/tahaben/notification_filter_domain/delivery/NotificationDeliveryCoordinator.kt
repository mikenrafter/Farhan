package ly.com.tahaben.notification_filter_domain.delivery

import ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.sound.AlertIdentity
import ly.com.tahaben.notification_filter_domain.sound.ElectionInput
import ly.com.tahaben.notification_filter_domain.sound.ElectionPolicy
import ly.com.tahaben.notification_filter_domain.sound.ProxyAlert
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
    INBOX_CLEARED,
}

/** The poster reports this reason before publishing, so a normal-channel attempt is safe. */
const val BREAKTHROUGH_BYPASS_LOST = "breakthrough_bypass_lost"

sealed interface PostResult {
    data object Posted : PostResult
    data class Failed(val reason: String) : PostResult
}

sealed interface DeliveryOutcome {
    val id: String
    data class Delivered(override val id: String) : DeliveryOutcome
    /** Stored and waiting: cadence not due, or no budget. */
    data class Deferred(override val id: String) : DeliveryOutcome
    /** Ambiguous callback history was evicted. The persisted row remains available in the inbox. */
    data class Invalidated(override val id: String) : DeliveryOutcome
    /** An explicit cancellation won: nothing posted, nothing spent. */
    data class Cleared(override val id: String) : DeliveryOutcome
    data class PostFailed(override val id: String, val reason: String) : DeliveryOutcome
    /** External effect succeeded; storage will be retried before another mutation, without reposting. */
    data class CommitPending(override val id: String) : DeliveryOutcome
}

/** Row storage with Room semantics: [upsert] replaces every column, [markCleared] on a missing id does nothing. */
interface DeliveryRowStore {
    suspend fun upsert(item: NotificationItem)
    suspend fun find(id: String): NotificationItem?
    suspend fun markCleared(id: String)
    suspend fun markDelivered(id: String)
    suspend fun pending(maxPriorityLevel: Int): List<NotificationItem>
    suspend fun invalidateBefore(session: String, order: Long)
    suspend fun nextHead(after: NotificationItem): NotificationItem? = null
}

interface DeliveryBudgetStore {
    suspend fun read(): DeliveryBudgetState
    suspend fun write(state: DeliveryBudgetState)
    suspend fun appendSpend(entry: BudgetSpendEntry)
}

/** Android-bound in production (NotificationManager). Must report failure, not hide it. */
interface ProxyNotificationPoster {
    suspend fun post(item: NotificationItem): PostResult
    suspend fun post(item: NotificationItem, alert: ProxyAlert): PostResult =
        post(item)
    suspend fun refresh(item: NotificationItem): PostResult = PostResult.Posted
}

/** Reads of settings that can change between calls. */
interface DeliveryEnvironment {
    fun schedule(): List<NotificationSchedulePeriod>
    fun settings(): Map<NotificationPriority, PriorityDeliverySetting>
    fun timeContext(): DeliveryTimeContext
    fun queuePreferences(): Map<String, AppQueuePreference>
    fun activeSourceKeys(): Set<String> = emptySet()
    fun soundElection(item: NotificationItem): ElectionInput? = null
}

/**
 * One owner for authorization and spend. CLAIM(serialized-budget), CLAIM(post-failure-no-spend):
 * [NotificationDeliveryCoordinatorTest]. The injected gate is shared with ScheduleWriter.
 * Posting is an external effect; durable adapters must document their crash boundary.
 */
class NotificationDeliveryCoordinator(
    private val rows: DeliveryRowStore,
    private val budget: DeliveryBudgetStore,
    private val poster: ProxyNotificationPoster,
    private val environment: DeliveryEnvironment,
    private val gate: DeliveryGate = DeliveryGate(),
    private val eventCapacity: Int = MAX_EVENT_KEYS,
) {
    private data class Event(val order: Long, val removed: Boolean, val lastRemoval: Long, val accepted: Long, val bypassed: Boolean = false, val appliedRemoval: Long = 0)
    private val events = linkedMapOf<String, Event>()
    private val legacyRemovals = linkedMapOf<String, Long>()
    private var sequence = 0L
    private var discardedBefore = 0L
    private val sourceSession = java.util.UUID.randomUUID().toString()
    private var persistedInvalidation = 0L
    private data class Completion(val id: String, val state: DeliveryBudgetState, val entry: BudgetSpendEntry?,
        var rowSaved: Boolean = false, var budgetSaved: Boolean = false)
    private var completion: Completion? = null
    private val alerted = linkedSetOf<AlertIdentity>()
    private class TickSound(var alerted: Boolean = false)

    init { require(eventCapacity > 0); gate.recover = { finishCompletion(); persistInvalidations() } }

    private suspend fun persistInvalidations() {
        val cutoff = synchronized(this) { discardedBefore }
        if (cutoff > persistedInvalidation) {
            rows.invalidateBefore(sourceSession, cutoff)
            persistedInvalidation = cutoff
        }
    }
    private fun ambiguous(item: NotificationItem): Boolean = synchronized(this) {
        item.sourceSession == sourceSession && item.sourceEventOrder > 0 && item.sourceEventOrder <= discardedBefore
    }

    private suspend fun finishCompletion() {
        val pending = completion ?: return
        if (!pending.rowSaved) { rows.markDelivered(pending.id); pending.rowSaved = true }
        if (!pending.budgetSaved) { budget.write(pending.state); pending.budgetSaved = true }
        pending.entry?.let { budget.appendSpend(it) }
        completion = null
    }

    @Synchronized private fun invalidateAll() { discardedBefore = ++sequence; events.clear() }


    /** Capture on the listener callback, before async discovery/classification. No private data is logged.
     * At capacity, conservatively refuse callbacks older than the evicted event, rather than resurrect them.
     */
    @Synchronized
    fun captureEvent(id: String, removed: Boolean = false, bypassed: Boolean = false): Long {
        val order = ++sequence
        val previous = events.remove(id)
        events[id] = Event(order, removed, if (removed) order else previous?.lastRemoval ?: 0, previous?.accepted ?: 0, bypassed, previous?.appliedRemoval ?: 0)
        if (events.size > eventCapacity) {
            val oldest = events.entries.first()
            discardedBefore = maxOf(discardedBefore, oldest.value.order)
            events.remove(oldest.key)
        }
        return order
    }

    @Synchronized
    private fun stale(id: String, order: Long?, timestamp: Long): Boolean =
        if (order != null) order <= discardedBefore || events[id]?.let { it.order > order || it.removed } == true
        else (legacyRemovals[id] ?: Long.MIN_VALUE) >= timestamp

    @Synchronized
    private fun explicitlyRemoved(id: String): Boolean = events[id]?.let { it.lastRemoval > it.accepted } == true
    @Synchronized private fun removalApplied(id: String, order: Long) {
        events[id]?.let { events[id] = it.copy(appliedRemoval = maxOf(it.appliedRemoval, order)) }
    }
    @Synchronized private fun bypassed(id: String): Boolean = events[id]?.bypassed == true
    @Synchronized private fun unprocessed(id: String): Boolean = events[id]?.let { it.order > it.accepted && !it.removed } == true
    @Synchronized private fun accept(id: String, order: Long?) {
        if (order != null) events[id]?.let { events[id] = it.copy(accepted = order) }
    }

    suspend fun onArrival(
        item: NotificationItem,
        effectivePriority: NotificationPriority,
        eventOrder: Long? = null,
        passThrough: Boolean = false,
        captureActions: (() -> Unit)? = null,
        sourceStillCurrent: (() -> Boolean)? = null,
    ): DeliveryOutcome = gate.run {
        if (stale(item.id, eventOrder, item.timestamp)) return@run DeliveryOutcome.Cleared(item.id)
        if (sourceStillCurrent?.invoke() == false) return@run DeliveryOutcome.Deferred(item.id)
        val previous = rows.find(item.id)
        val delivered = previous?.isDelivered == true && !previous.isCleared && !explicitlyRemoved(item.id)
        val updated = item.copy(priority = effectivePriority, isRead = previous?.isRead ?: item.isRead,
            isDelivered = delivered || passThrough, isCleared = false, deliveryInvalidated = false,
            sourceSession = if (eventOrder != null) sourceSession else item.sourceSession,
            sourceEventOrder = eventOrder ?: item.sourceEventOrder)
        rows.upsert(updated)
        if (stale(item.id, eventOrder, item.timestamp)) { rows.markCleared(item.id); return@run DeliveryOutcome.Cleared(item.id) }
        accept(item.id, eventOrder)
        captureActions?.invoke()
        if (passThrough) return@run DeliveryOutcome.Delivered(item.id)
        if (delivered) {
            return@run when (val post = postSafely(updated, refresh = true)) {
                PostResult.Posted -> DeliveryOutcome.Delivered(item.id)
                is PostResult.Failed -> DeliveryOutcome.PostFailed(item.id, post.reason)
            }
        }
        val setting = environment.settings()[effectivePriority] ?: PriorityDeliverySetting.defaultFor(effectivePriority)
        if (!NotificationDeliveryPolicy.shouldDeliverOnArrival(setting)) return@run DeliveryOutcome.Deferred(item.id)
        if (stale(item.id, eventOrder, item.timestamp)) DeliveryOutcome.Cleared(item.id)
        else if (sourceStillCurrent?.invoke() == false) DeliveryOutcome.Deferred(item.id) else deliver(updated)
    }

    suspend fun onRemoved(id: String, reason: NotificationRemovalReason, atMillis: Long, eventOrder: Long? = null) {
        if (reason != NotificationRemovalReason.SOURCE_APP_CANCELLED && reason != NotificationRemovalReason.USER_DISMISSED && reason != NotificationRemovalReason.INBOX_CLEARED) return
        if (eventOrder == null) synchronized(this) {
            legacyRemovals[id] = maxOf(legacyRemovals[id] ?: Long.MIN_VALUE, atMillis)
            if (legacyRemovals.size > eventCapacity) legacyRemovals.remove(legacyRemovals.keys.first())
        }
        gate.run {
            if (eventOrder != null && synchronized(this) { (events[id]?.accepted ?: 0) > eventOrder }) return@run
            val current = rows.find(id)
            if (eventOrder == null && current != null && current.timestamp > atMillis) return@run
            rows.markCleared(id)
            if (eventOrder != null) removalApplied(id, eventOrder)
        }
    }

    suspend fun clearInbox(id: String, delete: suspend () -> Unit) {
        val order = captureEvent(id, removed = true)
        gate.run { rows.markCleared(id); delete(); removalApplied(id, order) }
    }
    suspend fun clearAll(delete: suspend () -> Unit) {
        invalidateAll()
        gate.run { delete() }
    }

    suspend fun onBatchTick(): List<DeliveryOutcome> = gate.run {
        val removals = synchronized(this) { events.filterValues { it.lastRemoval > it.accepted && it.appliedRemoval < it.lastRemoval }.map { it.key to it.value.lastRemoval } }
        removals.forEach { (id, order) -> rows.markCleared(id); removalApplied(id, order) }
        val settings = environment.settings()
        val time = environment.timeContext()
        val visibleSources = environment.activeSourceKeys()
        val pending = rows.pending(NotificationPriority.LOW.level).filter {
            NotificationDeliveryPolicy.shouldDeliverOnBatchTick(it.priority, settings, time)
        }.toMutableList()
        val outcomes = mutableListOf<DeliveryOutcome>()
        val sound = TickSound()
        while (pending.isNotEmpty() && outcomes.size < MAX_BATCH_ATTEMPTS) {
            val candidate = NotificationDeliveryPolicy.orderCandidates(pending, environment.queuePreferences()).first()
            pending.removeAll { it.id == candidate.id }
            val current = rows.find(candidate.id)
            val outcome = if (current == null || current.isCleared || explicitlyRemoved(candidate.id)) {
                rows.markCleared(candidate.id)
                DeliveryOutcome.Cleared(candidate.id)
            } else if (current.deliveryInvalidated || ambiguous(current)) {
                persistInvalidations()
                DeliveryOutcome.Invalidated(candidate.id)
            } else if (current.isDelivered || bypassed(candidate.id) || candidate.id in visibleSources) DeliveryOutcome.Deferred(candidate.id)
            else deliver(current, sound)
            outcomes += outcome
            if (outcome is DeliveryOutcome.CommitPending) break
            if (outcome is DeliveryOutcome.Delivered) {
                rows.nextHead(candidate)?.let { next -> if (pending.none { it.id == next.id }) pending += next }
            }
        }
        outcomes
    }

    private suspend fun deliver(item: NotificationItem, sound: TickSound? = null): DeliveryOutcome {
        // Recheck callback-side tombstones at the effect boundary, even if removal work is queued.
        if (explicitlyRemoved(item.id) || rows.find(item.id)?.isCleared != false) {
            rows.markCleared(item.id)
            return DeliveryOutcome.Cleared(item.id)
        }
        if (item.deliveryInvalidated || ambiguous(item)) { persistInvalidations(); return DeliveryOutcome.Invalidated(item.id) }
        if (bypassed(item.id) || unprocessed(item.id)) return DeliveryOutcome.Deferred(item.id)
        val time = environment.timeContext()
        val schedule = environment.schedule()
        val setting = environment.settings()[item.priority] ?: PriorityDeliverySetting.defaultFor(item.priority)
        val decision = NotificationDeliveryPolicy.canDeliverNow(item.priority, setting, schedule, time, budget.read())
        if (!decision.canDeliver) return DeliveryOutcome.Deferred(item.id)
        if (explicitlyRemoved(item.id) || rows.find(item.id)?.isCleared != false) {
            rows.markCleared(item.id)
            return DeliveryOutcome.Cleared(item.id)
        }
        if (ambiguous(item)) { persistInvalidations(); return DeliveryOutcome.Invalidated(item.id) }
        if (bypassed(item.id) || unprocessed(item.id)) return DeliveryOutcome.Deferred(item.id)
        val alert = environment.soundElection(item)?.let {
            ElectionPolicy.alert(item, it, alerted, sound?.alerted == true)
        } ?: ProxyAlert.Normal
        return when (val post = postSafely(item, alert = alert)) {
            is PostResult.Failed -> DeliveryOutcome.PostFailed(item.id, post.reason)
            PostResult.Posted -> {
                if (alert == ProxyAlert.Breakthrough) {
                    alerted += AlertIdentity(item.id, item.timestamp)
                    if (alerted.size > eventCapacity) alerted.remove(alerted.first())
                    sound?.alerted = true
                }
                val charged = NotificationDeliveryPolicy.recordPassthrough(item.priority, setting, schedule, time.scheduleMinuteOfDay, decision.updatedBudget)
                val entry = if (setting.budgetBehavior == ly.com.tahaben.notification_filter_domain.model.BudgetBehavior.RESPECTING && item.priority.pointCost > 0)
                    BudgetSpendEntry(item.appName.orEmpty(), item.title.orEmpty(), item.priority, item.priority.pointCost,
                        System.currentTimeMillis(), charged.periodStartMinute, charged.accountingId, java.util.UUID.randomUUID().toString()) else null
                // At most one unresolved effect is retained. All subsequent mutations recover it under this gate.
                completion = Completion(item.id, charged, entry)
                try { finishCompletion() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { return DeliveryOutcome.CommitPending(item.id) }
                DeliveryOutcome.Delivered(item.id)
            }
        }
    }

    private suspend fun postSafely(
        item: NotificationItem,
        refresh: Boolean = false,
        alert: ProxyAlert = ProxyAlert.Normal,
    ): PostResult = try {
        if (refresh) {
            poster.refresh(item)
        } else {
            val first = poster.post(item, alert)
            if (alert == ProxyAlert.Breakthrough && first == PostResult.Failed(BREAKTHROUGH_BYPASS_LOST)) {
                poster.post(item, ProxyAlert.Normal)
            } else {
                first
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PostResult.Failed("notification_post_failed")
    }

    companion object { const val MAX_EVENT_KEYS = 4096; const val MAX_BATCH_ATTEMPTS = 256 }
}
