package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.settings_portability.SettingValue.Bool
import ly.com.tahaben.settings_portability.SettingValue.Int32
import ly.com.tahaben.settings_portability.SettingValue.Int64
import ly.com.tahaben.settings_portability.SettingValue.Text
import ly.com.tahaben.settings_portability.SettingValue.TextSet
import ly.com.tahaben.settings_portability.SettingsStoreId.DATA_STORE
import ly.com.tahaben.settings_portability.SettingsStoreId.SHARED_PREFERENCES
import org.junit.Test

/**
 * Old app files -> portable settings, through the production [LegacySettingsConverter] and [PortableSettingsCodec].
 *
 * The XML below is literal and shaped like the file Android writes for `shared_pref` (one element per key,
 * `<set>` for string sets, escaped `&amp;`). Key names and types come from the Default*Preferences classes;
 * the inventory is in research/settings-portability-red-report.md. Every expected value is written by hand.
 * Secrets, titles and unknown values are synthetic sentinels, not real data.
 */
class LegacySettingsConverterTest {

    private val credentialSentinel = "SYNTHETIC-CREDENTIAL-0123456789abcdef0123456789abcdef0123456789abcdef01234567"
    private val titleSentinel = "SYNTHETIC-NOTIFICATION-TITLE-SENTINEL"
    private val appNameSentinel = "SYNTHETIC-KNOWN-SOURCE-APPNAME-SENTINEL"
    private val unknownSentinel = "SYNTHETIC-UNKNOWN-VALUE-SENTINEL"
    private val dataStoreUnknownSentinel = "SYNTHETIC-DATASTORE-UNKNOWN-SENTINEL"
    private val keptSentinel = "KEPT-MESSAGE-SENTINEL"

    private val snapshotJson =
        """{"version":1,"repeating":[{"sh":9,"sm":0,"eh":12,"em":0,"b":8},{"sh":13,"sm":0,"eh":18,"em":0,"b":5}],""" +
            """"dated":{"2026-12-24":[{"sh":8,"sm":0,"eh":10,"em":0,"b":3}]},""" +
            """"budget":{"spent":6,"start":540,"hourly":2,"hour":10,"day":"2026-11-17","accounting":"2026-11-17:540"}}"""
    private val mirroredScheduleJson = """[{"sh":9,"sm":0,"eh":12,"em":0,"b":8},{"sh":13,"sm":0,"eh":18,"em":0,"b":5}]"""

    private val legacyXml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="notification_service_stats" value="true" />
    <boolean name="notification_delivery_testing_mode" value="false" />
    <boolean name="notification_experiment_alpha" value="true" />
    <boolean name="notification_priority_locked_com.example.chat" value="true" />
    <int name="notification_batch_window_minutes" value="45" />
    <int name="notification_standing_threshold_hours" value="12" />
    <int name="notification_flood_threshold" value="7" />
    <int name="notification_channel_priority_offset_com.example.chat_direct" value="-1" />
    <string name="notification_priority_override_com.example.chat">HIGH</string>
    <string name="notification_category_priority_SOCIAL">LOW</string>
    <string name="notification_app_queue_v1">{"com.example.chat":{"order":"OLDEST_FIRST","rank":2}}</string>
    <string name="notification_priority_delivery_settings">{"HIGH":{"cadence":"BATCHED","budget":"COUNTS"}}</string>
    <string name="notification_schedule_snapshot_v1">$snapshotJson</string>
    <string name="notification_delivery_schedule">$mirroredScheduleJson</string>
    <int name="notification_budget_spent" value="6" />
    <int name="notification_budget_period_start_minute" value="540" />
    <int name="notification_hourly_passthrough_count" value="2" />
    <int name="notification_passthrough_window_hour" value="10" />
    <int name="notify_me_hour" value="8" />
    <int name="notify_me_minute" value="30" />
    <long name="notify_me_schedule_date" value="9007199254740993" />
    <set name="notification_filter_exceptions">
        <string>com.example.b</string>
        <string>com.example.a</string>
    </set>
    <set name="infinite_scroll_exceptions">
        <string>com.example.feed</string>
    </set>
    <set name="message_array">
        <string>$keptSentinel</string>
        <string>Don&apos;t &amp; wait</string>
    </set>
    <set name="grayscale_white_list">
        <string>com.example.map</string>
    </set>
    <set name="key_time_limit_packages">
        <string>com.example.video</string>
    </set>
    <boolean name="key_launcher_enabled" value="true" />
    <boolean name="key_time_limiter_enabled" value="true" />
    <boolean name="infinite_scroll_service_stats" value="true" />
    <boolean name="grayscale_service_stats" value="false" />
    <int name="infinite_scroll_time_out" value="5" />
    <int name="count_down_time" value="10" />
    <string name="custom_message">Breathe &amp; wait</string>
    <boolean name="auto_cache_key" value="true" />
    <boolean name="cache_key" value="false" />
    <boolean name="ignore_launcher_key" value="false" />
    <boolean name="weekly_usage_report" value="true" />
    <boolean name="should_combine_db" value="true" />
    <boolean name="app_should_show_on_boarding" value="false" />
    <boolean name="should_show_appearance_menu" value="false" />
    <long name="today_usage_key" value="3600000" />
    <long name="yesterday_usage_key" value="7200000" />
    <string name="notification_automation_credential_v1">$credentialSentinel</string>
    <boolean name="notification_automation_enabled_v1" value="true" />
    <long name="notification_automation_last_at_v1" value="1700000000000" />
    <string name="notification_budget_spend_ledger">[{"a":"Chat","t":"$titleSentinel","pr":"HIGH","c":2,"ts":1700000000000,"p":540}]</string>
    <string name="notification_known_package_sources">[{"pkg":"com.example.chat","name":"$appNameSentinel"}]</string>
    <string name="future_key_from_a_newer_build">$unknownSentinel</string>
</map>
"""

    private val source = PortableSource("ly.com.tahaben.farhan_alpha", "0.2.6-alpha", 11L, 0)

    private val dataStoreInput: Map<String, SettingValue> = mapOf(
        "app_main_switch" to Bool(false),
        "app_dark_mode_on" to Text("DARK"),
        "app_theme_colors" to Text("Ocean"),
        "key_mindful_launch_enabled" to Bool(true),
        "key_mindful_launch_white_list" to TextSet(setOf("com.example.chat")),
        "key_delayed_launch_duration" to Int32(9),
        "key_delayed_launch_messages" to TextSet(setOf("one", "two")),
        "key_selected_delayed_launch_messages" to Text("one"),
        "future_datastore_key" to Text(dataStoreUnknownSentinel),
    )

    private val rows = ConfigurationRows(
        apps = listOf(
            AppActivityRow("Chat", "com.example.chat", "com.example.chat.Main", 0L),
            AppActivityRow("Work Mail", "com.example.mail", "com.example.mail.Main", 10L),
        ),
        timeLimits = listOf(TimeLimitRow("com.example.video", 1_800_000L, 1_700_000_000_000L)),
    )

    private fun snapshot(
        xml: String = legacyXml,
        dataStore: Map<String, SettingValue> = dataStoreInput,
        rows: ConfigurationRows = this.rows,
    ) = LegacySourceSnapshot(source, xml, dataStore, rows)

    private val acceptAll = SemanticValidator { null }

    private fun PortableSettingsResult.ready(): PortableSettingsResult.Ready {
        assertThat(this).isInstanceOf(PortableSettingsResult.Ready::class.java)
        return this as PortableSettingsResult.Ready
    }

    // why: owner's whole ask. Every app/category/channel/schedule/budget/other-module setting must arrive with its
    //      original type and exact value; nothing may be dropped or defaulted.
    // proves: CLAIM(types-kept), CLAIM(catalog-only)
    // catches: long squeezed through a double (9007199254740993 becomes ...992); int/long swap; a string set
    //          flattened to a string or sorted into a list; a prefix key (app, category, channel) lost because only
    //          exact keys were recognised; schedule or budget keys reset to defaults; a store skipped; rows reordered.
    @Test
    fun `literal legacy files convert to the exact portable settings`() {
        val ready = LegacySettingsConverter.convert(snapshot(), acceptAll).ready()

        assertThat(ready.settings.source).isEqualTo(source)
        assertThat(ready.settings.configuration.sharedPreferences).isEqualTo(
            mapOf(
                "notification_service_stats" to Bool(true),
                "notification_delivery_testing_mode" to Bool(false),
                "notification_experiment_alpha" to Bool(true),
                "notification_priority_locked_com.example.chat" to Bool(true),
                "notification_batch_window_minutes" to Int32(45),
                "notification_standing_threshold_hours" to Int32(12),
                "notification_flood_threshold" to Int32(7),
                "notification_channel_priority_offset_com.example.chat_direct" to Int32(-1),
                "notification_priority_override_com.example.chat" to Text("HIGH"),
                "notification_category_priority_SOCIAL" to Text("LOW"),
                "notification_app_queue_v1" to Text("""{"com.example.chat":{"order":"OLDEST_FIRST","rank":2}}"""),
                "notification_priority_delivery_settings" to Text("""{"HIGH":{"cadence":"BATCHED","budget":"COUNTS"}}"""),
                "notification_schedule_snapshot_v1" to Text(snapshotJson),
                "notification_delivery_schedule" to Text(mirroredScheduleJson),
                "notification_budget_spent" to Int32(6),
                "notification_budget_period_start_minute" to Int32(540),
                "notification_hourly_passthrough_count" to Int32(2),
                "notification_passthrough_window_hour" to Int32(10),
                "notify_me_hour" to Int32(8),
                "notify_me_minute" to Int32(30),
                "notify_me_schedule_date" to Int64(9007199254740993L),
                "notification_filter_exceptions" to TextSet(setOf("com.example.a", "com.example.b")),
                "infinite_scroll_exceptions" to TextSet(setOf("com.example.feed")),
                "message_array" to TextSet(setOf(keptSentinel, "Don't & wait")),
                "grayscale_white_list" to TextSet(setOf("com.example.map")),
                "key_time_limit_packages" to TextSet(setOf("com.example.video")),
                "key_launcher_enabled" to Bool(true),
                "key_time_limiter_enabled" to Bool(true),
                "infinite_scroll_service_stats" to Bool(true),
                "grayscale_service_stats" to Bool(false),
                "infinite_scroll_time_out" to Int32(5),
                "count_down_time" to Int32(10),
                "custom_message" to Text("Breathe & wait"),
                "auto_cache_key" to Bool(true),
                "cache_key" to Bool(false),
                "ignore_launcher_key" to Bool(false),
                "weekly_usage_report" to Bool(true),
            ),
        )
        // DataStore: every catalog key kept as typed; the unknown one is not.
        assertThat(ready.settings.configuration.dataStore).isEqualTo(dataStoreInput - "future_datastore_key")
        assertThat(ready.settings.configuration.rows).isEqualTo(rows)
        assertThat(ready.settings.unsupportedKeys).containsExactly(
            UnsupportedKey(DATA_STORE, "future_datastore_key"),
            UnsupportedKey(SHARED_PREFERENCES, "future_key_from_a_newer_build"),
        ).inOrder()
    }

    // why: the file is the leak surface. Secrets, notification text and unknown values must not be in the bytes
    //      the owner saves, while real settings are. The omitted keys are named so the preview can say what was left.
    // proves: CLAIM(catalog-only)
    // catches: a copy-all-prefs conversion; excluding by a short hand-picked list that misses the ledger or the
    //          known-sources cache; carrying an unknown key's value as "inert data"; a filter that matches nothing
    //          (the positive controls below prove the probe finds text that is present).
    @Test
    fun `secrets history internals and unknown values never reach the saved file`() {
        val ready = LegacySettingsConverter.convert(snapshot(), acceptAll).ready()
        val fileText = String(PortableSettingsCodec.encode(ready.settings), Charsets.UTF_8)

        // positive controls: the probe sees kept values, so absence below means something
        assertThat(fileText).contains(keptSentinel)
        assertThat(fileText).contains("com.example.chat")
        assertThat(fileText).contains("9007199254740993")

        for (sentinel in listOf(credentialSentinel, titleSentinel, appNameSentinel, unknownSentinel, dataStoreUnknownSentinel)) {
            assertThat(fileText).doesNotContain(sentinel)
        }
        for (key in listOf("notification_automation_credential_v1", "notification_budget_spend_ledger", "should_combine_db", "today_usage_key")) {
            assertThat(fileText).doesNotContain(key)
        }
        assertThat(ready.report.omitted).containsExactly(
            OmittedSetting(SHARED_PREFERENCES, "notification_automation_credential_v1", ExclusionReason.SECRET),
            OmittedSetting(SHARED_PREFERENCES, "notification_automation_enabled_v1", ExclusionReason.SECRET),
            OmittedSetting(SHARED_PREFERENCES, "notification_automation_last_at_v1", ExclusionReason.SECRET),
            OmittedSetting(SHARED_PREFERENCES, "notification_budget_spend_ledger", ExclusionReason.HISTORY),
            OmittedSetting(SHARED_PREFERENCES, "notification_known_package_sources", ExclusionReason.HISTORY),
            OmittedSetting(SHARED_PREFERENCES, "today_usage_key", ExclusionReason.HISTORY),
            OmittedSetting(SHARED_PREFERENCES, "yesterday_usage_key", ExclusionReason.HISTORY),
            OmittedSetting(SHARED_PREFERENCES, "should_combine_db", ExclusionReason.INTERNAL),
            OmittedSetting(SHARED_PREFERENCES, "app_should_show_on_boarding", ExclusionReason.INTERNAL),
            OmittedSetting(SHARED_PREFERENCES, "should_show_appearance_menu", ExclusionReason.INTERNAL),
        )
    }

    // why: schedules must never silently turn into defaults. Legacy keys (including the old 23:59 end) are kept
    //      verbatim and handed to the app's own migration; an undecodable schedule fails the conversion.
    // proves: CLAIM(no-silent-default)
    // catches: dropping the legacy schedule key when no snapshot exists; synthesizing a default schedule; a
    //          validator that is never called; a failure swallowed into Ready.
    @Test
    fun `legacy schedule keys are kept verbatim and a schedule the app cannot decode fails the conversion`() {
        val legacyOnly = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="notification_delivery_schedule">[{"sh":0,"sm":0,"eh":23,"em":59,"b":10}]</string>
    <int name="notification_budget_spent" value="4" />
    <int name="notification_budget_period_start_minute" value="0" />
</map>
"""
        val seen = mutableListOf<SettingsConfiguration>()
        // Stand-in for the Android adapter over ScheduleCodec: accepts exactly the one legacy string above.
        val validator = SemanticValidator { configuration ->
            seen += configuration
            val schedule = configuration.sharedPreferences["notification_delivery_schedule"]
            if (schedule == Text("""[{"sh":0,"sm":0,"eh":23,"em":59,"b":10}]""")) null
            else SemanticProblem(SHARED_PREFERENCES, "notification_delivery_schedule")
        }

        val ready = LegacySettingsConverter.convert(snapshot(legacyOnly, emptyMap(), ConfigurationRows()), validator).ready()

        assertThat(ready.settings.configuration.sharedPreferences).isEqualTo(
            mapOf(
                "notification_delivery_schedule" to Text("""[{"sh":0,"sm":0,"eh":23,"em":59,"b":10}]"""),
                "notification_budget_spent" to Int32(4),
                "notification_budget_period_start_minute" to Int32(0),
            ),
        )
        assertThat(seen).hasSize(1)
        assertThat(seen.single().sharedPreferences).isEqualTo(ready.settings.configuration.sharedPreferences)

        val truncatedSchedule = legacyOnly.replace("""[{"sh":0,"sm":0,"eh":23,"em":59,"b":10}]""", """[{"sh":""")
        val failed = LegacySettingsConverter.convert(snapshot(truncatedSchedule, emptyMap(), ConfigurationRows()), validator)

        assertThat(failed).isEqualTo(
            PortableSettingsResult.Failed(
                PortableSettingsError(PortableSettingsErrorCode.SEMANTIC_REJECTED, "sharedPreferences/notification_delivery_schedule"),
            ),
        )
    }

    // why: a damaged or contradictory source must fail loudly. The owner then fixes the source or reports it,
    //      instead of importing a quietly wrong configuration.
    // proves: CLAIM(no-silent-default), CLAIM(strict-input)
    // catches: last-write-wins on a duplicate name; an int that wraps; a string read as an int; an ignored unknown
    //          element; entity expansion; a truncated file treated as a short one; a DataStore type left unchecked.
    @Test
    fun `malformed or contradictory legacy input fails with a specific error and no settings`() {
        fun xml(body: String) = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n$body\n</map>\n"
        val cases = listOf(
            Triple(
                "duplicate name",
                xml("""<int name="notification_flood_threshold" value="1" /><int name="notification_flood_threshold" value="2" />"""),
                PortableSettingsErrorCode.DUPLICATE_KEY,
            ),
            Triple(
                "string where the catalog says int",
                xml("""<string name="notification_batch_window_minutes">45</string>"""),
                PortableSettingsErrorCode.TYPE_MISMATCH,
            ),
            Triple(
                "int out of range",
                xml("""<int name="notification_flood_threshold" value="2147483648" />"""),
                PortableSettingsErrorCode.OUT_OF_RANGE,
            ),
            Triple(
                "int that is not a number",
                xml("""<int name="notification_flood_threshold" value="seven" />"""),
                PortableSettingsErrorCode.MALFORMED_XML,
            ),
            Triple(
                "element Android does not write",
                xml("""<double name="notification_flood_threshold" value="1.0" />"""),
                PortableSettingsErrorCode.MALFORMED_XML,
            ),
            Triple(
                "missing value attribute",
                xml("""<boolean name="notification_service_stats" />"""),
                PortableSettingsErrorCode.MALFORMED_XML,
            ),
            Triple(
                "document type declaration with an entity",
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<!DOCTYPE map [<!ENTITY e \"x\">]>\n<map>\n" +
                    """<string name="custom_message">&e;</string>""" + "\n</map>\n",
                PortableSettingsErrorCode.MALFORMED_XML,
            ),
            Triple(
                "truncated",
                xml("""<int name="notification_flood_threshold" value="1" />""").dropLast(8),
                PortableSettingsErrorCode.MALFORMED_XML,
            ),
        )
        assertThat(cases.map { it.first }).hasSize(8)
        for ((name, document, code) in cases) {
            val result = LegacySettingsConverter.convert(snapshot(document, emptyMap(), ConfigurationRows()), acceptAll)
            assertWithMessage(name).that(result).isInstanceOf(PortableSettingsResult.Failed::class.java)
            assertWithMessage(name).that((result as PortableSettingsResult.Failed).error.code).isEqualTo(code)
        }

        val dataStoreWrongType = LegacySettingsConverter.convert(
            snapshot(xml(""), mapOf("app_main_switch" to Text("false")), ConfigurationRows()),
            acceptAll,
        )
        assertThat(dataStoreWrongType).isEqualTo(
            PortableSettingsResult.Failed(PortableSettingsError(PortableSettingsErrorCode.TYPE_MISMATCH, "dataStore/app_main_switch")),
        )

        // control: the same envelope with a valid body converts, so the failures above come from their bodies.
        val control = LegacySettingsConverter.convert(
            snapshot(xml("""<int name="notification_flood_threshold" value="7" />"""), emptyMap(), ConfigurationRows()),
            acceptAll,
        ).ready()
        assertThat(control.settings.configuration.sharedPreferences).isEqualTo(mapOf("notification_flood_threshold" to Int32(7)))
    }
}
