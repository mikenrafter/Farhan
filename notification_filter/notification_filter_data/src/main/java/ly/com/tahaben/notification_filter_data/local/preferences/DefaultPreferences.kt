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
    private val dataStore: DataStore<androidx.datastore.preferences.core.Preferences>,
    private val clock: java.time.Clock = ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock.systemClock,
) : Preferences {

    private val mainSwitchKey = booleanPreferencesKey(GlobalKey.Pref_KEY_APP_MAIN_SWITCH)

    @Volatile
    private var serviceEnabledCache: Boolean? = null

    @Volatile
    private var deliverySettingsCache: Map<NotificationPriority, PriorityDeliverySetting>? = null

    private val stateLock = Any()
    private var snapshotCache: ly.com.tahaben.notification_filter_domain.schedule.ScheduleSnapshot? = null
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
        sharedPref.getInt(Preferences.KEY_STANDING_THRESHOLD_HOURS, 6)

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

    override fun getNotificationSchedule(): List<NotificationSchedulePeriod> =
        getScheduleSnapshot().book.scheduleFor(java.time.LocalDate.now(clock))

    /** Legacy callers must use SetNotificationSchedule, which serializes through NotificationCore.writer. */
    override fun setNotificationSchedule(schedule: List<NotificationSchedulePeriod>) {
        error("Use ScheduleWriter for atomic, budget-preserving writes")
    }

    override fun getScheduleSnapshot(): ly.com.tahaben.notification_filter_domain.schedule.ScheduleSnapshot = try { synchronized(stateLock) {
        snapshotCache?.let { return@synchronized it }
        val stored = sharedPref.getString(SNAPSHOT_KEY, null)
        val legacy = sharedPref.getString(Preferences.KEY_NOTIFICATION_SCHEDULE, null)
        val old = if (stored != null) ScheduleCodec.decode(stored) else {
            ly.com.tahaben.notification_filter_domain.schedule.ScheduleSnapshot(
                ly.com.tahaben.notification_filter_domain.schedule.ScheduleBook(
                    legacy?.let(ScheduleCodec::decodeSchedule) ?: NotificationSchedulePeriod.DEFAULT_SCHEDULE),
                ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState(
                    sharedPref.getInt(Preferences.KEY_BUDGET_SPENT, 0), sharedPref.getInt(Preferences.KEY_BUDGET_PERIOD_START_MINUTE, -1),
                    sharedPref.getInt(Preferences.KEY_HOURLY_PASSTHROUGH_COUNT, 0), sharedPref.getInt(Preferences.KEY_PASSTHROUGH_WINDOW_HOUR, -1)))
        }
        val today = java.time.LocalDate.now(clock)
        val minute = ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock.current(isDeliveryTestingMode(), clock).scheduleMinuteOfDay
        val repairedBook = old.book.copy(
            repeating = ly.com.tahaben.notification_filter_domain.schedule.DaySchedule.repairLegacy(old.book.repeating),
            dated = old.book.dated.mapValues { ly.com.tahaben.notification_filter_domain.schedule.DaySchedule.repairLegacy(it.value) })
        val policy = ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryPolicy
        val normalized = policy.normalizeBudget(old.budget, old.book.scheduleFor(today), minute, today.toString())
        val repaired = old.copy(book = repairedBook, budget = normalized.copy(
            periodStartMinute = policy.findActivePeriod(repairedBook.scheduleFor(today), minute)?.startMinuteOfDay ?: -1))
        if (stored == null || repaired != old) {
            val editor = sharedPref.edit()
            if (legacy != null && !sharedPref.contains(LEGACY_BACKUP_KEY)) editor.putString(LEGACY_BACKUP_KEY, legacy)
            check(editor.putString(SNAPSHOT_KEY, ScheduleCodec.encode(repaired)).commit()) { "Schedule migration could not be saved" }
        }
        snapshotCache = repaired
        repaired
    } } catch (error: ly.com.tahaben.notification_filter_domain.schedule.ScheduleReadException) { throw error }
        catch (error: Exception) { throw ly.com.tahaben.notification_filter_domain.schedule.ScheduleReadException(error) }

    override fun commitScheduleSnapshot(snapshot: ly.com.tahaben.notification_filter_domain.schedule.ScheduleSnapshot) = synchronized(stateLock) {
        check(sharedPref.edit().putString(SNAPSHOT_KEY, ScheduleCodec.encode(snapshot))
            .putString(Preferences.KEY_NOTIFICATION_SCHEDULE, ScheduleCodec.encodeSchedule(snapshot.book.repeating))
            .putInt(Preferences.KEY_BUDGET_SPENT, snapshot.budget.spentPoints)
            .putInt(Preferences.KEY_BUDGET_PERIOD_START_MINUTE, snapshot.budget.periodStartMinute)
            .putInt(Preferences.KEY_HOURLY_PASSTHROUGH_COUNT, snapshot.budget.hourlyCount)
            .putInt(Preferences.KEY_PASSTHROUGH_WINDOW_HOUR, snapshot.budget.passthroughWindowHour).commit()) { "Schedule/budget commit failed" }
        snapshotCache = snapshot
    }

    override fun getAppQueuePreferences(): Map<String, ly.com.tahaben.notification_filter_domain.delivery.AppQueuePreference> {
        val json = JSONObject(sharedPref.getString(QUEUE_KEY, "{}")!!)
        return json.keys().asSequence().associate { pkg ->
            val preference = json.getJSONObject(pkg)
            pkg to ly.com.tahaben.notification_filter_domain.delivery.AppQueuePreference(
                ly.com.tahaben.notification_filter_domain.delivery.QueueOrder.valueOf(preference.getString("order")), preference.getInt("rank"))
        }
    }

    override fun setAppQueuePreference(packageName: String, preference: ly.com.tahaben.notification_filter_domain.delivery.AppQueuePreference) = synchronized(stateLock) {
        val json = JSONObject(sharedPref.getString(QUEUE_KEY, "{}")!!)
        json.put(packageName, JSONObject().put("order", preference.order.name).put("rank", preference.rank))
        check(sharedPref.edit().putString(QUEUE_KEY, json.toString()).commit())
    }

    override fun isExperimentEnabled(name: String): Boolean = sharedPref.getBoolean("notification_experiment_" + name, false)
    override fun setExperimentEnabled(name: String, enabled: Boolean) { sharedPref.edit().putBoolean("notification_experiment_" + name, enabled).apply() }

    override fun getOtpPriority(): NotificationPriority =
        sharedPref.getString(Preferences.KEY_OTP_PRIORITY, null)
            ?.let { stored -> NotificationPriority.entries.firstOrNull { it.name == stored } }
            ?: NotificationPriority.CRITICAL
    override fun setOtpPriority(priority: NotificationPriority) { sharedPref.edit().putString(Preferences.KEY_OTP_PRIORITY, priority.name).apply() }
    override fun getOtpPackages(): Set<String> = HashSet(sharedPref.getStringSet(Preferences.KEY_OTP_PACKAGES, emptySet()).orEmpty())
    override fun setOtpPackages(packages: Set<String>) { sharedPref.edit().putStringSet(Preferences.KEY_OTP_PACKAGES, HashSet(packages)).apply() }
    override fun isOtpAllApps(): Boolean = sharedPref.getBoolean(Preferences.KEY_OTP_ALL_APPS, true)
    override fun setOtpAllApps(allApps: Boolean) { sharedPref.edit().putBoolean(Preferences.KEY_OTP_ALL_APPS, allApps).apply() }

    override fun getBudgetSpentCurrentPeriod(): Int =
        getScheduleSnapshot().budget.spentPoints

    override fun setBudgetSpentCurrentPeriod(points: Int) {
        sharedPref.edit().putInt(Preferences.KEY_BUDGET_SPENT, points).apply()
    }

    override fun getCurrentBudgetPeriodStartMinute(): Int =
        getScheduleSnapshot().budget.periodStartMinute

    override fun setCurrentBudgetPeriodStartMinute(minuteOfDay: Int) {
        sharedPref.edit().putInt(Preferences.KEY_BUDGET_PERIOD_START_MINUTE, minuteOfDay).apply()
    }

    override fun getBudgetSpendLedger(periodStartMinute: Int): List<BudgetSpendEntry> = synchronized(ledgerLock) {
        val arr = readBudgetSpendLedgerRaw()
        (0 until arr.length()).mapNotNull { index ->
            val obj = arr.getJSONObject(index)
            if (obj.getInt("p") != periodStartMinute) null else BudgetSpendEntry(obj.getString("a"), obj.getString("t"),
                NotificationPriority.valueOf(obj.getString("pr")), obj.getInt("c"), obj.getLong("ts"), obj.getInt("p"))
        }
    }

    override fun getBudgetSpendLedgerForState(budget: ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState): List<BudgetSpendEntry> = synchronized(ledgerLock) {
        val ledger = readBudgetSpendLedgerRaw()
        (0 until ledger.length()).mapNotNull { index ->
            val obj = ledger.getJSONObject(index)
            val timestamp = obj.getLong("ts")
            val originalIdentity = obj.optString("accounting", "").ifEmpty {
                java.time.Instant.ofEpochMilli(timestamp).atZone(clock.zone).toLocalDate().toString() + ":" + obj.getInt("p")
            }
            if (originalIdentity != budget.accountingId) null else BudgetSpendEntry(obj.getString("a"), obj.getString("t"),
                NotificationPriority.valueOf(obj.getString("pr")), obj.getInt("c"), timestamp, obj.getInt("p"), originalIdentity, obj.optString("effect", ""))
        }
    }

    override fun addBudgetSpendEntry(entry: BudgetSpendEntry) = synchronized(ledgerLock) {
        val ledger = readBudgetSpendLedgerRaw()
        if (entry.effectId.isNotEmpty() && (0 until ledger.length()).any { ledger.getJSONObject(it).optString("effect") == entry.effectId }) return@synchronized
        ledger.put(JSONObject().apply {
            put("a", entry.appName); put("t", entry.title); put("pr", entry.priority.name); put("c", entry.pointCost)
            put("ts", entry.timestamp); put("p", entry.periodStartMinute); put("accounting", entry.accountingId); put("effect", entry.effectId)
        })
        while (ledger.length() > 100) ledger.remove(0)
        check(sharedPref.edit().putString(Preferences.KEY_BUDGET_SPEND_LEDGER, ledger.toString()).commit())
    }

    override fun clearBudgetSpendLedgerBefore(periodStartMinute: Int) = Unit

    override fun clearAllBudgetSpend() {
        synchronized(stateLock) {
            commitScheduleSnapshot(getScheduleSnapshot().copy(budget = ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState()))
            synchronized(ledgerLock) {
                check(sharedPref.edit().putString(Preferences.KEY_BUDGET_SPEND_LEDGER, "[]").commit())
            }
        }
    }

    private fun readBudgetSpendLedgerRaw(): JSONArray = JSONArray(sharedPref.getString(Preferences.KEY_BUDGET_SPEND_LEDGER, "[]")!!)

    override fun getHourlyPassthroughCount(): Int =
        getScheduleSnapshot().budget.hourlyCount

    override fun setHourlyPassthroughCount(count: Int) {
        sharedPref.edit().putInt(Preferences.KEY_HOURLY_PASSTHROUGH_COUNT, count).apply()
    }

    override fun getPassthroughWindowHour(): Int =
        getScheduleSnapshot().budget.passthroughWindowHour

    override fun setPassthroughWindowHour(hour: Int) {
        sharedPref.edit().putInt(Preferences.KEY_PASSTHROUGH_WINDOW_HOUR, hour).apply()
    }

    override fun getChannelPriorityOffset(packageName: String, channelId: String): Int {
        val key = Preferences.KEY_CHANNEL_PRIORITY_OFFSET_PREFIX + packageName + "_" + channelId
        return sharedPref.getInt(key, 0)
    }

    override fun setChannelPriorityOffset(packageName: String, channelId: String, offset: Int) {
        val key = Preferences.KEY_CHANNEL_PRIORITY_OFFSET_PREFIX + packageName + "_" + channelId
        if (offset == 0) {
            sharedPref.edit().remove(key).apply()
        } else {
            sharedPref.edit().putInt(key, offset).apply()
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
        private const val SNAPSHOT_KEY = "notification_schedule_snapshot_v1"
        private const val LEGACY_BACKUP_KEY = "notification_schedule_legacy_backup"
        private const val QUEUE_KEY = "notification_app_queue_v1"
    }
}