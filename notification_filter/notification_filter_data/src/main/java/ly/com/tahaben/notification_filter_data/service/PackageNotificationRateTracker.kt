package ly.com.tahaben.notification_filter_data.service

import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackageNotificationRateTracker @Inject constructor() {

    data class RateCounts(
        val lastTenMinutes: Int,
        val lastHour: Int,
    )

    private val timestampsByPackage = HashMap<String, ArrayDeque<Long>>()
    private val lock = Any()

    fun record(packageName: String): RateCounts {
        val now = System.currentTimeMillis()
        val tenMinuteCutoff = now - TEN_MINUTES_MS
        val hourCutoff = now - ONE_HOUR_MS

        synchronized(lock) {
            val deque = timestampsByPackage.getOrPut(packageName) { ArrayDeque() }
            deque.addLast(now)
            pruneOlderThan(deque, hourCutoff)

            var lastTenMinutes = 0
            for (timestamp in deque) {
                if (timestamp >= tenMinuteCutoff) {
                    lastTenMinutes++
                }
            }
            return RateCounts(lastTenMinutes = lastTenMinutes, lastHour = deque.size)
        }
    }

    private fun pruneOlderThan(deque: ArrayDeque<Long>, cutoff: Long) {
        while (deque.isNotEmpty() && deque.first() < cutoff) {
            deque.removeFirst()
        }
    }

    companion object {
        private const val TEN_MINUTES_MS = 600_000L
        private const val ONE_HOUR_MS = 3_600_000L
    }
}
