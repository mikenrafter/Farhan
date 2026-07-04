package ly.com.tahaben.notification_filter_domain.model

data class PriorityDeliverySetting(
    val cadence: DeliveryCadence,
    val budgetBehavior: BudgetBehavior,
) {
    companion object {
        val DEFAULTS: Map<NotificationPriority, PriorityDeliverySetting> = mapOf(
            NotificationPriority.CRITICAL to PriorityDeliverySetting(
                DeliveryCadence.IMMEDIATELY,
                BudgetBehavior.IGNORING,
            ),
            NotificationPriority.HIGH to PriorityDeliverySetting(
                DeliveryCadence.EVERY_HOUR,
                BudgetBehavior.RESPECTING,
            ),
            NotificationPriority.MEDIUM to PriorityDeliverySetting(
                DeliveryCadence.EVERY_OTHER_HOUR,
                BudgetBehavior.RESPECTING,
            ),
            NotificationPriority.LOW to PriorityDeliverySetting(
                DeliveryCadence.ONCE_DAILY,
                BudgetBehavior.RESPECTING,
            ),
        )

        fun defaultFor(priority: NotificationPriority): PriorityDeliverySetting =
            DEFAULTS[priority] ?: PriorityDeliverySetting(
                DeliveryCadence.EVERY_HOUR,
                BudgetBehavior.RESPECTING,
            )
    }
}
