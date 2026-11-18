package ly.com.tahaben.notification_filter_data.automation

import android.content.SharedPreferences
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError
import ly.com.tahaben.notification_filter_domain.schedule.AutomationReceipt
import ly.com.tahaben.notification_filter_domain.schedule.AutomationReceiptStore
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleMode
import javax.inject.Inject
import javax.inject.Singleton

/** The last automation outcome, three scalar keys. Unknown stored names read as "no receipt" rather than throwing. */
@Singleton
class SharedPreferencesReceiptStore @Inject constructor(private val preferences: SharedPreferences) : AutomationReceiptStore {
    override fun last(): AutomationReceipt? {
        val at = preferences.getLong(AT, -1L).takeIf { it >= 0 } ?: return null
        val mode = preferences.getString(MODE, null)?.let { name -> ScheduleMode.entries.firstOrNull { it.name == name } }
        val error = preferences.getString(ERROR, null)?.let { name -> AutomationError.entries.firstOrNull { it.name == name } }
        return AutomationReceipt(at, mode, error)
    }

    override fun record(receipt: AutomationReceipt) {
        preferences.edit().putLong(AT, receipt.atEpochMillis).putString(MODE, receipt.mode?.name)
            .putString(ERROR, receipt.error?.name).apply()
    }

    private companion object {
        const val AT = "notification_automation_last_at_v1"
        const val MODE = "notification_automation_last_mode_v1"
        const val ERROR = "notification_automation_last_error_v1"
    }
}
