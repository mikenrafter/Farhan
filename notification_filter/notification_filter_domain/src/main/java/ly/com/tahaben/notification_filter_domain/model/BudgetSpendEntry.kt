package ly.com.tahaben.notification_filter_domain.model

data class BudgetSpendEntry(
    val appName: String,
    val title: String,
    val priority: NotificationPriority,
    val pointCost: Int,
    val timestamp: Long,
    val periodStartMinute: Int,
)
