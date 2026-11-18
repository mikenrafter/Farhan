package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.delivery.NotificationCore
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil

class DeleteAllNotifications(private val repository: NotificationRepository, private val core: NotificationCore, private val service: ServiceUtil) {
    suspend operator fun invoke() = core.coordinator.clearAll {
        repository.deleteAllNotificationsFromDB()
        service.clearNotificationActions()
    }
}
