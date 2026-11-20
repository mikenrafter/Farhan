package ly.com.tahaben.notification_filter_data.service

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryTimeContext
import ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryCoordinator
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeInput
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeReturn
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

internal class SnoozeServiceRouting(
    private val coordinator: NotificationDeliveryCoordinator,
    private val handler: SnoozeHandler,
    private val find: suspend (String) -> NotificationItem?,
    private val inputAt: suspend (Instant) -> SnoozeInput,
) {
    suspend fun handleStoredPost(item: NotificationItem, order: Long, now: Instant): Boolean {
        if (find(item.id)?.snoozeDeadline == null) return false
        coordinator.acceptSnoozeEvent(item.id, order)
        settle(SnoozeReturn(item.id, item.timestamp), now)
        return true
    }

    suspend fun settle(returned: SnoozeReturn, now: Instant): Boolean =
        handler.settle(returned, inputAt(now))
}

internal suspend fun snoozeReturnFilterEnabled(preferences: Preferences): Boolean =
    preferences.isNotificationServiceStatsEnabled()

internal fun previousSnoozeTick(now: Instant, zone: ZoneId, testingMode: Boolean): Instant {
    val interval = if (testingMode) DeliveryTimeContext.TESTING_BOUNDARY_MINUTES
        else DeliveryTimeContext.PRODUCTION_BOUNDARY_MINUTES
    val minute = now.atZone(zone).minute
    return now.truncatedTo(ChronoUnit.MINUTES).minusSeconds((minute % interval + interval) * 60L)
}
