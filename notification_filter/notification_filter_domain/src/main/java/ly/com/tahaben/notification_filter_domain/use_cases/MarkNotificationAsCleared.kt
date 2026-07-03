package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository

class MarkNotificationAsCleared(private val repository: NotificationRepository) {
    suspend operator fun invoke(id: String) = repository.markNotificationAsCleared(id)
}
