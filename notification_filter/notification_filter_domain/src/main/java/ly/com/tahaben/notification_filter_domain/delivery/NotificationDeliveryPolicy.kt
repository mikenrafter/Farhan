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
    val localDay: String = "",
    val accountingId: String = "",
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
        val current = normalizeBudget(budget, schedule, timeContext.scheduleMinuteOfDay, timeContext.localDay)
        val budgetWithHour = if (current.passthroughWindowHour != timeContext.passthroughWindowKey) {
            current.copy(passthroughWindowHour = timeContext.passthroughWindowKey, hourlyCount = 0)
        } else {
            current
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
        val canDeliver = spentPoints.toLong() + priority.pointCost <= period.budgetPoints.toLong()

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

        val normalized = normalizeBudget(budget, schedule, timeContext.scheduleMinuteOfDay, timeContext.localDay)
        var spentPoints = normalized.spentPoints
        var hourlyCount = if (normalized.passthroughWindowHour == timeContext.passthroughWindowKey) {
            normalized.hourlyCount
        } else {
            0
        }
        val delivered = mutableListOf<NotificationItem>()

        val sorted = orderCandidates(eligible, queuePreferences)

        for (item in sorted) {
            val setting = settings[item.priority] ?: PriorityDeliverySetting.defaultFor(item.priority)
            val cost = item.priority.pointCost
            val ignoresBudget = setting.budgetBehavior == BudgetBehavior.IGNORING

            if (!ignoresBudget) {
                if (period == null || period.budgetPoints == 0) continue
                if (hourlyCount >= MAX_HOURLY_PASSTHROUGH) continue
                if (spentPoints.toLong() + cost > period.budgetPoints.toLong()) continue
            }

            delivered += item
            if (!ignoresBudget) {
                spentPoints += cost
                hourlyCount++
            }
        }

        return delivered to normalized.copy(
            spentPoints = spentPoints,
            hourlyCount = hourlyCount,
            periodStartMinute = periodStartMinute,
            passthroughWindowHour = timeContext.passthroughWindowKey,
        )
    }

    /** Calendar identity is independent of editable boundaries; unknown legacy day binds on first use.
     * CLAIM(day-budget): [NotificationCoreTest]. ScheduleWriter rebases the active start under DeliveryGate.
     */
    fun normalizeBudget(budget: DeliveryBudgetState, schedule: List<NotificationSchedulePeriod>, minute: Int, localDay: String = ""): DeliveryBudgetState {
        val start = findActivePeriod(schedule, minute)?.startMinuteOfDay ?: -1
        val dayChanged = localDay.isNotEmpty() && budget.localDay.isNotEmpty() && localDay != budget.localDay
        return budget.copy(
            spentPoints = if (!dayChanged && start == budget.periodStartMinute) budget.spentPoints else 0,
            periodStartMinute = start,
            hourlyCount = if (dayChanged) 0 else budget.hourlyCount,
            passthroughWindowHour = if (dayChanged) -1 else budget.passthroughWindowHour,
            localDay = localDay.ifEmpty { budget.localDay },
            accountingId = if (dayChanged || start != budget.periodStartMinute || budget.accountingId.isEmpty())
                "${localDay.ifEmpty { budget.localDay }}:$start" else budget.accountingId,
        )
    }

    /** App heads compete by priority, descending rank, recency, then stable package/key ties [QueueOrderTest]. */
    fun orderCandidates(candidates: List<NotificationItem>, preferences: Map<String, AppQueuePreference>): List<NotificationItem> {
        val heads = compareBy<NotificationItem> { it.priority.level }
            .thenByDescending { preferences[it.packageName]?.rank ?: 0 }
            .thenByDescending { it.timestamp }.thenBy { it.packageName }.thenBy { it.id }
        val queues = candidates.groupBy { it.priority to it.packageName }.values.map { appItems ->
            val oldest = preferences[appItems.first().packageName]?.order == QueueOrder.OLDEST_FIRST
            java.util.ArrayDeque(appItems.sortedWith(
                if (oldest) compareBy<NotificationItem> { it.timestamp }.thenBy { it.id }
                else compareByDescending<NotificationItem> { it.timestamp }.thenBy { it.id }))
        }
        val pending = java.util.PriorityQueue<java.util.ArrayDeque<NotificationItem>> { a, b -> heads.compare(a.first, b.first) }
        pending.addAll(queues)
        return buildList {
            while (pending.isNotEmpty()) {
                val queue = pending.remove()
                add(queue.removeFirst())
                if (queue.isNotEmpty()) pending.add(queue)
            }
        }
    }

    private fun resolveSpentPoints(budget: DeliveryBudgetState, periodStartMinute: Int): Int =
        if (budget.periodStartMinute == periodStartMinute) budget.spentPoints else 0
}
