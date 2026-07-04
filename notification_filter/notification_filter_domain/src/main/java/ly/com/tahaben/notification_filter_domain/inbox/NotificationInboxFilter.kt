package ly.com.tahaben.notification_filter_domain.inbox

import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

object NotificationInboxFilter {

    fun effectivePriority(
        item: NotificationItem,
        standingThresholdHours: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): NotificationPriority {
        val standingCutoffMillis = standingThresholdHours * 3_600_000L
        val isStanding = !item.isRead && (nowMillis - item.timestamp) > standingCutoffMillis
        return if (isStanding) item.priority.degraded(1) else item.priority
    }

    fun filter(
        notifications: List<NotificationItem>,
        priorityFilter: NotificationPriority?,
        standingThresholdHours: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<NotificationItem> {
        if (priorityFilter == null) return notifications
        return notifications.filter { item ->
            effectivePriority(item, standingThresholdHours, nowMillis) == priorityFilter
        }
    }
}
