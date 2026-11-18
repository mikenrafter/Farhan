package ly.com.tahaben.notification_filter_domain.delivery

import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting

data class DeliveryBudgetState(
    val spentPoints: Int = 0,
    val periodStartMinute: Int = -1,
    val hourlyCount: Int = 0,
    val passthroughWindowHour: Int = -1,
)

data class DeliveryDecision(
    val canDeliver: Boolean,
    val updatedBudget: DeliveryBudgetState,
)

object NotificationDeliveryPolicy {

    const val MAX_HOURLY_PASSTHROUGH = 10

    fun findActivePeriod(
        schedule: List<NotificationSchedulePeriod>,
        minuteOfDay: Int,
    ): NotificationSchedulePeriod? = schedule.firstOrNull { period ->
        val startMin = period.startHour * 60 + period.startMinute
        val endMin = period.endHour * 60 + period.endMinute
        minuteOfDay in startMin until endMin
    }

    fun isBatchTickForCadence(cadence: DeliveryCadence, timeContext: DeliveryTimeContext): Boolean =
        when (cadence) {
            DeliveryCadence.IMMEDIATELY -> false
            DeliveryCadence.EVERY_HOUR -> true
            DeliveryCadence.EVERY_OTHER_HOUR -> timeContext.boundaryIndex % 2 == 0
            DeliveryCadence.EVERY_THIRD_HOUR -> timeContext.boundaryIndex % 3 == 0
            DeliveryCadence.ONCE_DAILY -> timeContext.boundaryIndex == 0
        }

    fun shouldDeliverOnArrival(setting: PriorityDeliverySetting): Boolean =
        setting.cadence == DeliveryCadence.IMMEDIATELY

    fun shouldDeliverOnBatchTick(
        priority: NotificationPriority,
        settings: Map<NotificationPriority, PriorityDeliverySetting>,
        timeContext: DeliveryTimeContext,
    ): Boolean {
        val setting = settings[priority] ?: PriorityDeliverySetting.defaultFor(priority)
        return setting.cadence != DeliveryCadence.IMMEDIATELY &&
            isBatchTickForCadence(setting.cadence, timeContext)
    }

    fun canDeliverNow(
        priority: NotificationPriority,
        setting: PriorityDeliverySetting,
        schedule: List<NotificationSchedulePeriod>,
        timeContext: DeliveryTimeContext,
        budget: DeliveryBudgetState,
    ): DeliveryDecision {
        val budgetWithHour = if (budget.passthroughWindowHour != timeContext.passthroughWindowKey) {
            budget.copy(passthroughWindowHour = timeContext.passthroughWindowKey, hourlyCount = 0)
        } else {
            budget
        }

        if (setting.budgetBehavior == BudgetBehavior.IGNORING) {
            return DeliveryDecision(canDeliver = true, updatedBudget = budgetWithHour)
        }

        if (budgetWithHour.hourlyCount >= MAX_HOURLY_PASSTHROUGH) {
            return DeliveryDecision(canDeliver = false, updatedBudget = budgetWithHour)
        }

        val period = findActivePeriod(schedule, timeContext.scheduleMinuteOfDay)
            ?: return DeliveryDecision(canDeliver = false, updatedBudget = budgetWithHour)

        if (period.budgetPoints == 0) {
            return DeliveryDecision(canDeliver = false, updatedBudget = budgetWithHour)
        }

        val periodStartMinute = period.startHour * 60 + period.startMinute
        val spentPoints = resolveSpentPoints(budgetWithHour, periodStartMinute)
        val budgetWithPeriod = budgetWithHour.copy(
            spentPoints = spentPoints,
            periodStartMinute = periodStartMinute,
        )
        val canDeliver = spentPoints + priority.pointCost <= period.budgetPoints

        return DeliveryDecision(canDeliver = canDeliver, updatedBudget = budgetWithPeriod)
    }

    fun recordPassthrough(
        priority: NotificationPriority,
        setting: PriorityDeliverySetting,
        schedule: List<NotificationSchedulePeriod>,
        minuteOfDay: Int,
        budget: DeliveryBudgetState,
    ): DeliveryBudgetState {
        if (setting.budgetBehavior == BudgetBehavior.IGNORING) {
            return budget.copy(hourlyCount = budget.hourlyCount + 1)
        }

        val period = findActivePeriod(schedule, minuteOfDay)
        val periodStartMinute = period?.let { it.startHour * 60 + it.startMinute }
            ?: budget.periodStartMinute
        val spentPoints = if (period != null) {
            val resolved = resolveSpentPoints(budget, periodStartMinute)
            resolved + priority.pointCost
        } else {
            budget.spentPoints
        }
        return budget.copy(
            hourlyCount = budget.hourlyCount + 1,
            spentPoints = spentPoints,
            periodStartMinute = periodStartMinute ?: budget.periodStartMinute,
        )
    }

    fun selectForBatchDelivery(
        candidates: List<NotificationItem>,
        settings: Map<NotificationPriority, PriorityDeliverySetting>,
        schedule: List<NotificationSchedulePeriod>,
        timeContext: DeliveryTimeContext,
        budget: DeliveryBudgetState,
        queuePreferences: Map<String, AppQueuePreference> = emptyMap(),
    ): Pair<List<NotificationItem>, DeliveryBudgetState> {
        val eligible = candidates.filter { item ->
            shouldDeliverOnBatchTick(item.priority, settings, timeContext)
        }
        if (eligible.isEmpty()) return emptyList<NotificationItem>() to budget

        val period = findActivePeriod(schedule, timeContext.scheduleMinuteOfDay)
        val periodStartMinute = period?.let { it.startHour * 60 + it.startMinute }
            ?: budget.periodStartMinute

        var spentPoints = resolveSpentPoints(budget, periodStartMinute)
        var hourlyCount = if (budget.passthroughWindowHour == timeContext.passthroughWindowKey) {
            budget.hourlyCount
        } else {
            0
        }
        val delivered = mutableListOf<NotificationItem>()

        val sorted = eligible.sortedWith(
            compareBy<NotificationItem> { it.priority.level }.thenBy { it.timestamp }
        )

        for (item in sorted) {
            val setting = settings[item.priority] ?: PriorityDeliverySetting.defaultFor(item.priority)
            val cost = item.priority.pointCost
            val ignoresBudget = setting.budgetBehavior == BudgetBehavior.IGNORING

            if (!ignoresBudget) {
                if (period == null || period.budgetPoints == 0) continue
                if (hourlyCount >= MAX_HOURLY_PASSTHROUGH) break
                if (spentPoints + cost > period.budgetPoints) continue
            }

            delivered += item
            if (!ignoresBudget) {
                spentPoints += cost
                hourlyCount++
            }
        }

        return delivered to budget.copy(
            spentPoints = spentPoints,
            hourlyCount = hourlyCount,
            periodStartMinute = periodStartMinute,
            passthroughWindowHour = timeContext.passthroughWindowKey,
        )
    }

    private fun resolveSpentPoints(budget: DeliveryBudgetState, periodStartMinute: Int): Int =
        if (budget.periodStartMinute == periodStartMinute) budget.spentPoints else 0
}
