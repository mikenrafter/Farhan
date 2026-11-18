package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.delivery.NotificationCore
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil

class MarkNotificationAsCleared(private val core: NotificationCore, private val service: ServiceUtil) {
    suspend operator fun invoke(id: String) = core.coordinator.clearInbox(id) { service.deleteNotificationIntent(id) }
}
