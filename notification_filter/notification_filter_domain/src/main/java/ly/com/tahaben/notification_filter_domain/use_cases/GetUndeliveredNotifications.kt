package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository

class GetUndeliveredNotifications(private val repository: NotificationRepository) {
    suspend operator fun invoke(maxPriorityLevel: Int): List<NotificationItem> =
        repository.getUndeliveredNotificationsUpToPriority(maxPriorityLevel)
}
