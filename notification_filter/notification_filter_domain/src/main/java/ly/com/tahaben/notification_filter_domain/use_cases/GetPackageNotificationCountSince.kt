package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository

class GetPackageNotificationCountSince(private val repository: NotificationRepository) {
    suspend operator fun invoke(packageName: String, since: Long): Int =
        repository.getPackageNotificationCountSince(packageName, since)
}
