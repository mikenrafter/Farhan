package ly.com.tahaben.notification_filter_data.service

/** Records only summaries that Farhan asked Android to cancel. Group keys are scoped by Android. */
internal class SnoozeSummaryCancellations(private val elapsedMillis: () -> Long) {
    private data class Cancellation(
        val summaryKey: String,
        val postTime: Long,
        val expiresAt: Long,
        val confirmed: Boolean,
    )
    private val groups = linkedMapOf<String, Cancellation>()

    @Synchronized
    fun requested(groupKey: String, summaryKey: String, postTime: Long) {
        groups.remove(groupKey)
        groups[groupKey] = Cancellation(summaryKey, postTime, elapsedMillis() + WINDOW_MILLIS, confirmed = false)
        if (groups.size > CAPACITY) groups.remove(groups.keys.first())
    }

    @Synchronized
    fun summaryRemoved(groupKey: String, summaryKey: String, postTime: Long, listenerCancelled: Boolean) {
        val request = groups.remove(groupKey) ?: return
        val now = elapsedMillis()
        if (request.summaryKey == summaryKey && request.postTime == postTime && listenerCancelled && now < request.expiresAt) {
            groups[groupKey] = request.copy(expiresAt = now + WINDOW_MILLIS, confirmed = true)
        }
    }

    @Synchronized
    fun causedCascade(groupKey: String): Boolean {
        val request = groups[groupKey] ?: return false
        if (elapsedMillis() < request.expiresAt) return request.confirmed
        groups.remove(groupKey)
        return false
    }

    private companion object {
        const val CAPACITY = 128
        const val WINDOW_MILLIS = 2_000L
    }
}
