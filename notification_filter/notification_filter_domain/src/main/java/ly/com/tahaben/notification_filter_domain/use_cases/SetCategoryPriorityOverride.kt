package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class SetCategoryPriorityOverride(private val preferences: Preferences) {
    operator fun invoke(category: NotificationCategory, priority: NotificationPriority?) {
        preferences.setCategoryPriorityOverride(category, priority)
    }
}
