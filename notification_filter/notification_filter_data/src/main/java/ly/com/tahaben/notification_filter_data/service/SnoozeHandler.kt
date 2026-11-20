package ly.com.tahaben.notification_filter_data.service

import android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED
import android.service.notification.NotificationListenerService.REASON_SNOOZED
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEvent
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.FAILED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.LOST
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.REMOVED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.RESNOOZED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.RETURNED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.SNOOZED
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeDecision
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeFallbackReason
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeFallbackReason.*
import ly.com.tahaben.notification_filter_domain.delivery.SnoozePolicy
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeReturnFlags
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeSkipReason
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventLog
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeInput
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeReturn
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeReturnDecision
import ly.com.tahaben.notification_filter_domain.model.NotificationItem

interface SnoozePlatform {
    fun snooze(key: String, millis: Long)
    fun snoozedKeys(): Set<String>
    fun activePostTimes(): Map<String, Long>
    fun cancelVisible(key: String)
}

interface SnoozeLedger {
    suspend fun find(key: String): NotificationItem?
    suspend fun liveRows(): List<NotificationItem>
    suspend fun upsert(item: NotificationItem)
}

fun interface SnoozeReturnBudget {
    // CLAIM(snooze-return-budget): decide and charge in one gated operation [Snooze pilot spec V4].
    suspend fun settleReturn(key: String, postTime: Long, now: Instant): SnoozeReturnDecision
}

class SnoozeHandler(
    private val platform: SnoozePlatform,
    private val events: ExperimentEventLog,
    private val budget: SnoozeReturnBudget,
    private val ledger: SnoozeLedger,
    private val bootCount: () -> Int,
    private val proxyArrival: suspend (NotificationItem) -> Unit,
    private val refreshProxy: suspend (NotificationItem) -> Unit,
    private val inputAt: (Instant) -> SnoozeInput,
) : SnoozeReturnFlags {
    private data class Entry(var confirmed: Boolean = false, var returned: SnoozeReturn? = null)
    private data class Attempts(val day: LocalDate, val count: Int, val boundary: Int, val retry: Boolean)
    private val owned = linkedMapOf<String, Entry>()
    private val attempts = mutableMapOf<String, Attempts>()

    @Synchronized
    override fun flagIfOwned(key: String, postTime: Long): Boolean {
        val entry = owned[key] ?: return false
        if ((entry.returned?.postTime ?: Long.MIN_VALUE) <= postTime) entry.returned = SnoozeReturn(key, postTime)
        return true
    }

    @Synchronized
    override fun flagged(): List<SnoozeReturn> = owned.values.mapNotNull { it.returned }.sortedBy { it.postTime }

    @Synchronized
    override fun clear(returned: SnoozeReturn) {
        val entry = owned[returned.key] ?: return
        if (entry.returned == returned) entry.returned = null
    }

    @Synchronized
    fun owns(key: String) = key in owned

    @Synchronized
    fun hasOwnedRows() = owned.isNotEmpty()

    @Synchronized
    private fun remember(key: String, confirmed: Boolean = false) {
        val entry = owned.remove(key) ?: Entry()
        entry.confirmed = confirmed
        owned[key] = entry
        if (owned.size > 1024) {
            val evicted = owned.keys.first()
            owned.remove(evicted)
            attempts.remove(evicted)
        }
    }

    @Synchronized
    private fun forget(key: String) {
        owned.remove(key)
        attempts.remove(key)
    }

    suspend fun onListenerConnected(now: Instant, previousTick: Instant) = scan(now, previousTick)

    // CLAIM(snooze-confirmation): reconcile against Android state at a batch tick [Snooze pilot spec W3].
    suspend fun onBatchTick(now: Instant, previousTick: Instant) = scan(now, previousTick)

    private suspend fun scan(now: Instant, previousTick: Instant) {
        val rows = ledger.liveRows()
        synchronized(this) {
            for (row in rows) if (!owns(row.id)) remember(row.id)
            val keys = rows.mapTo(hashSetOf()) { it.id }
            owned.keys.retainAll(keys)
            attempts.keys.retainAll(keys)
        }
        val active: Map<String, Long>
        val snoozed: Set<String>
        try {
            active = platform.activePostTimes()
            snoozed = platform.snoozedKeys()
        } catch (_: SecurityException) {
            return
        } catch (_: IllegalStateException) {
            return
        }
        val input by lazy { inputAt(now) }
        for (row in rows) {
            val deadline = row.snoozeDeadline ?: continue
            if (row.id in snoozed) {
                remember(row.id, confirmed = true)
                continue
            }
            if (deadline >= now.toEpochMilli()) continue
            val activePostTime = active[row.id]
            when {
                activePostTime != null && activePostTime != row.timestamp ->
                    settle(SnoozeReturn(row.id, activePostTime), input)
                activePostTime != null && row.timestamp < previousTick.toEpochMilli() &&
                    synchronized(this) { owned[row.id]?.confirmed != true } -> fallback(row, now, UNCONFIRMED)
                activePostTime == null && deadline < previousTick.toEpochMilli() -> {
                    val lost = row.snoozeBootCount != bootCount()
                    release(row.copy(isCleared = !lost && row.snoozeGroupKey == null))
                    record(if (lost) LOST else REMOVED, row, now)
                }
            }
        }
    }

    // CLAIM(snooze-handled): unsuccessful native snoozes leave the post for ordinary delivery [Snooze pilot spec V1].
    suspend fun onPosted(item: NotificationItem, input: SnoozeInput): Boolean {
        if (input.snoozeEnabled && input.filterEnabled && input.exclusionFlags.isGroupSummary) return true
        if (ledger.find(item.id)?.snoozeDeadline != null) return true
        return when (val decision = SnoozePolicy.decide(input)) {
            is SnoozeDecision.Skip -> {
                if (decision.reason == SnoozeSkipReason.PLATFORM_TOO_OLD) record(FAILED, item, input.now)
                false
            }
            is SnoozeDecision.Snooze -> request(item, input.priority, decision.durationMillis, input, retry = false)
        }
    }

    // CLAIM(snooze-one-return-path): intake and rescan settle through one worker entry [Snooze pilot spec W3, W5].
    suspend fun settle(returned: SnoozeReturn, input: SnoozeInput): Boolean {
        val row = ledger.find(returned.key) ?: return false
        val deadline = row.snoozeDeadline ?: return false
        val priority = row.snoozePriority ?: return false
        if (row.isDelivered || row.isCleared) return false
        if (!input.filterEnabled) {
            release(row.copy(isDelivered = true))
            return true
        }
        val current = boundary(input)
        val previous = attempts[row.id]
        if (previous?.retry == true && previous.day == current.first && previous.boundary == current.second) {
            return fallback(row, input.now, SAME_BOUNDARY)
        }
        if (input.now.toEpochMilli() < deadline) return fallback(row, input.now, BEFORE_DEADLINE)
        if (returned.postTime == row.timestamp) return fallback(row, input.now, NOT_A_RETURN)
        return when (val decision = budget.settleReturn(row.id, returned.postTime, input.now)) {
            SnoozeReturnDecision.Charged -> {
                forget(row.id)
                record(RETURNED, row, input.now)
                true
            }
            SnoozeReturnDecision.NotOwned -> {
                val live = ledger.find(row.id)
                if (live?.snoozeDeadline != null && !live.isCleared && !live.isDelivered) {
                    fallback(live, input.now, NOT_A_RETURN)
                } else {
                    forget(row.id)
                    false
                }
            }
            SnoozeReturnDecision.NoFundedMoment ->
                fallback(row, input.now, if (input.snoozeEnabled) NO_FUNDED_MOMENT else SNOOZE_OFF)
            is SnoozeReturnDecision.RetryAt -> {
                val delay = Duration.between(input.now, decision.instant).toMillis()
                when {
                    !input.snoozeEnabled -> fallback(row, input.now, SNOOZE_OFF)
                    delay <= 0 -> fallback(row, input.now, INVALID_RETRY)
                    previous?.day == current.first && previous.count >= 3 -> fallback(row, input.now, DAILY_LIMIT)
                    request(row.copy(timestamp = returned.postTime), priority, delay, input, retry = true) -> true
                    else -> fallback(row, input.now, NATIVE_FAILURE)
                }
            }
        }
    }

    private suspend fun request(
        item: NotificationItem, priority: ly.com.tahaben.notification_filter_domain.model.NotificationPriority,
        delay: Long, input: SnoozeInput, retry: Boolean,
    ): Boolean {
        val previous = ledger.find(item.id)
        ledger.upsert(item.copy(
            snoozeDeadline = input.now.toEpochMilli() + delay,
            snoozePriority = priority,
            snoozeBootCount = bootCount(),
        ))
        remember(item.id)
        try {
            platform.snooze(item.id, delay)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            if (!retry) {
                ledger.upsert(previous ?: item)
                forget(item.id)
                record(FAILED, item, input.now)
            }
            return false
        }
        val (day, boundary) = boundary(input)
        val count = attempts[item.id]?.takeIf { it.day == day }?.count ?: 0
        attempts[item.id] = Attempts(day, count + 1, boundary, retry)
        record(if (retry) RESNOOZED else SNOOZED, item, input.now)
        return true
    }

    private fun boundary(input: SnoozeInput): Pair<LocalDate, Int> {
        val local = input.now.atZone(input.zone)
        val minute = local.hour * 60 + local.minute
        return local.toLocalDate() to (minute / if (input.testingMode) 3 else 60)
    }

    private suspend fun release(row: NotificationItem) {
        ledger.upsert(row.copy(snoozeDeadline = null, snoozePriority = null, snoozeBootCount = null))
        forget(row.id)
    }

    private suspend fun fallback(row: NotificationItem, now: Instant, reason: SnoozeFallbackReason): Boolean {
        release(row)
        platform.cancelVisible(row.id)
        proxyArrival(row.copy(snoozeDeadline = null, snoozePriority = null, snoozeBootCount = null))
        events.record(ExperimentEvent(now.toEpochMilli(), FAILED, packageName = row.packageName, fallbackReason = reason))
        return false
    }

    // CLAIM(snooze-removal): Android's snooze callback bypasses proxy removal [Snooze pilot spec D6, W7].
    suspend fun onRemoved(key: String, packageName: String, reason: Int, atEpochMillis: Long): Boolean {
        val row = ledger.find(key) ?: return false
        if (reason == REASON_SNOOZED && row.snoozeDeadline != null) {
            remember(key, confirmed = true)
            return true
        }
        if (reason == REASON_GROUP_SUMMARY_CANCELED && row.snoozeGroupKey != null) {
            if (row.isDelivered && !row.isCleared) refreshProxy(row)
            else if (row.snoozeDeadline != null) release(row.copy(isCleared = false))
            return true
        }
        return false
    }

    private fun record(kind: ExperimentEventKind, row: NotificationItem, at: Instant) {
        events.record(ExperimentEvent(at.toEpochMilli(), kind, packageName = row.packageName))
    }
}
