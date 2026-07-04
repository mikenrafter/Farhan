package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class SetDeliveryTestingMode(private val preferences: Preferences) {
    operator fun invoke(enabled: Boolean) {
        preferences.setDeliveryTestingMode(enabled)
    }
}
