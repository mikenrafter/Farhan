package ly.com.tahaben.notification_filter_domain.model

data class CurrentBudgetStatus(
    val activePeriod: NotificationSchedulePeriod? = null,
    val spentPoints: Int = 0,
    val spendEntries: List<BudgetSpendEntry> = emptyList(),
) {
    val totalBudget: Int get() = activePeriod?.budgetPoints ?: 0
    val remainingPoints: Int get() = (totalBudget - spentPoints).coerceAtLeast(0)
    val hasBudget: Boolean get() = totalBudget > 0
}
