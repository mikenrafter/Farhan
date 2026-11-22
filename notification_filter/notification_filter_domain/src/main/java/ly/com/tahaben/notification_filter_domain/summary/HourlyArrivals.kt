package ly.com.tahaben.notification_filter_domain.summary

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Notification arrivals per local hour of one day, for the Summary timeline. Pure: the caller supplies the arrival
 * times and the zone, so the day boundaries and daylight-saving days are decided by [zone] alone.
 */
object HourlyArrivals {
    const val HOURS = 24

    /**
     * 24 counts indexed by local hour. A moment belongs to [day] when the zone's calendar says so; an hour that
     * repeats at a clock change adds into the same slot. Non-positive timestamps are rows with no recorded arrival
     * time (the model defaults to 0) and are skipped.
     */
    fun count(arrivalMillis: Iterable<Long>, day: LocalDate, zone: ZoneId): IntArray {
        val hours = IntArray(HOURS)
        for (millis in arrivalMillis) {
            if (millis <= 0L) continue
            val local = Instant.ofEpochMilli(millis).atZone(zone)
            if (local.toLocalDate() == day) hours[local.hour]++
        }
        return hours
    }

    /** Bar height in 0..1 for each hour, scaled so the busiest hour fills the chart. All zeros stay zero. */
    fun scaled(hours: IntArray): List<Float> {
        val peak = hours.maxOrNull() ?: 0
        return hours.map { if (peak == 0) 0f else it.toFloat() / peak }
    }
}
