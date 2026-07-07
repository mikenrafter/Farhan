package ly.com.tahaben.notification_filter_domain.use_cases

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationDashboardSummary
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import org.junit.Test

class GetNotificationDashboardSummaryTest {

    @Test
    fun `health score penalizes critical unread and standing alerts`() = runBlocking {
        val now = System.currentTimeMillis()
        val notifications = listOf(
            item("1", NotificationPriority.CRITICAL, now - 1_000, isRead = false),
            item("2", NotificationPriority.HIGH, now - 4 * 3_600_000L, isRead = false),
        )

        val summary = GetNotificationDashboardSummary(
            repository = FakeRepository(notifications),
            preferences = FakePreferences(),
        )().first()

        assertThat(summary.priorityBreakdown[NotificationPriority.CRITICAL]).isEqualTo(1)
        assertThat(summary.standingCount).isEqualTo(1)
        assertThat(summary.healthScore).isLessThan(100)
    }

    private fun item(
        id: String,
        priority: NotificationPriority,
        timestamp: Long,
        isRead: Boolean,
    ) = NotificationItem(
        id = id,
        appName = "App",
        title = "Title",
        text = "Text",
        time = "12:00",
        packageName = "com.test",
        timestamp = timestamp,
        priority = priority,
        category = NotificationCategory.OTHER,
        isRead = isRead,
    )

    private class FakeRepository(
        private val notifications: List<NotificationItem>,
    ) : NotificationRepository {
        override fun getNotifications() = flowOf(notifications)
        override suspend fun insertNotificationItemEntity(notificationItem: NotificationItem) = Unit
        override suspend fun deleteNotificationItemEntity(notificationItem: NotificationItem) = Unit
        override suspend fun deleteAllNotificationsFromDB() = Unit
        override suspend fun markNotificationRead(id: String) = Unit
        override suspend fun markAppNotificationsRead(packageName: String) = Unit
        override fun getClearedNotifications() = flowOf(emptyList<NotificationItem>())
        override suspend fun markNotificationAsCleared(id: String) = Unit
        override suspend fun markNotificationAsDelivered(id: String) = Unit
        override suspend fun getUndeliveredNotificationsUpToPriority(priorityLevel: Int) = emptyList<NotificationItem>()
        override suspend fun getPackageNotificationCountSince(packageName: String, since: Long) = 0
        override suspend fun getAllNotifications() = notifications
    }

    private class FakePreferences : Preferences {
        override fun getStandingThresholdHours() = 3
        override fun getFloodThreshold() = 20
        override fun loadShouldShowOnBoarding() = false
        override fun saveShouldShowOnBoarding(shouldShow: Boolean) = Unit
        override fun loadShouldShowcase() = false
        override fun saveShouldShowcase(shouldShow: Boolean) = Unit
        override suspend fun isServiceEnabled() = true
        override fun isNotificationServiceStatsEnabled() = true
        override fun setServiceState(isEnabled: Boolean) = Unit
        override fun savePackageToNotificationExceptions(packageName: String) = Unit
        override fun removePackageFromNotificationExceptions(packageName: String) = Unit
        override fun isPackageInNotificationExceptions(packageName: String) = false
        override fun getNotificationFilterExceptionsList(): Set<String> = emptySet()
        override fun setNotifyMeTime(hour: Int, minutes: Int) = Unit
        override fun isNotifyMeScheduledToday() = false
        override fun setNotifyMeScheduledDate(date: Long) = Unit
        override fun getNotifyMeHours() = -1
        override fun getNotifyMeMinutes() = 0
        override fun getSettingsShouldShowWarning() = false
        override fun setSettingsShouldShowWarning(shouldShow: Boolean) = Unit
        override fun getAppPriorityOverride(packageName: String) = null
        override fun setAppPriorityOverride(packageName: String, priority: NotificationPriority?) = Unit
        override fun getBatchWindowMinutes() = 15
        override fun setBatchWindowMinutes(minutes: Int) = Unit
        override fun setStandingThresholdHours(hours: Int) = Unit
        override fun setFloodThreshold(count: Int) = Unit
        override fun isAppPriorityLocked(packageName: String) = false
        override fun setAppPriorityLocked(packageName: String, locked: Boolean) = Unit
        override fun getCategoryPriorityOverride(category: NotificationCategory) = null
        override fun setCategoryPriorityOverride(category: NotificationCategory, priority: NotificationPriority?) = Unit
        override fun getChannelPriorityOverride(packageName: String, channelId: String) = null
        override fun setChannelPriorityOverride(packageName: String, channelId: String, priority: NotificationPriority?) = Unit
        override fun getNotificationSchedule() =
            ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod.DEFAULT_SCHEDULE
        override fun setNotificationSchedule(schedule: List<ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod>) = Unit
        override fun getBudgetSpentCurrentPeriod() = 0
        override fun setBudgetSpentCurrentPeriod(points: Int) = Unit
        override fun getCurrentBudgetPeriodStartMinute() = -1
        override fun setCurrentBudgetPeriodStartMinute(minuteOfDay: Int) = Unit
        override fun getBudgetSpendLedger(periodStartMinute: Int) = emptyList<ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry>()
        override fun addBudgetSpendEntry(entry: ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry) = Unit
        override fun clearBudgetSpendLedgerBefore(periodStartMinute: Int) = Unit
        override fun clearAllBudgetSpend() = Unit
        override fun getHourlyPassthroughCount() = 0
        override fun setHourlyPassthroughCount(count: Int) = Unit
        override fun getPassthroughWindowHour() = -1
        override fun setPassthroughWindowHour(hour: Int) = Unit
        override fun getPriorityDeliverySettings() = PriorityDeliverySetting.DEFAULTS
        override fun setPriorityDeliverySetting(
            priority: NotificationPriority,
            setting: PriorityDeliverySetting,
        ) = Unit
        override fun isDeliveryTestingMode() = false
        override fun setDeliveryTestingMode(enabled: Boolean) = Unit
        override fun getKnownPackageSources() = emptyMap<String, ly.com.tahaben.notification_filter_domain.model.KnownPackageSource>()
        override fun setKnownPackageSources(sources: List<ly.com.tahaben.notification_filter_domain.model.KnownPackageSource>) = Unit
        override fun shouldPassThroughNotification(packageName: String) = false
    }
}
