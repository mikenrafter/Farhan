package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class GetDashboardSettings(private val preferences: Preferences) {
    fun getBatchWindowMinutes(): Int = preferences.getBatchWindowMinutes()
    fun getStandingThresholdHours(): Int = preferences.getStandingThresholdHours()
    fun getFloodThreshold(): Int = preferences.getFloodThreshold()
}
