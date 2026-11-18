package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryPolicy
import ly.com.tahaben.notification_filter_domain.model.CurrentBudgetStatus
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class GetCurrentBudgetStatus(private val preferences: Preferences, private val clock: java.time.Clock = ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock.systemClock) {
    operator fun invoke(): CurrentBudgetStatus {
        val snapshot = preferences.getScheduleSnapshot()
        val time = ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock.current(preferences.isDeliveryTestingMode(), clock)
        val schedule = snapshot.book.scheduleFor(java.time.LocalDate.parse(time.localDay))
        val status = statusAt(schedule, snapshot.budget, time.scheduleMinuteOfDay, time.localDay)
        val normalized = NotificationDeliveryPolicy.normalizeBudget(snapshot.budget, schedule, time.scheduleMinuteOfDay, time.localDay)
        val entries = preferences.getBudgetSpendLedgerForState(normalized)
        return status.copy(spendEntries = entries)
    }

    companion object {
        /**
         * Pure form of [invoke] with an explicit minute and budget state, so tests need no
         * Calendar or Preferences. Shares delivery normalization.
         */
        fun statusAt(
            schedule: List<ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod>,
            budget: ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState,
            minuteOfDay: Int,
            localDay: String = "",
        ): CurrentBudgetStatus {
            val active = NotificationDeliveryPolicy.findActivePeriod(schedule, minuteOfDay)
            val spent = NotificationDeliveryPolicy.normalizeBudget(budget, schedule, minuteOfDay, localDay).spentPoints
            return CurrentBudgetStatus(activePeriod = active, spentPoints = spent)
        }
    }
}
