package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.delivery.DeliveryTimeContext
import ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryPolicy
import ly.com.tahaben.notification_filter_domain.model.CurrentBudgetStatus
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import java.util.Calendar

class GetCurrentBudgetStatus(private val preferences: Preferences) {
    operator fun invoke(): CurrentBudgetStatus {
        val schedule = preferences.getNotificationSchedule()
        val cal = Calendar.getInstance()
        val timeContext = DeliveryTimeContext.from(
            calendarHour = cal.get(Calendar.HOUR_OF_DAY),
            minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            dayOfYear = cal.get(Calendar.DAY_OF_YEAR),
            testingMode = preferences.isDeliveryTestingMode(),
        )
        val activePeriod = NotificationDeliveryPolicy.findActivePeriod(
            schedule,
            timeContext.scheduleMinuteOfDay,
        )
        val periodStartMinute = activePeriod?.startMinuteOfDay
        val storedPeriodStart = preferences.getCurrentBudgetPeriodStartMinute()
        val spentPoints = if (periodStartMinute != null && storedPeriodStart == periodStartMinute) {
            preferences.getBudgetSpentCurrentPeriod()
        } else {
            0
        }
        val spendEntries = if (periodStartMinute != null) {
            preferences.getBudgetSpendLedger(periodStartMinute)
        } else {
            emptyList()
        }
        return CurrentBudgetStatus(
            activePeriod = activePeriod,
            spentPoints = spentPoints,
            spendEntries = spendEntries,
        )
    }

    companion object {
        /**
         * Pure form of [invoke] with an explicit minute and budget state, so tests need no
         * Calendar or Preferences. RED STUB: same logic as [invoke], which keys spend by the
         * stored period start.
         */
        fun statusAt(
            schedule: List<ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod>,
            budget: ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState,
            minuteOfDay: Int,
        ): CurrentBudgetStatus {
            val active = NotificationDeliveryPolicy.findActivePeriod(schedule, minuteOfDay)
            val spent = if (active != null && budget.periodStartMinute == active.startMinuteOfDay) {
                budget.spentPoints
            } else {
                0
            }
            return CurrentBudgetStatus(activePeriod = active, spentPoints = spent)
        }
    }
}
