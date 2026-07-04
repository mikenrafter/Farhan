package ly.com.tahaben.notification_filter_data.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryTimeContext
import ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryPolicy
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationDeliveryEngine @Inject constructor(
    private val preferences: Preferences,
    private val useCases: NotificationFilterUseCases,
    private val deliveryScheduler: NotificationDeliveryScheduler,
) {

    suspend fun maintainDeliverySchedule(context: Context) {
        if (!useCases.checkIfNotificationServiceIsEnabled()) return
        deliverPendingNotifications(context)
        deliveryScheduler.scheduleNextBoundaryDelivery(context)
    }

    suspend fun deliverPendingNotifications(context: Context) {
        if (!useCases.checkIfNotificationServiceIsEnabled()) return
        if (!canPostNotifications(context)) {
            Timber.w("Skipping Farhan delivery — POST_NOTIFICATIONS not granted")
            return
        }

        val timeContext = currentTimeContext()
        val schedule = preferences.getNotificationSchedule()
        val deliverySettings = preferences.getPriorityDeliverySettings()

        var budget = readDeliveryBudget().let { current ->
            if (current.passthroughWindowHour != timeContext.passthroughWindowKey) {
                current.copy(passthroughWindowHour = timeContext.passthroughWindowKey, hourlyCount = 0)
            } else {
                current
            }
        }

        val undelivered = useCases.getUndeliveredNotifications(NotificationPriority.LOW.level)
        val (delivered, budgetAfterDelivery) = NotificationDeliveryPolicy.selectForBatchDelivery(
            candidates = undelivered,
            settings = deliverySettings,
            schedule = schedule,
            timeContext = timeContext,
            budget = budget,
        )
        budget = budgetAfterDelivery
        for (item in delivered) {
            NotificationService.postProxyNotification(context, item.id, item.appName, item.title, item.text)
            useCases.markNotificationAsDelivered(item.id)
            val setting = deliverySettings[item.priority]
                ?: PriorityDeliverySetting.defaultFor(item.priority)
            recordBudgetSpend(item, item.priority, setting)
        }
        if (delivered.isNotEmpty()) {
            Timber.i("Farhan batch-delivered ${delivered.size} notification(s)")
        }

        writeDeliveryBudget(budget)
    }

    suspend fun tryImmediateDelivery(
        context: Context,
        item: NotificationItem,
        effectivePriority: NotificationPriority,
    ) {
        if (!canPostNotifications(context)) return

        val deliverySettings = preferences.getPriorityDeliverySettings()
        val setting = deliverySettings[effectivePriority]
            ?: PriorityDeliverySetting.defaultFor(effectivePriority)
        if (NotificationDeliveryPolicy.shouldDeliverOnArrival(setting) &&
            canDeliverNow(effectivePriority, setting)
        ) {
            NotificationService.postProxyNotification(context, item.id, item.appName, item.title, item.text)
            useCases.markNotificationAsDelivered(item.id)
            recordPassthrough(effectivePriority, setting)
            recordBudgetSpend(item, effectivePriority, setting)
        }
    }

    private fun canDeliverNow(priority: NotificationPriority, setting: PriorityDeliverySetting): Boolean {
        val timeContext = currentTimeContext()
        val decision = NotificationDeliveryPolicy.canDeliverNow(
            priority = priority,
            setting = setting,
            schedule = preferences.getNotificationSchedule(),
            timeContext = timeContext,
            budget = readDeliveryBudget(),
        )
        writeDeliveryBudget(decision.updatedBudget)
        return decision.canDeliver
    }

    private fun recordPassthrough(priority: NotificationPriority, setting: PriorityDeliverySetting) {
        val timeContext = currentTimeContext()
        val updated = NotificationDeliveryPolicy.recordPassthrough(
            priority = priority,
            setting = setting,
            schedule = preferences.getNotificationSchedule(),
            minuteOfDay = timeContext.scheduleMinuteOfDay,
            budget = readDeliveryBudget(),
        )
        writeDeliveryBudget(updated)
    }

    private fun currentTimeContext(): DeliveryTimeContext {
        val cal = Calendar.getInstance()
        return DeliveryTimeContext.from(
            calendarHour = cal.get(Calendar.HOUR_OF_DAY),
            minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            dayOfYear = cal.get(Calendar.DAY_OF_YEAR),
            testingMode = preferences.isDeliveryTestingMode(),
        )
    }

    private fun readDeliveryBudget(): DeliveryBudgetState = DeliveryBudgetState(
        spentPoints = preferences.getBudgetSpentCurrentPeriod(),
        periodStartMinute = preferences.getCurrentBudgetPeriodStartMinute(),
        hourlyCount = preferences.getHourlyPassthroughCount(),
        passthroughWindowHour = preferences.getPassthroughWindowHour(),
    )

    private fun writeDeliveryBudget(budget: DeliveryBudgetState) {
        val previousPeriodStart = preferences.getCurrentBudgetPeriodStartMinute()
        if (budget.periodStartMinute != previousPeriodStart && budget.periodStartMinute >= 0) {
            preferences.clearBudgetSpendLedgerBefore(budget.periodStartMinute)
        }
        preferences.setBudgetSpentCurrentPeriod(budget.spentPoints)
        preferences.setCurrentBudgetPeriodStartMinute(budget.periodStartMinute)
        preferences.setHourlyPassthroughCount(budget.hourlyCount)
        preferences.setPassthroughWindowHour(budget.passthroughWindowHour)
    }

    private fun recordBudgetSpend(
        item: NotificationItem,
        priority: NotificationPriority,
        setting: PriorityDeliverySetting,
    ) {
        if (setting.budgetBehavior == BudgetBehavior.IGNORING || priority.pointCost == 0) return

        val timeContext = currentTimeContext()
        val period = NotificationDeliveryPolicy.findActivePeriod(
            schedule = preferences.getNotificationSchedule(),
            minuteOfDay = timeContext.scheduleMinuteOfDay,
        ) ?: return

        preferences.addBudgetSpendEntry(
            BudgetSpendEntry(
                appName = item.appName.orEmpty(),
                title = item.title.orEmpty(),
                priority = priority,
                pointCost = priority.pointCost,
                timestamp = System.currentTimeMillis(),
                periodStartMinute = period.startMinuteOfDay,
            )
        )
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
