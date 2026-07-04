package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class AddPackageToNotificationException(
    private val sharedPref: Preferences,
    private val setAppPriorityOverride: SetAppPriorityOverride,
) {
    operator fun invoke(packageName: String) {
        setAppPriorityOverride(packageName, ly.com.tahaben.notification_filter_domain.model.NotificationPriority.CRITICAL)
    }
}