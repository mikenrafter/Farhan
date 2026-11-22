package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.settings_portability.SettingsStoreId.DATA_STORE
import ly.com.tahaben.settings_portability.SettingsStoreId.SHARED_PREFERENCES
import org.junit.Test

/**
 * The catalog against literal key names copied from the app's own constants (not from the catalog). Source files:
 * notification_filter_domain Preferences.kt, ScheduleAutomationCredentials.kt, SharedPreferencesReceiptStore.kt,
 * launcher_domain Preference.kt, onboarding_domain Preferences.kt, usage_overview_domain Preferences.kt and
 * WorkerKeys.kt, infinite_scroll_blocker_domain and screen_grayscale_domain Preferences.kt.
 * The listed keys are the pinned inventory; a key the catalog gains or loses without this test changing is a
 * deliberate decision, not an accident.
 */
class SettingsCatalogTest {

    private fun shared(key: String) = SettingsCatalog.dispositionOf(SHARED_PREFERENCES, key)

    // why: the automation credential, enablement and receipts are secrets the destination must revoke, and they
    //      must never be exportable. The two sets are disjoint by construction; this pins the five real keys.
    // proves: CLAIM(catalog-disjoint)
    // catches: moving the credential into the exportable set; losing one receipt key (so a stale receipt survives
    //          an import); enabled reset to true or removed instead of written false.
    @Test
    fun `every automation key the app writes is an import reset and none is exportable`() {
        val expected = mapOf(
            "notification_automation_credential_v1" to ResetValue.Remove,
            "notification_automation_last_at_v1" to ResetValue.Remove,
            "notification_automation_last_mode_v1" to ResetValue.Remove,
            "notification_automation_last_error_v1" to ResetValue.Remove,
            "notification_automation_enabled_v1" to ResetValue.Bool(false),
        )
        for ((key, after) in expected) {
            assertWithMessage(key).that(shared(key)).isEqualTo(SettingDisposition.ImportReset(after))
            assertWithMessage(key).that(shared(key).omissionReason).isEqualTo(ExclusionReason.SECRET)
            assertWithMessage(key).that(shared(key).isInImportDomain).isTrue()
            assertWithMessage("$key in the DataStore").that(SettingsCatalog.dispositionOf(DATA_STORE, key)).isEqualTo(SettingDisposition.Unsupported)
        }
        // the reset writes: only the enabled flag is a put; the rest are removals
        assertThat(SettingsCatalog.importResetPuts).containsExactly("notification_automation_enabled_v1", SettingValue.Bool(false))
    }

    // why: keys that hold history, learned names or seen-it flags must stay out of a backup, with their reason.
    // proves: CLAIM(catalog-only)
    // catches: dropping an excluded key (it becomes Unsupported and then exports as a name only, or worse is
    //          added to configuration); a wrong reason shown in the preview.
    @Test
    fun `history counters and seen-it flags are excluded with their reason`() {
        val expected = mapOf(
            "notification_budget_spend_ledger" to ExclusionReason.HISTORY,
            "notification_known_package_sources" to ExclusionReason.HISTORY,
            "today_usage_key" to ExclusionReason.HISTORY,
            "yesterday_usage_key" to ExclusionReason.HISTORY,
            "should_combine_db" to ExclusionReason.INTERNAL,
            "notification_schedule_legacy_backup" to ExclusionReason.INTERNAL,
            "app_should_show_on_boarding" to ExclusionReason.INTERNAL,
            "notification_filter_should_show_on_boarding" to ExclusionReason.INTERNAL,
            "infinite_scroll_should_show_on_boarding" to ExclusionReason.INTERNAL,
            "grayscale_should_show_on_boarding" to ExclusionReason.INTERNAL,
            "notification_filter_should_showcase" to ExclusionReason.INTERNAL,
            "notification_filter_settings_warning" to ExclusionReason.INTERNAL,
            "should_show_appearance_menu" to ExclusionReason.INTERNAL,
            "privileged_access_link_drop_pending" to ExclusionReason.INTERNAL,
            "notification_experiment_otp_redaction_seen" to ExclusionReason.INTERNAL,
        )
        for ((key, reason) in expected) {
            assertWithMessage(key).that(shared(key)).isEqualTo(SettingDisposition.Excluded(reason))
            assertWithMessage(key).that(shared(key).isInImportDomain).isFalse()
        }
    }

    // why: prefix keys carry the owner's per-app, per-category and per-channel choices; an exact key can share a
    //      prefix with them (notification_priority_delivery_settings starts like notification_priority_override_...
    //      only by accident of naming, and must keep its own type). An empty suffix is no setting at all.
    // proves: CLAIM(catalog-default-unsupported)
    // catches: matching prefixes before exact keys; a prefix match with an empty suffix; a wrong type for a prefix
    //          (so a valid channel offset is rejected as TYPE_MISMATCH); unknown keys treated as settings.
    @Test
    fun `prefix keys map to their types and unknown or empty-suffix keys are unsupported`() {
        val configuration = mapOf(
            "notification_experiment_alpha" to SettingType.BOOLEAN,
            "notification_priority_override_com.example.chat" to SettingType.STRING,
            "notification_category_priority_SOCIAL" to SettingType.STRING,
            "notification_priority_locked_com.example.chat" to SettingType.BOOLEAN,
            "notification_channel_priority_offset_com.example.chat_direct" to SettingType.INT,
            "notification_priority_delivery_settings" to SettingType.STRING,
            "notification_app_queue_v1" to SettingType.STRING,
            "notification_otp_priority" to SettingType.STRING,
            "notification_otp_packages" to SettingType.STRING_SET,
            "notification_otp_all_apps" to SettingType.BOOLEAN,
            "privileged_access_route" to SettingType.STRING,
            "notify_me_schedule_date" to SettingType.LONG,
            "key_time_limit_packages" to SettingType.STRING_SET,
            "monthly_usage_report" to SettingType.BOOLEAN,
        )
        for ((key, type) in configuration) {
            assertWithMessage(key).that(shared(key)).isEqualTo(SettingDisposition.Configuration(type))
        }
        for (key in listOf(
            "notification_experiment_", "notification_priority_override_", "notification_channel_priority_offset_",
            "future_key_from_a_newer_build", "notification_priority_override", "Notify_me_hour",
        )) {
            assertWithMessage(key).that(shared(key)).isEqualTo(SettingDisposition.Unsupported)
        }
    }

    // why: the DataStore holds exactly eight settings (launcher and onboarding); a SharedPreferences key must not
    //      be accepted there, nor the reverse, or a file could write a key into the wrong store.
    // proves: CLAIM(catalog-default-unsupported)
    // catches: one shared lookup for both stores; a DataStore key missing from the inventory.
    @Test
    fun `the data store lists its eight settings and nothing from shared preferences`() {
        val expected = mapOf(
            "app_main_switch" to SettingType.BOOLEAN,
            "app_dark_mode_on" to SettingType.STRING,
            "app_theme_colors" to SettingType.STRING,
            "key_selected_delayed_launch_messages" to SettingType.STRING,
            "key_mindful_launch_white_list" to SettingType.STRING_SET,
            "key_delayed_launch_messages" to SettingType.STRING_SET,
            "key_mindful_launch_enabled" to SettingType.BOOLEAN,
            "key_delayed_launch_duration" to SettingType.INT,
        )
        for ((key, type) in expected) {
            assertWithMessage(key).that(SettingsCatalog.dispositionOf(DATA_STORE, key)).isEqualTo(SettingDisposition.Configuration(type))
            assertWithMessage("$key in shared preferences").that(shared(key)).isEqualTo(SettingDisposition.Unsupported)
        }
        assertThat(SettingsCatalog.dispositionOf(DATA_STORE, "notification_flood_threshold")).isEqualTo(SettingDisposition.Unsupported)
    }

    // why: the tab switches are the owner's choices and travel with a backup, so a restore brings the bar back as
    //      it was. They live in shared preferences beside the other settings (app FeatureSwitchStore).
    // catches: leaving a switch key out of the inventory (it exports as an unsupported name and a restore drops
    //          it) or accepting one in the DataStore.
    @Test
    fun `the three feature switches are exportable booleans in shared preferences only`() {
        for (key in listOf("feature_switch_usage", "feature_switch_launcher", "feature_switch_focus")) {
            assertWithMessage(key).that(shared(key)).isEqualTo(SettingDisposition.Configuration(SettingType.BOOLEAN))
            assertWithMessage("$key in the DataStore").that(SettingsCatalog.dispositionOf(DATA_STORE, key)).isEqualTo(SettingDisposition.Unsupported)
        }
    }
}
