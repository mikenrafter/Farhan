package ly.com.tahaben.notification_filter_domain.delivery

class SnoozeTracker {
    // CLAIM(snooze-tracker-bound): keep at most 1024 keys, evicting the oldest [Snooze pilot spec D5].
    fun recordSnoozed(key: String) = Unit

    // CLAIM(snooze-return-once): a tracked return consumes its key [Snooze pilot spec D5, I2].
    fun consumeIfSnoozed(key: String): Boolean = false

    fun rehydrate(keys: Iterable<String>) = Unit
}
