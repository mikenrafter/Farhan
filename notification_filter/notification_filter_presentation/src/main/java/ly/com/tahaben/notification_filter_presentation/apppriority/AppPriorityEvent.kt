package ly.com.tahaben.notification_filter_presentation.apppriority

import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

sealed class AppPriorityEvent {
    data class OnSetAppPriority(val packageName: String, val priority: NotificationPriority?) : AppPriorityEvent()
    data class OnSetAppLocked(val packageName: String, val locked: Boolean) : AppPriorityEvent()
    data class OnSetChannelPriority(val packageName: String, val channelId: String, val priority: NotificationPriority?) : AppPriorityEvent()
    data class OnSetQueuePreference(val packageName: String, val preference: ly.com.tahaben.notification_filter_domain.delivery.AppQueuePreference) : AppPriorityEvent()
}
