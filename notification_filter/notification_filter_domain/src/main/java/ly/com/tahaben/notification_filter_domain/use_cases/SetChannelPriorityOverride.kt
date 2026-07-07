package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class SetChannelPriorityOverride(private val preferences: Preferences) {
    operator fun invoke(packageName: String, channelId: String, offset: Int) {
        preferences.setChannelPriorityOffset(packageName, channelId, offset)
    }
}
