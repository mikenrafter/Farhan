package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class IsAppPriorityLocked(private val preferences: Preferences) {
    operator fun invoke(packageName: String): Boolean = preferences.isAppPriorityLocked(packageName)
}
