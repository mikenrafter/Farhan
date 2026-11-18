package ly.com.tahaben.notification_filter_data.local.preferences

import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.schedule.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Mates the existing sh/sm/eh/em/b preference format with the additive version-1 snapshot. */
object ScheduleCodec {
    fun decodeSchedule(json: String): List<NotificationSchedulePeriod> = decodeWindows(JSONArray(json))
    private fun decodeWindows(windows: JSONArray): List<NotificationSchedulePeriod> = (0 until windows.length()).map {
        val window = windows.getJSONObject(it)
        NotificationSchedulePeriod(window.getInt("sh"), window.getInt("sm"), window.getInt("eh"), window.getInt("em"), window.getInt("b"))
    }
    private fun windows(schedule: List<NotificationSchedulePeriod>): JSONArray = JSONArray().apply {
        schedule.forEach { window -> put(JSONObject().apply {
            put("sh", window.startHour); put("sm", window.startMinute); put("eh", window.endHour); put("em", window.endMinute); put("b", window.budgetPoints)
        }) }
    }
    fun encodeSchedule(schedule: List<NotificationSchedulePeriod>): String = windows(schedule).toString()
    fun encode(snapshot: ScheduleSnapshot): String = JSONObject().apply {
        put("version", 1)
        put("repeating", windows(snapshot.book.repeating))
        put("dated", JSONObject().apply { snapshot.book.dated.forEach { (date, schedule) -> put(date.toString(), windows(schedule)) } })
        put("budget", JSONObject().apply {
            put("spent", snapshot.budget.spentPoints); put("start", snapshot.budget.periodStartMinute)
            put("hourly", snapshot.budget.hourlyCount); put("hour", snapshot.budget.passthroughWindowHour); put("day", snapshot.budget.localDay); put("accounting", snapshot.budget.accountingId)
        })
    }.toString()
    fun decode(json: String): ScheduleSnapshot {
        val root = JSONObject(json)
        require(root.getInt("version") == 1) { "Unsupported schedule storage version" }
        val dated = root.getJSONObject("dated")
        val budget = root.getJSONObject("budget")
        return ScheduleSnapshot(ScheduleBook(decodeWindows(root.getJSONArray("repeating")), dated.keys().asSequence().associate {
            LocalDate.parse(it) to decodeWindows(dated.getJSONArray(it))
        }), DeliveryBudgetState(budget.getInt("spent"), budget.getInt("start"), budget.getInt("hourly"), budget.getInt("hour"), budget.getString("day"), budget.optString("accounting", "")))
    }
}
