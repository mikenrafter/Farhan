package ly.com.tahaben.notification_filter_data.service

import ly.com.tahaben.notification_filter_domain.delivery.NotificationRateWindow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackageNotificationRateTracker @Inject constructor() {
    data class RateCounts(val lastTenMinutes: Int, val lastHour: Int)
    private val window = NotificationRateWindow()
    fun record(packageName: String): RateCounts = window.record(packageName).let { RateCounts(it.lastTenMinutes, it.lastHour) }
}
