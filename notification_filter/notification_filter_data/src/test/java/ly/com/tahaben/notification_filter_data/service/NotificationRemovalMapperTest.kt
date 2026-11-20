package ly.com.tahaben.notification_filter_data.service

import android.service.notification.NotificationListenerService
import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.delivery.NotificationRemovalReason
import org.junit.Test

class NotificationRemovalMapperTest {
    // why: only observable explicit removal signals clear rows; Farhan's own interception and snooze do not.
    // catches: mapping every removal to cancellation, or losing source/app/user clear variants.
    @Test fun `platform reasons distinguish explicit removal from listener and snooze effects`() {
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_APP_CANCEL)).isEqualTo(NotificationRemovalReason.SOURCE_APP_CANCELLED)
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_APP_CANCEL_ALL)).isEqualTo(NotificationRemovalReason.SOURCE_APP_CANCELLED)
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_CANCEL)).isEqualTo(NotificationRemovalReason.USER_DISMISSED)
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_CANCEL_ALL)).isEqualTo(NotificationRemovalReason.USER_DISMISSED)
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_CLICK)).isEqualTo(NotificationRemovalReason.USER_DISMISSED)
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_LISTENER_CANCEL)).isNull()
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_SNOOZED)).isNull()
    }

    // why: CLAIM(scoped-proxy-clear) treats app cancellation of a Farhan proxy as cleanup after a clear.
    // catches: feeding Farhan's own cleanup back into the bounded delivery callback history, or ignoring a user swipe.
    @Test fun `Farhan cancelling its own proxy is cleanup and a user swipe is a removal`() {
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_APP_CANCEL, ownProxy = true)).isNull()
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_APP_CANCEL_ALL, ownProxy = true)).isNull()
        assertThat(NotificationRemovalMapper.fromReason(NotificationListenerService.REASON_CANCEL, ownProxy = true))
            .isEqualTo(NotificationRemovalReason.USER_DISMISSED)
    }
}
