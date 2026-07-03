package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class SetAppPriorityLocked(private val preferences: Preferences) {
    operator fun invoke(packageName: String, locked: Boolean) {
        preferences.setAppPriorityLocked(packageName, locked)
    }
}
