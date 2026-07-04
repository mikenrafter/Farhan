package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class GetPriorityDeliverySettings(private val preferences: Preferences) {
    operator fun invoke(): Map<NotificationPriority, PriorityDeliverySetting> =
        preferences.getPriorityDeliverySettings()
}
