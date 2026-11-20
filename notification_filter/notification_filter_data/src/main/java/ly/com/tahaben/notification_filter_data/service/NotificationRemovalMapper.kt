package ly.com.tahaben.notification_filter_data.service

import android.service.notification.NotificationListenerService
import ly.com.tahaben.notification_filter_domain.delivery.NotificationRemovalReason

object NotificationRemovalMapper {
    fun fromReason(reason: Int, ownProxy: Boolean): NotificationRemovalReason? = when (reason) {
        // CLAIM(scoped-proxy-clear): cancellation of Farhan's own proxy is cleanup [NotificationRemovalMapperTest].
        NotificationListenerService.REASON_APP_CANCEL, NotificationListenerService.REASON_APP_CANCEL_ALL ->
            if (ownProxy) null else NotificationRemovalReason.SOURCE_APP_CANCELLED
        NotificationListenerService.REASON_CANCEL, NotificationListenerService.REASON_CANCEL_ALL, NotificationListenerService.REASON_CLICK -> NotificationRemovalReason.USER_DISMISSED
        else -> null // Farhan listener cancellation, snooze and unrelated lifecycle removals are not explicit clears.
    }
}
