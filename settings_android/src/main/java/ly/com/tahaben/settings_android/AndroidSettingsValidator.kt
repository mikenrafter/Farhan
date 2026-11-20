package ly.com.tahaben.settings_android

import ly.com.tahaben.core.model.ThemeColors
import ly.com.tahaben.core.model.UIModeAppearance
import ly.com.tahaben.notification_filter_data.local.preferences.ScheduleCodec
import ly.com.tahaben.notification_filter_domain.delivery.QueueOrder
import ly.com.tahaben.notification_filter_domain.model.*
import ly.com.tahaben.notification_filter_domain.schedule.DaySchedule
import ly.com.tahaben.notification_filter_domain.sound.SoundElectionSettings
import ly.com.tahaben.settings_portability.*

/** Embedded preference JSON is checked strictly before the existing domain decoder receives its original text. */
object AndroidSettingsValidator : SemanticValidator {
    override fun firstProblem(configuration: SettingsConfiguration): SemanticProblem? {
        for ((store, preferences) in listOf(SettingsStoreId.SHARED_PREFERENCES to configuration.sharedPreferences,
            SettingsStoreId.DATA_STORE to configuration.dataStore)) {
            for ((key, value) in preferences) {
                try { checkPreference(key, value) }
                catch (failure: Exception) { return SemanticProblem(store, key) }
            }
        }
        return null
    }

    private fun checkPreference(key: String, value: SettingValue) {
        fun text() = (value as SettingValue.Text).value
        fun integer() = (value as SettingValue.Int32).value
        when {
            key.startsWith(SoundElectionSettings.APP_PREFIX) ->
                require(SoundElectionSettings.isPackageName(key.removePrefix(SoundElectionSettings.APP_PREFIX)))
            key.startsWith(SoundElectionSettings.CATEGORY_PREFIX) ->
                NotificationCategory.valueOf(key.removePrefix(SoundElectionSettings.CATEGORY_PREFIX))
            key.startsWith("notification_category_priority_") -> {
                NotificationCategory.valueOf(key.removePrefix("notification_category_priority_"))
                NotificationPriority.valueOf(text())
            }
            key.startsWith("notification_priority_override_") -> NotificationPriority.valueOf(text())
            key == WidgetProfiles.KEY -> WidgetProfiles.decode(text())
            key == "app_dark_mode_on" -> UIModeAppearance.valueOf(text())
            key == "app_theme_colors" -> if (text() != "Unknown") ThemeColors.valueOf(text())
            key == "notification_delivery_schedule" -> {
                windows(json(text()))
                DaySchedule.repairLegacy(ScheduleCodec.decodeSchedule(text()))
            }
            key == "notification_schedule_snapshot_v1" -> {
                val root = obj(json(text()), setOf("version", "repeating", "dated", "budget"))
                require(int(root["version"]) == 1)
                windows(root["repeating"])
                map(root["dated"]).forEach { (date, periods) -> java.time.LocalDate.parse(date); windows(periods) }
                val budget = map(root["budget"])
                require(budget.keys == setOf("spent", "start", "hourly", "hour", "day") ||
                    budget.keys == setOf("spent", "start", "hourly", "hour", "day", "accounting"))
                require(int(budget["spent"]) >= 0 && int(budget["hourly"]) >= 0)
                require(int(budget["start"]) in -1..1439 && int(budget["hour"]) in -1..23)
                require(budget["day"] is String && (!budget.containsKey("accounting") || budget["accounting"] is String))
                (budget["day"] as String).takeIf { it.isNotEmpty() }?.let(java.time.LocalDate::parse)
                ScheduleCodec.decode(text()).book.let { book ->
                    DaySchedule.repairLegacy(book.repeating)
                    book.dated.values.forEach(DaySchedule::repairLegacy)
                }
            }
            key == "notification_app_queue_v1" -> map(json(text())).forEach { (_, raw) ->
                val entry = obj(raw, setOf("order", "rank"))
                QueueOrder.valueOf(entry["order"] as String)
                int(entry["rank"])
            }
            key == "notification_priority_delivery_settings" -> map(json(text())).forEach { (priority, raw) ->
                NotificationPriority.valueOf(priority)
                val entry = obj(raw, setOf("cadence", "budget"))
                PriorityDeliverySetting(DeliveryCadence.valueOf(entry["cadence"] as String), BudgetBehavior.valueOf(entry["budget"] as String))
            }
            key == "notify_me_hour" || key == "notification_passthrough_window_hour" -> require(integer() in -1..23)
            key == "notify_me_minute" -> require(integer() in -1..59)
            key == "notification_budget_period_start_minute" -> require(integer() in -1..1439)
            key == "notification_budget_spent" || key == "notification_hourly_passthrough_count" -> require(integer() >= 0)
            key == "notification_batch_window_minutes" || key == "notification_standing_threshold_hours" ||
                key == "notification_flood_threshold" || key == "infinite_scroll_time_out" -> require(integer() > 0)
            key == "count_down_time" -> require(integer() >= -1)
            key == "key_delayed_launch_duration" -> require(integer() >= 0)
        }
    }
    private fun windows(raw: Any?) {
        (raw as List<*>).forEach { window ->
            obj(window, setOf("sh", "sm", "eh", "em", "b")).values.forEach(::int)
        }
    }
    private fun int(raw: Any?): Int {
        val number = raw as Long
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return number.toInt()
    }
    @Suppress("UNCHECKED_CAST")
    private fun map(raw: Any?) = raw as Map<String, Any?>
    private fun obj(raw: Any?, keys: Set<String>) = map(raw).also { require(it.keys == keys) }

    private fun json(text: String): Any? = StrictPreferenceJson.parse(text)
}
