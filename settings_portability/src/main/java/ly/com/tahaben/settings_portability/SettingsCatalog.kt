package ly.com.tahaben.settings_portability

/** What an import writes to an [SettingDisposition.ImportReset] key, whatever the file says. */
sealed interface ResetValue {
    /** The key is removed. */
    data object Remove : ResetValue

    /** The key is written as this boolean. */
    data class Bool(val value: Boolean) : ResetValue
}

sealed interface SettingDisposition {
    /** A user setting. The stored value must have this type. */
    data class Configuration(val type: SettingType) : SettingDisposition

    /** Known and deliberately not transferred. */
    data class Excluded(val reason: ExclusionReason) : SettingDisposition

    /**
     * Destination state that an import resets in the same commit as the configuration (automation credential,
     * receipts, enablement). Never exportable and never accepted from a file; the journal's previous snapshot holds
     * its raw destination value so rollback restores it exactly.
     */
    data class ImportReset(val after: ResetValue) : SettingDisposition

    /** Not in the catalog. Reported by name, never applied. */
    data object Unsupported : SettingDisposition
}

/** Why the preview names this key as left out, or null when the key is a setting or unknown. */
val SettingDisposition.omissionReason: ExclusionReason?
    get() = when (this) {
        is SettingDisposition.Excluded -> reason
        is SettingDisposition.ImportReset -> ExclusionReason.SECRET
        is SettingDisposition.Configuration, SettingDisposition.Unsupported -> null
    }

/** Keys the import owns: it replaces configuration and resets these. Everything else in a store is untouched. */
val SettingDisposition.isInImportDomain: Boolean
    get() = this is SettingDisposition.Configuration || this is SettingDisposition.ImportReset

/** Name used in file locations and error locations. */
internal val SettingsStoreId.locationName: String
    get() = when (this) {
        SettingsStoreId.SHARED_PREFERENCES -> "sharedPreferences"
        SettingsStoreId.DATA_STORE -> "dataStore"
    }

/**
 * The one list of which keys are settings. Export, rescue conversion, import validation and the
 * "replace only the configuration domain" rule all read it, so they cannot disagree.
 *
 * Inventory source: the `Preferences` interfaces and `Default*Preferences` classes under notification_filter,
 * launcher, infinite_scroll_blocker, screen_grayscale, usage_overview and onboarding (SharedPreferences
 * `shared_pref`), and the launcher and onboarding DataStore keys (`farhan_data_store`).
 *
 * Invariants:
 *  - CLAIM(catalog-disjoint): [dispositionOf] returns one case per key, so no key is both exportable and an
 *    import-reset key. [SettingsCatalogTest] pins the literal automation keys.
 *  - CLAIM(catalog-default-unsupported): a key the inventory does not name is [SettingDisposition.Unsupported].
 *  - A prefix key needs a non-empty suffix (a package, category or channel name).
 */
object SettingsCatalog {
    private val sharedConfiguration: Map<String, SettingType> = mapOf(
        "notification_service_stats" to SettingType.BOOLEAN,
        "notification_sound_system" to SettingType.BOOLEAN,
        "notification_delivery_testing_mode" to SettingType.BOOLEAN,
        "notification_filter_exceptions" to SettingType.STRING_SET,
        "infinite_scroll_exceptions" to SettingType.STRING_SET,
        "grayscale_white_list" to SettingType.STRING_SET,
        "key_time_limit_packages" to SettingType.STRING_SET,
        "message_array" to SettingType.STRING_SET,
        "notification_batch_window_minutes" to SettingType.INT,
        "notification_standing_threshold_hours" to SettingType.INT,
        "notification_flood_threshold" to SettingType.INT,
        "notification_priority_delivery_settings" to SettingType.STRING,
        "notification_app_queue_v1" to SettingType.STRING,
        "notification_schedule_snapshot_v1" to SettingType.STRING,
        "notification_delivery_schedule" to SettingType.STRING,
        "notification_budget_spent" to SettingType.INT,
        "notification_budget_period_start_minute" to SettingType.INT,
        "notification_hourly_passthrough_count" to SettingType.INT,
        "notification_passthrough_window_hour" to SettingType.INT,
        "notify_me_hour" to SettingType.INT,
        "notify_me_minute" to SettingType.INT,
        "notify_me_schedule_date" to SettingType.LONG,
        "infinite_scroll_service_stats" to SettingType.BOOLEAN,
        "grayscale_service_stats" to SettingType.BOOLEAN,
        "key_launcher_enabled" to SettingType.BOOLEAN,
        "key_time_limiter_enabled" to SettingType.BOOLEAN,
        "infinite_scroll_time_out" to SettingType.INT,
        "count_down_time" to SettingType.INT,
        "custom_message" to SettingType.STRING,
        "auto_cache_key" to SettingType.BOOLEAN,
        "cache_key" to SettingType.BOOLEAN,
        "ignore_launcher_key" to SettingType.BOOLEAN,
        "ignore_farhan_key" to SettingType.BOOLEAN,
        "weekly_usage_report" to SettingType.BOOLEAN,
        "monthly_usage_report" to SettingType.BOOLEAN,
        "yearly_usage_report" to SettingType.BOOLEAN,
    )

    /** Longest match is not needed: no prefix is a prefix of another or of an exact key. */
    private val sharedConfigurationPrefixes: List<Pair<String, SettingType>> = listOf(
        "notification_experiment_" to SettingType.BOOLEAN,
        "notification_sound_app_" to SettingType.BOOLEAN,
        "notification_sound_category_" to SettingType.BOOLEAN,
        "notification_priority_override_" to SettingType.STRING,
        "notification_category_priority_" to SettingType.STRING,
        "notification_priority_locked_" to SettingType.BOOLEAN,
        "notification_channel_priority_offset_" to SettingType.INT,
    )

    private val sharedExcluded: Map<String, ExclusionReason> = mapOf(
        "notification_budget_spend_ledger" to ExclusionReason.HISTORY, // holds notification titles
        "notification_known_package_sources" to ExclusionReason.HISTORY, // learned from observed notifications
        "today_usage_key" to ExclusionReason.HISTORY,
        "yesterday_usage_key" to ExclusionReason.HISTORY,
        "should_combine_db" to ExclusionReason.INTERNAL,
        "sound_control_rule_id" to ExclusionReason.INTERNAL,
        "sound_control_generation" to ExclusionReason.INTERNAL,
        "sound_control_phase" to ExclusionReason.INTERNAL,
        "notification_schedule_legacy_backup" to ExclusionReason.INTERNAL,
        "app_should_show_on_boarding" to ExclusionReason.INTERNAL,
        "notification_filter_should_show_on_boarding" to ExclusionReason.INTERNAL,
        "infinite_scroll_should_show_on_boarding" to ExclusionReason.INTERNAL,
        "grayscale_should_show_on_boarding" to ExclusionReason.INTERNAL,
        "notification_filter_should_showcase" to ExclusionReason.INTERNAL,
        "notification_filter_settings_warning" to ExclusionReason.INTERNAL,
        "should_show_appearance_menu" to ExclusionReason.INTERNAL,
    )

    /** SharedPreferences keys only; the DataStore holds none. */
    private val sharedImportReset: Map<String, ResetValue> = mapOf(
        "notification_automation_credential_v1" to ResetValue.Remove,
        "notification_automation_last_at_v1" to ResetValue.Remove,
        "notification_automation_last_mode_v1" to ResetValue.Remove,
        "notification_automation_last_error_v1" to ResetValue.Remove,
        "notification_automation_enabled_v1" to ResetValue.Bool(false),
    )

    private val dataStoreConfiguration: Map<String, SettingType> = mapOf(
        "imported_widget_profiles_v1" to SettingType.STRING,
        "app_main_switch" to SettingType.BOOLEAN,
        "app_dark_mode_on" to SettingType.STRING,
        "app_theme_colors" to SettingType.STRING,
        "key_selected_delayed_launch_messages" to SettingType.STRING,
        "key_mindful_launch_white_list" to SettingType.STRING_SET,
        "key_delayed_launch_messages" to SettingType.STRING_SET,
        "key_mindful_launch_enabled" to SettingType.BOOLEAN,
        "key_delayed_launch_duration" to SettingType.INT,
    )

    /** Keys written on every import, with their values: the reset half of the shared preferences commit. */
    val importResetPuts: Map<String, SettingValue> =
        sharedImportReset.mapNotNull { (key, after) ->
            (after as? ResetValue.Bool)?.let { key to SettingValue.Bool(it.value) }
        }.toMap()

    fun dispositionOf(store: SettingsStoreId, key: String): SettingDisposition = when (store) {
        SettingsStoreId.SHARED_PREFERENCES -> sharedDisposition(key)
        SettingsStoreId.DATA_STORE ->
            dataStoreConfiguration[key]?.let { SettingDisposition.Configuration(it) } ?: SettingDisposition.Unsupported
    }

    private fun sharedDisposition(key: String): SettingDisposition {
        sharedImportReset[key]?.let { return SettingDisposition.ImportReset(it) }
        sharedExcluded[key]?.let { return SettingDisposition.Excluded(it) }
        sharedConfiguration[key]?.let { return SettingDisposition.Configuration(it) }
        for ((prefix, type) in sharedConfigurationPrefixes) {
            if (key.length > prefix.length && key.startsWith(prefix)) return SettingDisposition.Configuration(type)
        }
        return SettingDisposition.Unsupported
    }
}
