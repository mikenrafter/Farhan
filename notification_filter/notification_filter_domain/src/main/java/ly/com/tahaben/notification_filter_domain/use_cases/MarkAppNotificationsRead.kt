package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository

class MarkAppNotificationsRead(private val repository: NotificationRepository) {
    suspend operator fun invoke(packageName: String) {
        repository.markAppNotificationsRead(packageName)
    }
}
