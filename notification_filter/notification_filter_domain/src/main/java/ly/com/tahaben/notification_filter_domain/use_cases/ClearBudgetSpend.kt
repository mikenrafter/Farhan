package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.delivery.NotificationCore

class ClearBudgetSpend(private val core: NotificationCore) {
    suspend operator fun invoke() = core.clearBudget()
}
