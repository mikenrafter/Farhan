package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class GetCategoryPriorityOverride(private val preferences: Preferences) {
    operator fun invoke(category: NotificationCategory): NotificationPriority =
        preferences.getCategoryPriorityOverride(category) ?: category.defaultPriority
}
