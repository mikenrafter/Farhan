package ly.com.tahaben.notification_filter_data.service

import android.service.notification.NotificationListenerService
import ly.com.tahaben.notification_filter_domain.delivery.NotificationRemovalReason

object NotificationRemovalMapper {
    fun fromReason(reason: Int): NotificationRemovalReason? = when (reason) {
        NotificationListenerService.REASON_APP_CANCEL, NotificationListenerService.REASON_APP_CANCEL_ALL -> NotificationRemovalReason.SOURCE_APP_CANCELLED
        NotificationListenerService.REASON_CANCEL, NotificationListenerService.REASON_CANCEL_ALL, NotificationListenerService.REASON_CLICK -> NotificationRemovalReason.USER_DISMISSED
        else -> null // Farhan listener cancellation, snooze and unrelated lifecycle removals are not explicit clears.
    }
}
