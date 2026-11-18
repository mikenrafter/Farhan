package ly.com.tahaben.notification_filter_domain.delivery

import java.time.Clock

/** Production wall-clock adapter; always carries a calendar date including the year. */
object DeliveryClock {
    /** Resolve the phone zone at use time, including changes while the process remains alive. */
    val systemClock: Clock = object : Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneId.systemDefault()
        override fun withZone(zone: java.time.ZoneId): Clock = Clock.system(zone)
        override fun instant(): java.time.Instant = java.time.Instant.now()
    }
    fun current(testingMode: Boolean, clock: Clock = systemClock): DeliveryTimeContext {
        val now = clock.instant().atZone(clock.zone)
        return DeliveryTimeContext.from(now.hour, now.hour * 60 + now.minute, now.dayOfYear, testingMode, now.toLocalDate().toString())
    }
}
