package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class SetDashboardSettings(private val preferences: Preferences) {
    fun setBatchWindowMinutes(minutes: Int) = preferences.setBatchWindowMinutes(minutes)
    fun setStandingThresholdHours(hours: Int) = preferences.setStandingThresholdHours(hours)
    fun setFloodThreshold(count: Int) = preferences.setFloodThreshold(count)
}
