package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class GetChannelPriorityOverride(private val preferences: Preferences) {
    operator fun invoke(packageName: String, channelId: String): Int {
        return preferences.getChannelPriorityOffset(packageName, channelId)
    }
}
