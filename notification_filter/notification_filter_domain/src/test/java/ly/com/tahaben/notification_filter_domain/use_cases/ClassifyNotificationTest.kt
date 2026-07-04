package ly.com.tahaben.notification_filter_domain.use_cases

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import org.junit.Before
import org.junit.Test

class ClassifyNotificationTest {

    private lateinit var preferences: FakePreferences
    private lateinit var classify: ClassifyNotification

    @Before
    fun setUp() {
        preferences = FakePreferences()
        classify = ClassifyNotification(preferences)
    }

    @Test
    fun `channel override beats app override`() {
        preferences.setAppPriorityOverride("com.app", NotificationPriority.LOW)
        preferences.setChannelPriorityOverride("com.app", "alerts", NotificationPriority.CRITICAL)

        val (priority, category) = classify("msg", "com.app", "alerts")

        assertThat(priority).isEqualTo(NotificationPriority.CRITICAL)
        assertThat(category).isEqualTo(NotificationCategory.COMMUNICATION)
    }

    @Test
    fun `locked app uses override without further changes`() {
        preferences.setAppPriorityOverride("com.app", NotificationPriority.CRITICAL)
        preferences.setAppPriorityLocked("com.app", true)

        val (priority, _) = classify("social", "com.app", null)

        assertThat(priority).isEqualTo(NotificationPriority.CRITICAL)
    }

    @Test
    fun `message category defaults to communication high`() {
        val (priority, category) = classify("msg", "com.app", null)

        assertThat(priority).isEqualTo(NotificationPriority.HIGH)
        assertThat(category).isEqualTo(NotificationCategory.COMMUNICATION)
    }

    @Test
    fun `unknown category defaults to other medium`() {
        val (priority, category) = classify(null, "com.app", null)

        assertThat(priority).isEqualTo(NotificationPriority.MEDIUM)
        assertThat(category).isEqualTo(NotificationCategory.OTHER)
    }

    private class FakePreferences : Preferences {
        private val appOverrides = mutableMapOf<String, NotificationPriority>()
        private val channelOverrides = mutableMapOf<Pair<String, String>, NotificationPriority>()
        private val lockedApps = mutableSetOf<String>()

        override fun getChannelPriorityOverride(packageName: String, channelId: String) =
            channelOverrides[packageName to channelId]

        override fun setChannelPriorityOverride(packageName: String, channelId: String, priority: NotificationPriority?) {
            if (priority == null) channelOverrides.remove(packageName to channelId)
            else channelOverrides[packageName to channelId] = priority
        }

        override fun isAppPriorityLocked(packageName: String) = packageName in lockedApps

        override fun setAppPriorityLocked(packageName: String, locked: Boolean) {
            if (locked) lockedApps += packageName else lockedApps -= packageName
        }

        override fun getAppPriorityOverride(packageName: String) = appOverrides[packageName]

        override fun setAppPriorityOverride(packageName: String, priority: NotificationPriority?) {
            if (priority == null) appOverrides.remove(packageName)
            else appOverrides[packageName] = priority
        }

        override fun getCategoryPriorityOverride(category: NotificationCategory) = null

        override fun setCategoryPriorityOverride(category: NotificationCategory, priority: NotificationPriority?) = Unit

        override fun loadShouldShowOnBoarding() = false
        override fun saveShouldShowOnBoarding(shouldShow: Boolean) = Unit
        override fun loadShouldShowcase() = false
        override fun saveShouldShowcase(shouldShow: Boolean) = Unit
        override suspend fun isServiceEnabled() = true
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
        override fun getBatchWindowMinutes() = 15
        override fun setBatchWindowMinutes(minutes: Int) = Unit
        override fun getStandingThresholdHours() = 3
        override fun setStandingThresholdHours(hours: Int) = Unit
        override fun getFloodThreshold() = 20
        override fun setFloodThreshold(count: Int) = Unit
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
