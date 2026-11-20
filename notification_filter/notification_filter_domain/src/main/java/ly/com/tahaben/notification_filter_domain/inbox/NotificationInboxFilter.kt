package ly.com.tahaben.notification_filter_domain.inbox

import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

object NotificationInboxFilter {

    fun effectivePriority(
        item: NotificationItem,
        standingThresholdHours: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): NotificationPriority = effectivePriorityAtCutoff(item, nowMillis - standingThresholdHours * 3_600_000L)

    fun effectivePriorityAtCutoff(item: NotificationItem, standingCutoff: Long): NotificationPriority {
        val isStanding = !item.isRead
            && item.priority != NotificationPriority.CRITICAL
            && item.timestamp < standingCutoff
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
