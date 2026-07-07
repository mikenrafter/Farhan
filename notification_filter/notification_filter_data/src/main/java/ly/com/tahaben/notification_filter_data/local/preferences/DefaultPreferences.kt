package ly.com.tahaben.notification_filter_data.local.preferences

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ly.com.tahaben.core.util.GlobalKey
import ly.com.tahaben.notification_filter_domain.model.AppStoreCategory
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.KnownPackageSource
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Calendar

class DefaultPreferences(
    private val sharedPref: SharedPreferences,
    private val dataStore: DataStore<androidx.datastore.preferences.core.Preferences>
) : Preferences {

    private val mainSwitchKey = booleanPreferencesKey(GlobalKey.Pref_KEY_APP_MAIN_SWITCH)

    @Volatile
    private var serviceEnabledCache: Boolean? = null

    @Volatile
    private var scheduleCache: List<NotificationSchedulePeriod>? = null

    @Volatile
    private var deliverySettingsCache: Map<NotificationPriority, PriorityDeliverySetting>? = null

    private val pendingLedgerEntries = ArrayList<BudgetSpendEntry>()
    private val ledgerLock = Any()

    @SuppressLint("ApplySharedPref")
    override fun saveShouldShowOnBoarding(shouldShow: Boolean) {
        //need to use commit to make sure the value is saved because we might read
        // the value again as soon as we finish calling this function
        sharedPref.edit()
            .putBoolean(Preferences.KEY_NOTIFICATION_FILTER_SHOULD_SHOW_ON_BOARDING, shouldShow)
            .commit()
    }

    override fun loadShouldShowOnBoarding(): Boolean {
        return sharedPref.getBoolean(
            Preferences.KEY_NOTIFICATION_FILTER_SHOULD_SHOW_ON_BOARDING,
            true
        )
    }

    override fun loadShouldShowcase(): Boolean {
        return sharedPref.getBoolean(
            Preferences.KEY_NOTIFICATION_FILTER_SHOULD_SHOWCASE,
            true
        )
    }

    override fun saveShouldShowcase(shouldShow: Boolean) {
        sharedPref.edit()
            .putBoolean(Preferences.KEY_NOTIFICATION_FILTER_SHOULD_SHOWCASE, shouldShow)
            .apply()
    }

    override suspend fun isServiceEnabled(): Boolean {
        serviceEnabledCache?.let { return it }
        val enabled = isNotificationServiceStatsEnabled() &&
            dataStore.data.map { data -> data[mainSwitchKey] ?: true }.first()
        serviceEnabledCache = enabled
        return enabled
    }

    override fun isNotificationServiceStatsEnabled(): Boolean =
        sharedPref.getBoolean(Preferences.KEY_NOTIFICATION_SERVICE_STATS, false)

    override fun setServiceState(isEnabled: Boolean) {
        sharedPref.edit()
            .putBoolean(Preferences.KEY_NOTIFICATION_SERVICE_STATS, isEnabled)
            .apply()
        serviceEnabledCache = null
    }

    override fun savePackageToNotificationExceptions(packageName: String) {
        Timber.d("packageName: $packageName")
        val savedSet: MutableSet<String>? =
            sharedPref.getStringSet(
                Preferences.KEY_NOTIFICATION_FILTER_EXCEPTIONS,
                mutableSetOf<String>()
            )
        Timber.d("old set: $savedSet")
        val newSet = savedSet?.toMutableSet()
        newSet?.add(packageName)
        Timber.d("new set: $newSet")
        sharedPref.edit()
            .putStringSet(Preferences.KEY_NOTIFICATION_FILTER_EXCEPTIONS, newSet)
            .apply()
    }

    override fun isPackageInNotificationExceptions(packageName: String): Boolean {
        val set = sharedPref.getStringSet(
            Preferences.KEY_NOTIFICATION_FILTER_EXCEPTIONS,
            emptySet<String>()
        )
        return set?.contains(packageName) == true
    }

    override fun removePackageFromNotificationExceptions(packageName: String) {
        val savedSet: MutableSet<String>? =
            sharedPref.getStringSet(
                Preferences.KEY_NOTIFICATION_FILTER_EXCEPTIONS,
                mutableSetOf<String>()
            )
        Timber.d("old set: $savedSet")
        val newSet = savedSet?.toMutableSet()
        newSet?.remove(packageName)
        sharedPref.edit()
            .putStringSet(Preferences.KEY_NOTIFICATION_FILTER_EXCEPTIONS, newSet)
            .apply()
        Timber.d("new set: $newSet")
    }

    override fun getNotificationFilterExceptionsList(): Set<String> {
        val s: Set<String> = HashSet<String>(
            sharedPref.getStringSet(
                Preferences.KEY_NOTIFICATION_FILTER_EXCEPTIONS,
                emptySet<String>()
            )!!
        )
        return s
    }

    override fun getNotifyMeHours(): Int {
        return sharedPref
            .getInt(Preferences.KEY_NOTIFY_ME_HOUR, -1)
    }

    override fun getNotifyMeMinutes(): Int {

        return sharedPref
            .getInt(Preferences.KEY_NOTIFY_ME_MINUTE, -1)
    }

    override fun setNotifyMeTime(hour: Int, minutes: Int) {
        Timber.d("setting hour: $hour - minutes: $minutes")
        sharedPref.edit()
            .putInt(Preferences.KEY_NOTIFY_ME_HOUR, hour)
            .putInt(Preferences.KEY_NOTIFY_ME_MINUTE, minutes)
            .apply()
    }

    override fun setNotifyMeScheduledDate(date: Long) {
        sharedPref.edit()
            .putLong(Preferences.KEY_NOTIFY_ME_SCHEDULE_DATE, date)
            .apply()
    }

    override fun isNotifyMeScheduledToday(): Boolean {
        val scheduleDate = Calendar.getInstance()
        scheduleDate.timeInMillis = sharedPref.getLong(Preferences.KEY_NOTIFY_ME_SCHEDULE_DATE, -1)
        val calendar = Calendar.getInstance()
        Timber.d("today = ${calendar.time} \nschedule= ${scheduleDate.time}")
        return calendar.get(Calendar.DAY_OF_MONTH) == scheduleDate.get(Calendar.DAY_OF_MONTH) &&
                calendar.get(Calendar.MONTH) == scheduleDate.get(Calendar.MONTH) &&
                calendar.get(Calendar.YEAR) == scheduleDate.get(Calendar.YEAR)
    }

    override fun getSettingsShouldShowWarning(): Boolean {
        return sharedPref.getBoolean(Preferences.KEY_NOTIFICATION_FILTER_WARNING, true)
    }

    override fun setSettingsShouldShowWarning(shouldShow: Boolean) {
        sharedPref.edit()
            .putBoolean(Preferences.KEY_NOTIFICATION_FILTER_WARNING, shouldShow)
            .apply()
    }

    override fun getAppPriorityOverride(packageName: String): ly.com.tahaben.notification_filter_domain.model.NotificationPriority? {
        val stored = sharedPref.getString(
            Preferences.KEY_APP_PRIORITY_OVERRIDE_PREFIX + packageName, null
        ) ?: return null
        return try {
            ly.com.tahaben.notification_filter_domain.model.NotificationPriority.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun setAppPriorityOverride(
        packageName: String,
        priority: ly.com.tahaben.notification_filter_domain.model.NotificationPriority?
    ) {
        val key = Preferences.KEY_APP_PRIORITY_OVERRIDE_PREFIX + packageName
        if (priority == null) {
            sharedPref.edit().remove(key).apply()
        } else {
            sharedPref.edit().putString(key, priority.name).apply()
        }
    }

    override fun getBatchWindowMinutes(): Int =
        sharedPref.getInt(Preferences.KEY_BATCH_WINDOW_MINUTES, 30)

    override fun setBatchWindowMinutes(minutes: Int) {
        sharedPref.edit().putInt(Preferences.KEY_BATCH_WINDOW_MINUTES, minutes).apply()
    }

    override fun getStandingThresholdHours(): Int =
        sharedPref.getInt(Preferences.KEY_STANDING_THRESHOLD_HOURS, 3)

    override fun setStandingThresholdHours(hours: Int) {
        sharedPref.edit().putInt(Preferences.KEY_STANDING_THRESHOLD_HOURS, hours).apply()
    }

    override fun getFloodThreshold(): Int =
        sharedPref.getInt(Preferences.KEY_FLOOD_THRESHOLD, 10)

    override fun setFloodThreshold(count: Int) {
        sharedPref.edit().putInt(Preferences.KEY_FLOOD_THRESHOLD, count).apply()
    }

    override fun isAppPriorityLocked(packageName: String): Boolean =
        sharedPref.getBoolean(Preferences.KEY_APP_PRIORITY_LOCKED_PREFIX + packageName, false)

    override fun setAppPriorityLocked(packageName: String, locked: Boolean) {
        sharedPref.edit().putBoolean(Preferences.KEY_APP_PRIORITY_LOCKED_PREFIX + packageName, locked).apply()
    }

    override fun getCategoryPriorityOverride(category: NotificationCategory): NotificationPriority? {
        val stored = sharedPref.getString(Preferences.KEY_CATEGORY_PRIORITY_PREFIX + category.name, null) ?: return null
        return try { NotificationPriority.valueOf(stored) } catch (e: IllegalArgumentException) { null }
    }

    override fun setCategoryPriorityOverride(category: NotificationCategory, priority: NotificationPriority?) {
        val key = Preferences.KEY_CATEGORY_PRIORITY_PREFIX + category.name
        if (priority == null) sharedPref.edit().remove(key).apply()
        else sharedPref.edit().putString(key, priority.name).apply()
    }

    override fun getNotificationSchedule(): List<NotificationSchedulePeriod> {
        scheduleCache?.let { return it }
        val json = sharedPref.getString(Preferences.KEY_NOTIFICATION_SCHEDULE, null)
            ?: return NotificationSchedulePeriod.DEFAULT_SCHEDULE.also { scheduleCache = it }
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                NotificationSchedulePeriod(
                    startHour = obj.getInt("sh"),
                    startMinute = obj.getInt("sm"),
                    endHour = obj.getInt("eh"),
                    endMinute = obj.getInt("em"),
                    budgetPoints = obj.getInt("b"),
                )
            }.also { scheduleCache = it }
        } catch (e: Exception) {
            NotificationSchedulePeriod.DEFAULT_SCHEDULE.also { scheduleCache = it }
        }
    }

    override fun setNotificationSchedule(schedule: List<NotificationSchedulePeriod>) {
        scheduleCache = schedule
        val arr = JSONArray()
        schedule.forEach { period ->
            arr.put(JSONObject().apply {
                put("sh", period.startHour)
                put("sm", period.startMinute)
                put("eh", period.endHour)
                put("em", period.endMinute)
                put("b", period.budgetPoints)
            })
        }
        sharedPref.edit().putString(Preferences.KEY_NOTIFICATION_SCHEDULE, arr.toString()).apply()
    }

    override fun getBudgetSpentCurrentPeriod(): Int =
        sharedPref.getInt(Preferences.KEY_BUDGET_SPENT, 0)

    override fun setBudgetSpentCurrentPeriod(points: Int) {
        sharedPref.edit().putInt(Preferences.KEY_BUDGET_SPENT, points).apply()
    }

    override fun getCurrentBudgetPeriodStartMinute(): Int =
        sharedPref.getInt(Preferences.KEY_BUDGET_PERIOD_START_MINUTE, -1)

    override fun setCurrentBudgetPeriodStartMinute(minuteOfDay: Int) {
        sharedPref.edit().putInt(Preferences.KEY_BUDGET_PERIOD_START_MINUTE, minuteOfDay).apply()
    }

    override fun getBudgetSpendLedger(periodStartMinute: Int): List<BudgetSpendEntry> {
        flushPendingLedgerEntries()
        val json = sharedPref.getString(Preferences.KEY_BUDGET_SPEND_LEDGER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { index ->
                val obj = arr.getJSONObject(index)
                if (obj.getInt("p") != periodStartMinute) return@mapNotNull null
                BudgetSpendEntry(
                    appName = obj.getString("a"),
                    title = obj.getString("t"),
                    priority = NotificationPriority.valueOf(obj.getString("pr")),
                    pointCost = obj.getInt("c"),
                    timestamp = obj.getLong("ts"),
                    periodStartMinute = obj.getInt("p"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun addBudgetSpendEntry(entry: BudgetSpendEntry) {
        synchronized(ledgerLock) {
            pendingLedgerEntries.add(entry)
            if (pendingLedgerEntries.size >= LEDGER_FLUSH_THRESHOLD) {
                flushPendingLedgerEntries()
            }
        }
    }

    override fun clearBudgetSpendLedgerBefore(periodStartMinute: Int) {
        flushPendingLedgerEntries()
        val existing = readBudgetSpendLedgerRaw()
        val filtered = JSONArray()
        for (index in 0 until existing.length()) {
            val obj = existing.getJSONObject(index)
            if (obj.getInt("p") >= periodStartMinute) {
                filtered.put(obj)
            }
        }
        sharedPref.edit().putString(Preferences.KEY_BUDGET_SPEND_LEDGER, filtered.toString()).apply()
    }

    override fun clearAllBudgetSpend() {
        synchronized(ledgerLock) {
            pendingLedgerEntries.clear()
        }
        sharedPref.edit()
            .putInt(Preferences.KEY_BUDGET_SPENT, 0)
            .putInt(Preferences.KEY_BUDGET_PERIOD_START_MINUTE, -1)
            .putInt(Preferences.KEY_HOURLY_PASSTHROUGH_COUNT, 0)
            .putInt(Preferences.KEY_PASSTHROUGH_WINDOW_HOUR, -1)
            .putString(Preferences.KEY_BUDGET_SPEND_LEDGER, "[]")
            .apply()
    }

    private fun flushPendingLedgerEntries() {
        val entriesToFlush = synchronized(ledgerLock) {
            if (pendingLedgerEntries.isEmpty()) return
            pendingLedgerEntries.toList().also { pendingLedgerEntries.clear() }
        }
        val existing = readBudgetSpendLedgerRaw()
        entriesToFlush.forEach { entry ->
            existing.put(JSONObject().apply {
                put("a", entry.appName)
                put("t", entry.title)
                put("pr", entry.priority.name)
                put("c", entry.pointCost)
                put("ts", entry.timestamp)
                put("p", entry.periodStartMinute)
            })
        }
        trimBudgetSpendLedger(existing)
        sharedPref.edit().putString(Preferences.KEY_BUDGET_SPEND_LEDGER, existing.toString()).apply()
    }

    private fun readBudgetSpendLedgerRaw(): JSONArray {
        val json = sharedPref.getString(Preferences.KEY_BUDGET_SPEND_LEDGER, null) ?: return JSONArray()
        return try {
            JSONArray(json)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun trimBudgetSpendLedger(ledger: JSONArray) {
        while (ledger.length() > 100) {
            ledger.remove(0)
        }
    }

    override fun getHourlyPassthroughCount(): Int =
        sharedPref.getInt(Preferences.KEY_HOURLY_PASSTHROUGH_COUNT, 0)

    override fun setHourlyPassthroughCount(count: Int) {
        sharedPref.edit().putInt(Preferences.KEY_HOURLY_PASSTHROUGH_COUNT, count).apply()
    }

    override fun getPassthroughWindowHour(): Int =
        sharedPref.getInt(Preferences.KEY_PASSTHROUGH_WINDOW_HOUR, -1)

    override fun setPassthroughWindowHour(hour: Int) {
        sharedPref.edit().putInt(Preferences.KEY_PASSTHROUGH_WINDOW_HOUR, hour).apply()
    }

    override fun getChannelPriorityOverride(packageName: String, channelId: String): ly.com.tahaben.notification_filter_domain.model.NotificationPriority? {
        val key = Preferences.KEY_CHANNEL_PRIORITY_PREFIX + packageName + "_" + channelId
        val stored = sharedPref.getString(key, null) ?: return null
        return try {
            ly.com.tahaben.notification_filter_domain.model.NotificationPriority.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun setChannelPriorityOverride(packageName: String, channelId: String, priority: ly.com.tahaben.notification_filter_domain.model.NotificationPriority?) {
        val key = Preferences.KEY_CHANNEL_PRIORITY_PREFIX + packageName + "_" + channelId
        if (priority == null) {
            sharedPref.edit().remove(key).apply()
        } else {
            sharedPref.edit().putString(key, priority.name).apply()
        }
    }

    override fun getPriorityDeliverySettings(): Map<NotificationPriority, PriorityDeliverySetting> {
        deliverySettingsCache?.let { return it }
        val json = sharedPref.getString(Preferences.KEY_PRIORITY_DELIVERY_SETTINGS, null)
            ?: return PriorityDeliverySetting.DEFAULTS.also { deliverySettingsCache = it }
        return try {
            val obj = JSONObject(json)
            NotificationPriority.entries.associateWith { priority ->
                val entry = obj.optJSONObject(priority.name)
                    ?: return@associateWith PriorityDeliverySetting.defaultFor(priority)
                PriorityDeliverySetting(
                    cadence = DeliveryCadence.valueOf(entry.getString("cadence")),
                    budgetBehavior = BudgetBehavior.valueOf(entry.getString("budget")),
                )
            }.also { deliverySettingsCache = it }
        } catch (e: Exception) {
            PriorityDeliverySetting.DEFAULTS.also { deliverySettingsCache = it }
        }
    }

    override fun setPriorityDeliverySetting(priority: NotificationPriority, setting: PriorityDeliverySetting) {
        deliverySettingsCache = null
        val current = getPriorityDeliverySettings().toMutableMap()
        current[priority] = setting
        val obj = JSONObject()
        current.forEach { (p, s) ->
            obj.put(p.name, JSONObject().apply {
                put("cadence", s.cadence.name)
                put("budget", s.budgetBehavior.name)
            })
        }
        sharedPref.edit().putString(Preferences.KEY_PRIORITY_DELIVERY_SETTINGS, obj.toString()).apply()
        deliverySettingsCache = current
    }

    override fun isDeliveryTestingMode(): Boolean =
        sharedPref.getBoolean(Preferences.KEY_DELIVERY_TESTING_MODE, false)

    override fun setDeliveryTestingMode(enabled: Boolean) {
        sharedPref.edit().putBoolean(Preferences.KEY_DELIVERY_TESTING_MODE, enabled).apply()
    }

    override fun getKnownPackageSources(): Map<String, KnownPackageSource> {
        val json = sharedPref.getString(Preferences.KEY_KNOWN_PACKAGE_SOURCES, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(json)
            buildMap {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val packageName = obj.getString("pkg")
                    val channels = obj.optJSONArray("channels").toStringSet()
                    val categories = obj.optJSONArray("categories").toStringSet()
                    put(
                        packageName,
                        KnownPackageSource(
                            packageName = packageName,
                            appName = obj.optString("name", packageName),
                            channelIds = channels,
                            androidCategories = categories,
                            appCategoryCode = obj.optInt("appCat", AppStoreCategory.UNDEFINED)
                                .takeIf { it != AppStoreCategory.UNDEFINED },
                            appCategoryLabel = obj.optString("appCatLabel", null)?.takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun setKnownPackageSources(sources: List<KnownPackageSource>) {
        val arr = JSONArray()
        sources.sortedBy { it.appName.lowercase() }.forEach { source ->
            arr.put(JSONObject().apply {
                put("pkg", source.packageName)
                put("name", source.appName)
                put("channels", JSONArray(source.channelIds.sorted()))
                put("categories", JSONArray(source.androidCategories.sorted()))
                source.appCategoryCode?.let { put("appCat", it) }
                source.appCategoryLabel?.let { put("appCatLabel", it) }
            })
        }
        sharedPref.edit().putString(Preferences.KEY_KNOWN_PACKAGE_SOURCES, arr.toString()).apply()
    }

    override fun shouldPassThroughNotification(packageName: String): Boolean {
        if (isPackageInNotificationExceptions(packageName)) return true
        return getAppPriorityOverride(packageName) == NotificationPriority.CRITICAL
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (i in 0 until length()) {
                add(getString(i))
            }
        }
    }

    companion object {
        private const val LEDGER_FLUSH_THRESHOLD = 5
    }
}