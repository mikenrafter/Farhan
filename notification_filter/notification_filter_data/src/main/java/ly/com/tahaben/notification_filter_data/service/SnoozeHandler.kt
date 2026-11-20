package ly.com.tahaben.notification_filter_data.service

import java.time.Instant
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventLog
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeInput
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

interface SnoozePlatform {
    fun snooze(key: String, millis: Long)
    fun snoozedKeys(): Set<String>
}

sealed interface SnoozeReturnDecision {
    data object Charged : SnoozeReturnDecision
    data class RetryAt(val instant: Instant) : SnoozeReturnDecision
}

fun interface SnoozeReturnBudget {
    // CLAIM(snooze-return-budget): decide and charge in one gated operation [Snooze pilot spec D5, D7, I2].
    suspend fun settleReturn(
        key: String,
        packageName: String,
        priority: NotificationPriority,
        at: Instant,
    ): SnoozeReturnDecision
}

class SnoozeHandler(platform: SnoozePlatform, events: ExperimentEventLog, budget: SnoozeReturnBudget) {
    fun onListenerConnected() = Unit

    // CLAIM(snooze-handled): a successful snooze or return bypasses ordinary delivery [Snooze pilot spec D5, D7].
    suspend fun onPosted(key: String, input: SnoozeInput): Boolean = false

    // CLAIM(snooze-removal): Android's snooze callback bypasses proxy removal [Snooze pilot spec D6].
    fun onRemoved(key: String, packageName: String, reason: Int, atEpochMillis: Long): Boolean = false
}
