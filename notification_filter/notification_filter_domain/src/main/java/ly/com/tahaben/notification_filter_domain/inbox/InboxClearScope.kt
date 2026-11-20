package ly.com.tahaben.notification_filter_domain.inbox

import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

sealed interface InboxClearScope {
    data object All : InboxClearScope
    data class Priority(val priority: NotificationPriority) : InboxClearScope
    data class Cleared(val priority: NotificationPriority?) : InboxClearScope
}
