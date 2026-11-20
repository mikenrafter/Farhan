package ly.com.tahaben.notification_filter_data.service

import java.time.Instant
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
    platform: SnoozePlatform,
    events: ExperimentEventLog,
    budget: SnoozeReturnBudget,
    ledger: SnoozeLedger,
    bootCount: () -> Int,
    proxyArrival: suspend (NotificationItem) -> Unit,
    refreshProxy: suspend (NotificationItem) -> Unit,
    inputAt: (Instant) -> SnoozeInput,
) {
    suspend fun onListenerConnected(now: Instant, previousTick: Instant) = Unit

    // CLAIM(snooze-confirmation): reconcile against Android state at a batch tick [Snooze pilot spec V6].
    suspend fun onBatchTick(now: Instant, previousTick: Instant) = Unit

    // CLAIM(snooze-handled): unsuccessful native snoozes leave the post for ordinary delivery [Snooze pilot spec V1].
    suspend fun onPosted(item: NotificationItem, input: SnoozeInput): Boolean = false

    // CLAIM(snooze-one-return-path): intake and rescan settle through one worker entry [Snooze pilot spec W3, W5].
    suspend fun settle(returned: SnoozeReturn, input: SnoozeInput): Boolean = false

    // CLAIM(snooze-removal): Android's snooze callback bypasses proxy removal [Snooze pilot spec D6].
    suspend fun onRemoved(key: String, packageName: String, reason: Int, atEpochMillis: Long): Boolean = false
}
