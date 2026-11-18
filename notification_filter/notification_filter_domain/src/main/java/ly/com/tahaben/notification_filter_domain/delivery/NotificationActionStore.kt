package ly.com.tahaben.notification_filter_domain.delivery

/** Transient capabilities only. Evicted history opens the source app; inbox rows are never removed here. */
class NotificationActionStore<T>(private val capacity: Int = 1024, private val expiryMillis: Long = 86_400_000,
    private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry<T>(val capturedAt: Long, val actions: T)
    private val entries = linkedMapOf<String, Entry<T>>()
    @Synchronized fun put(id: String, actions: T) {
        prune()
        entries.remove(id)
        entries[id] = Entry(now(), actions)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun get(id: String): T? { prune(); return entries[id]?.actions }
    @Synchronized fun remove(id: String) { entries.remove(id) }
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun size(): Int { prune(); return entries.size }
    private fun prune() {
        val cutoff = now() - expiryMillis
        val iterator = entries.iterator()
        while (iterator.hasNext()) if (iterator.next().value.capturedAt <= cutoff) iterator.remove()
    }
}
