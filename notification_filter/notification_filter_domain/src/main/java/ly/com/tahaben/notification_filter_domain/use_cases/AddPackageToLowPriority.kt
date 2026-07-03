package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class AddPackageToLowPriority(private val preferences: Preferences) {
    operator fun invoke(packageName: String) {
        preferences.setAppPriorityOverride(packageName, NotificationPriority.LOW)
    }
}
