package ly.com.tahaben.notification_filter_domain.delivery

/** Minute buckets bound storm memory. Counts use the current minute plus the preceding 9/59 minutes. */
class NotificationRateWindow(private val capacity: Int = 512, private val now: () -> Long = System::currentTimeMillis) {
    data class Counts(val lastTenMinutes: Int, val lastHour: Int)
    private data class Buckets(val minutes: LongArray = LongArray(60) { Long.MIN_VALUE }, val counts: IntArray = IntArray(60), var latest: Long = Long.MIN_VALUE)
    private val packages = LinkedHashMap<String, Buckets>(16, 0.75f, true)
    @Synchronized fun record(packageName: String): Counts {
        val minute = Math.floorDiv(now(), 60_000L)
        val iterator = packages.iterator()
        while (iterator.hasNext()) if (iterator.next().value.latest <= minute - 60) iterator.remove()
        val buckets = packages.getOrPut(packageName) { Buckets() }
        while (packages.size > capacity) packages.remove(packages.keys.first())
        val slot = Math.floorMod(minute, 60).toInt()
        if (buckets.minutes[slot] != minute) { buckets.minutes[slot] = minute; buckets.counts[slot] = 0 }
        buckets.counts[slot] = (buckets.counts[slot].toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        buckets.latest = minute
        var ten = 0L
        var hour = 0L
        for (index in buckets.minutes.indices) {
            val age = minute - buckets.minutes[index]
            if (age in 0..59) hour += buckets.counts[index]
            if (age in 0..9) ten += buckets.counts[index]
        }
        return Counts(ten.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), hour.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
    @Synchronized fun packageCount(): Int = packages.size
}
