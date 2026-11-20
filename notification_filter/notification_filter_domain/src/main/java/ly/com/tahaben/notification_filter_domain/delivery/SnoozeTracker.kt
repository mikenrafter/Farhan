package ly.com.tahaben.notification_filter_domain.delivery

class SnoozeTracker {
    private val keys = LinkedHashSet<String>()
    // CLAIM(snooze-tracker-bound): keep at most 1024 keys, evicting the oldest [Snooze pilot spec D5].
    @Synchronized
    fun recordSnoozed(key: String) {
        keys.remove(key)
        keys.add(key)
        if (keys.size > 1024) keys.remove(keys.first())
    }

    // CLAIM(snooze-return-once): a tracked return consumes its key [Snooze pilot spec D5, I2].
    @Synchronized
    fun consumeIfSnoozed(key: String): Boolean = keys.remove(key)

    @Synchronized
    fun rehydrate(keys: Iterable<String>) {
        this.keys.clear()
        keys.forEach(::recordSnoozed)
    }
}
