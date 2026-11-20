package ly.com.tahaben.notification_filter_data.service

import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventLog
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeInput

interface SnoozePlatform {
    fun snooze(key: String, millis: Long)
    fun snoozedKeys(): Set<String>
}

class SnoozeHandler(platform: SnoozePlatform, events: ExperimentEventLog) {
    fun onListenerConnected() = Unit

    // CLAIM(snooze-handled): a successful snooze or return bypasses ordinary delivery [Snooze pilot spec D5, D7].
    fun onPosted(key: String, input: SnoozeInput, atEpochMillis: Long): Boolean = false

    // CLAIM(snooze-removal): Android's snooze callback bypasses proxy removal [Snooze pilot spec D6].
    fun onRemoved(key: String, packageName: String, reason: Int, atEpochMillis: Long): Boolean = false
}
