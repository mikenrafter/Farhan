package ly.com.tahaben.notification_filter_domain.preferences

interface Preferences {

    fun loadShouldShowOnBoarding(): Boolean
    fun saveShouldShowOnBoarding(shouldShow: Boolean)
    fun loadShouldShowcase(): Boolean
    fun saveShouldShowcase(shouldShow: Boolean)
    suspend fun isServiceEnabled(): Boolean
    fun setServiceState(isEnabled: Boolean)
    fun savePackageToNotificationExceptions(packageName: String)
    fun removePackageFromNotificationExceptions(packageName: String)
    fun isPackageInNotificationExceptions(packageName: String): Boolean
    fun getNotificationFilterExceptionsList(): Set<String>
    fun setNotifyMeTime(hour: Int, minutes: Int)
    fun isNotifyMeScheduledToday(): Boolean
    fun setNotifyMeScheduledDate(date: Long)
    fun getNotifyMeHours(): Int
    fun getNotifyMeMinutes(): Int
    fun getSettingsShouldShowWarning(): Boolean
    fun setSettingsShouldShowWarning(shouldShow: Boolean)

    fun getAppPriorityOverride(packageName: String): ly.com.tahaben.notification_filter_domain.model.NotificationPriority?
    fun setAppPriorityOverride(packageName: String, priority: ly.com.tahaben.notification_filter_domain.model.NotificationPriority?)
    fun getBatchWindowMinutes(): Int
    fun setBatchWindowMinutes(minutes: Int)
    fun getStandingThresholdHours(): Int
    fun setStandingThresholdHours(hours: Int)
    fun getFloodThreshold(): Int
    fun setFloodThreshold(count: Int)

    // Priority lock: when locked, automated adjustments (flood/chatter) cannot change the priority
    fun isAppPriorityLocked(packageName: String): Boolean
    fun setAppPriorityLocked(packageName: String, locked: Boolean)

    // Per-category priority overrides (user-configurable; falls back to NotificationCategory.defaultPriority)
    fun getCategoryPriorityOverride(category: ly.com.tahaben.notification_filter_domain.model.NotificationCategory): ly.com.tahaben.notification_filter_domain.model.NotificationPriority?
    fun setCategoryPriorityOverride(category: ly.com.tahaben.notification_filter_domain.model.NotificationCategory, priority: ly.com.tahaben.notification_filter_domain.model.NotificationPriority?)

    // Per-channel priority overrides (highest-priority override, overrides app and category defaults)
    fun getChannelPriorityOverride(packageName: String, channelId: String): ly.com.tahaben.notification_filter_domain.model.NotificationPriority?
    fun setChannelPriorityOverride(packageName: String, channelId: String, priority: ly.com.tahaben.notification_filter_domain.model.NotificationPriority?)

    // Notification delivery schedule (budget per time period)
    fun getNotificationSchedule(): List<ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod>
    fun setNotificationSchedule(schedule: List<ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod>)

    // Budget tracking for the current schedule period
    fun getBudgetSpentCurrentPeriod(): Int
    fun setBudgetSpentCurrentPeriod(points: Int)
    fun getCurrentBudgetPeriodStartMinute(): Int
    fun setCurrentBudgetPeriodStartMinute(minuteOfDay: Int)

    // Hourly passthrough rate limit (max 10 proxy notifications per hour)
    fun getHourlyPassthroughCount(): Int
    fun setHourlyPassthroughCount(count: Int)
    fun getPassthroughWindowHour(): Int
    fun setPassthroughWindowHour(hour: Int)

    companion object {
        const val KEY_NOTIFICATION_SERVICE_STATS = "notification_service_stats"
        const val KEY_NOTIFICATION_FILTER_EXCEPTIONS = "notification_filter_exceptions"
        const val KEY_NOTIFY_ME_SCHEDULE_DATE = "notify_me_schedule_date"
        const val KEY_NOTIFY_ME_HOUR = "notify_me_hour"
        const val KEY_NOTIFY_ME_MINUTE = "notify_me_minute"
        const val KEY_NOTIFICATION_FILTER_SHOULD_SHOW_ON_BOARDING =
            "notification_filter_should_show_on_boarding"
        const val KEY_NOTIFICATION_FILTER_SHOULD_SHOWCASE =
            "notification_filter_should_showcase"
        const val KEY_NOTIFICATION_FILTER_WARNING = "notification_filter_settings_warning"
        const val KEY_APP_PRIORITY_OVERRIDE_PREFIX = "notification_priority_override_"
        const val KEY_BATCH_WINDOW_MINUTES = "notification_batch_window_minutes"
        const val KEY_STANDING_THRESHOLD_HOURS = "notification_standing_threshold_hours"
        const val KEY_FLOOD_THRESHOLD = "notification_flood_threshold"
        const val KEY_APP_PRIORITY_LOCKED_PREFIX = "notification_priority_locked_"
        const val KEY_CATEGORY_PRIORITY_PREFIX = "notification_category_priority_"
        const val KEY_NOTIFICATION_SCHEDULE = "notification_delivery_schedule"
        const val KEY_BUDGET_SPENT = "notification_budget_spent"
        const val KEY_BUDGET_PERIOD_START_MINUTE = "notification_budget_period_start_minute"
        const val KEY_HOURLY_PASSTHROUGH_COUNT = "notification_hourly_passthrough_count"
        const val KEY_PASSTHROUGH_WINDOW_HOUR = "notification_passthrough_window_hour"
        const val KEY_CHANNEL_PRIORITY_PREFIX = "notification_channel_priority_"
    }
}