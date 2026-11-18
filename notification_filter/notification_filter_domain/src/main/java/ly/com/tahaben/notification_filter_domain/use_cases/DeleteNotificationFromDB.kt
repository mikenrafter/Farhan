package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.delivery.NotificationCore
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil

class DeleteNotificationFromDB(private val repository: NotificationRepository, private val core: NotificationCore, private val service: ServiceUtil) {
    suspend operator fun invoke(item: NotificationItem) = core.coordinator.clearInbox(item.id) {
        repository.deleteNotificationItemEntity(item)
        service.deleteNotificationIntent(item.id)
    }
}
