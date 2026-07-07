package ly.com.tahaben.notification_filter_domain.use_cases

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.model.AppStoreCategory
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
    fun `channel offset promotes above app override`() {
        preferences.setAppPriorityOverride("com.app", NotificationPriority.LOW)
        // LOW.level=4, CRITICAL.level=1, offset = 4-1 = 3
        preferences.setChannelPriorityOffset("com.app", "alerts", 3)

        val (priority, category) = classify("msg", "com.app", "alerts")

        assertThat(priority).isEqualTo(NotificationPriority.CRITICAL)
        assertThat(category).isEqualTo(NotificationCategory.COMMUNICATION)
    }

    @Test
    fun `channel offset clamps at low when app is demoted`() {
        // App is medium (3), channel offset = -1 (channel is low), app demoted to low (4)
        // effective = 4 - (-1) = 5, clamped to 4 = LOW
        preferences.setAppPriorityOverride("com.app", NotificationPriority.LOW)
        preferences.setChannelPriorityOffset("com.app", "promo", -1)

        val (priority, _) = classify(null, "com.app", "promo")

        assertThat(priority).isEqualTo(NotificationPriority.LOW)
    }

    @Test
    fun `channel offset tracks app when app promoted`() {
        // App promoted to HIGH (2), channel has offset -1 → effective = 2-(-1) = 3 = MEDIUM
        preferences.setAppPriorityOverride("com.app", NotificationPriority.HIGH)
        preferences.setChannelPriorityOffset("com.app", "promo", -1)

        val (priority, _) = classify(null, "com.app", "promo")

        assertThat(priority).isEqualTo(NotificationPriority.MEDIUM)
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

    @Test
    fun `app store category provides default when notification category missing`() {
        val (priority, category) = classify.classifyForApp(
            packageName = "com.game",
            androidCategories = emptySet(),
            appCategoryCode = AppStoreCategory.GAME,
        )

        assertThat(category).isEqualTo(NotificationCategory.ENTERTAINMENT)
        assertThat(priority).isEqualTo(NotificationPriority.LOW)
    }

    private class FakePreferences : Preferences {
        private val appOverrides = mutableMapOf<String, NotificationPriority>()
        private val channelOffsets = mutableMapOf<Pair<String, String>, Int>()
        private val lockedApps = mutableSetOf<String>()

        override fun getChannelPriorityOffset(packageName: String, channelId: String) =
            channelOffsets[packageName to channelId] ?: 0

        override fun setChannelPriorityOffset(packageName: String, channelId: String, offset: Int) {
            if (offset == 0) channelOffsets.remove(packageName to channelId)
            else channelOffsets[packageName to channelId] = offset
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
