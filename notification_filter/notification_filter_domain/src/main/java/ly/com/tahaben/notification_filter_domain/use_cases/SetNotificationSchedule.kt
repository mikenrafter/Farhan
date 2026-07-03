package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class SetNotificationSchedule(private val preferences: Preferences) {
    operator fun invoke(schedule: List<NotificationSchedulePeriod>) {
        preferences.setNotificationSchedule(schedule)
    }
}
