package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository

class MarkNotificationRead(private val repository: NotificationRepository) {
    suspend operator fun invoke(id: String) {
        repository.markNotificationRead(id)
    }
}
