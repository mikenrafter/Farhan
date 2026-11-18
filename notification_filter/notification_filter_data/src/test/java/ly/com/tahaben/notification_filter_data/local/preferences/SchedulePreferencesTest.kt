package ly.com.tahaben.notification_filter_data.local.preferences

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.model.*
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.schedule.*
import ly.com.tahaben.notification_filter_domain.use_cases.GetCurrentBudgetStatus
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class SchedulePreferencesTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-18T17:00:00Z"), ZoneId.of("America/Denver"))
    private val dataStore = object : DataStore<androidx.datastore.preferences.core.Preferences> {
        override val data = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) = transform(emptyPreferences())
    }

    // why: the actual preference reader must preserve saved points while repairing both old time rules.
    // catches: default fallback, endpoint migration after validation, dropping a half-hour window's budget.
    @Test fun `actual read migrates literal old JSON and preserves unrelated settings`() {
        val shared = MemoryPreferences()
        val literal = """[{"sh":0,"sm":0,"eh":0,"em":30,"b":7},{"sh":0,"sm":30,"eh":9,"em":0,"b":3},{"sh":9,"sm":0,"eh":23,"em":59,"b":11}]"""
        shared.edit().putString(Preferences.KEY_NOTIFICATION_SCHEDULE, literal).putInt(Preferences.KEY_BUDGET_SPENT, 6)
            .putInt(Preferences.KEY_BUDGET_PERIOD_START_MINUTE, 540).putStringSet(Preferences.KEY_NOTIFICATION_FILTER_EXCEPTIONS, setOf("kept"))
            .putString(Preferences.KEY_APP_PRIORITY_OVERRIDE_PREFIX + "kept", "LOW").commit()
        val preferences = DefaultPreferences(shared, dataStore, clock)
        assertThat(preferences.getNotificationSchedule()).isEqualTo(listOf(NotificationSchedulePeriod(0, 0, 9, 0, 10), NotificationSchedulePeriod(9, 0, 24, 0, 11)))
        assertThat(preferences.getScheduleSnapshot().budget.spentPoints).isEqualTo(6)
        assertThat(preferences.getScheduleSnapshot().budget.localDay).isEqualTo("2026-11-18")
        assertThat(shared.getString("notification_schedule_legacy_backup", null)).isEqualTo(literal)
        assertThat(preferences.isPackageInNotificationExceptions("kept")).isTrue()
        assertThat(preferences.getAppPriorityOverride("kept")).isEqualTo(NotificationPriority.LOW)
        assertThat(DefaultPreferences(shared, dataStore, clock).getScheduleSnapshot()).isEqualTo(preferences.getScheduleSnapshot())
    }

    // why: corrupt readable storage must be preserved for repair rather than silently overwritten with defaults.
    // catches: catch-and-default on malformed JSON or bad topology.
    @Test fun `invalid stored JSON refuses read without replacing stored data`() {
        for (literal in listOf("not-json", """[{"sh":0,"sm":0,"eh":9,"em":0,"b":7},{"sh":10,"sm":0,"eh":23,"em":59,"b":3}]""")) {
            val shared = MemoryPreferences()
            shared.edit().putString(Preferences.KEY_NOTIFICATION_SCHEDULE, literal).commit()
            assertThrows(Exception::class.java) { DefaultPreferences(shared, dataStore, clock).getScheduleSnapshot() }
            assertThat(shared.getString(Preferences.KEY_NOTIFICATION_SCHEDULE, null)).isEqualTo(literal)
            assertThat(shared.contains("notification_schedule_snapshot_v1")).isFalse()
        }
    }

    // why: both the status use case and persisted ledger must retain itemized spend after a boundary edit and reload.
    // catches: filtering ledger by the new editable start, dropping unknown date, non-atomic schedule/budget persistence.
    @Test fun `edit rebase keeps durable ledger detail visible through actual status reader`() = runTest {
        val shared = MemoryPreferences()
        val preferences = DefaultPreferences(shared, dataStore, clock)
        val original = listOf(NotificationSchedulePeriod(0, 0, 9, 0, 2), NotificationSchedulePeriod(9, 0, 12, 0, 8), NotificationSchedulePeriod(12, 0, 24, 0, 0))
        preferences.commitScheduleSnapshot(ScheduleSnapshot(ScheduleBook(original), DeliveryBudgetState(6, 540, localDay = "2026-11-18", accountingId = "2026-11-18:540")))
        preferences.addBudgetSpendEntry(BudgetSpendEntry("App", "Saved", NotificationPriority.MEDIUM, 6, clock.millis(), 540, "2026-11-18:540", "effect"))
        val store = object : ScheduleBookStore {
            override suspend fun read() = preferences.getScheduleSnapshot()
            override suspend fun commit(snapshot: ScheduleSnapshot) = preferences.commitScheduleSnapshot(snapshot)
        }
        ScheduleWriter(store, clock).write(ScheduleWrite(listOf(original[0].copy(endMinute = 30), original[1].copy(startMinute = 30, budgetPoints = 10), original[2])))
        val reloaded = DefaultPreferences(shared, dataStore, clock)
        val status = GetCurrentBudgetStatus(reloaded, clock)()
        assertThat(status.spentPoints).isEqualTo(6)
        assertThat(status.remainingPoints).isEqualTo(4)
        assertThat(status.spendEntries.map { it.title }).containsExactly("Saved")
        val token = reloaded.getScheduleSnapshot().budget.accountingId
        assertThat(token).isEqualTo("2026-11-18:540")
        reloaded.addBudgetSpendEntry(status.spendEntries.single())
        assertThat(GetCurrentBudgetStatus(reloaded, clock)().spendEntries).hasSize(1)
    }
}

/** In-memory Android storage port; JSON codec and DefaultPreferences are the real production classes. */
private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun contains(key: String) = values.containsKey(key)
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { changes[key] = value?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean { if (clear) values.clear(); changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; return true }
    }
}
