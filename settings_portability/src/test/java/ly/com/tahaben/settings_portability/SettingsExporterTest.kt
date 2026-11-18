package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.settings_portability.SettingValue.Bool
import ly.com.tahaben.settings_portability.SettingValue.Int32
import ly.com.tahaben.settings_portability.SettingValue.Int64
import ly.com.tahaben.settings_portability.SettingValue.Text
import ly.com.tahaben.settings_portability.SettingValue.TextSet
import org.junit.Test

/**
 * Normal export is a second entry into the same format as rescue conversion, so it gets its own check:
 * "tested once, wired twice" is how a leak ships. Ends with a full cycle into an empty app.
 */
class SettingsExporterTest {

    private val credentialSentinel = "SYNTHETIC-CREDENTIAL-0123456789abcdef0123456789abcdef0123456789abcdef01234567"
    private val source = PortableSource("ly.com.tahaben.farhan_alpha", "0.2.6-alpha", 11L, 0)

    private val sharedContents: Map<String, SettingValue> = mapOf(
        "notification_priority_override_com.example.chat" to Text("HIGH"),
        "notification_channel_priority_offset_com.example.chat_direct" to Int32(-1),
        "notify_me_schedule_date" to Int64(9007199254740993L),
        "notification_filter_exceptions" to TextSet(setOf("com.example.a")),
        "notification_automation_credential_v1" to Text(credentialSentinel),
        "should_combine_db" to Bool(true),
        "today_usage_key" to Int64(42L),
    )
    private val dataStoreContents: Map<String, SettingValue> = mapOf(
        "app_theme_colors" to Text("Ocean"),
        "app_main_switch" to Bool(false),
    )
    private val rows = ConfigurationRows(
        apps = listOf(AppActivityRow("Chat", "com.example.chat", "com.example.chat.Main", 0L)),
        timeLimits = listOf(TimeLimitRow("com.example.chat", 3_600_000L, 1_700_000_000_000L)),
    )

    private val expectedShared: Map<String, SettingValue> = mapOf(
        "notification_priority_override_com.example.chat" to Text("HIGH"),
        "notification_channel_priority_offset_com.example.chat_direct" to Int32(-1),
        "notify_me_schedule_date" to Int64(9007199254740993L),
        "notification_filter_exceptions" to TextSet(setOf("com.example.a")),
    )

    // why: export must keep every setting from all three stores and nothing else. Reading a store "as is" would
    //      put the automation credential in a file the owner stores anywhere.
    // proves: CLAIM(catalog-only)
    // catches: exporting readAll(); forgetting the DataStore or the rows; leaking the credential or counters;
    //          reporting an export as complete while naming nothing omitted.
    @Test
    fun `export keeps the configuration of all three stores and drops secrets and counters`() {
        val faults = Faults()
        val result = SettingsExporter.export(
            source,
            FakePreferencesStore(faults, sharedContents),
            FakePreferencesStore(faults, dataStoreContents),
            FakeRowsStore(faults, rows),
            SemanticValidator { null },
        )

        assertThat(result).isInstanceOf(PortableSettingsResult.Ready::class.java)
        val ready = result as PortableSettingsResult.Ready
        assertThat(ready.settings.configuration).isEqualTo(SettingsConfiguration(expectedShared, dataStoreContents, rows))
        assertThat(ready.report.omitted.map { it.key }).containsExactly(
            "notification_automation_credential_v1", "should_combine_db", "today_usage_key",
        )
        assertThat(faults.operations).isEqualTo(0) // export only reads
    }

    // why: the three public paths must agree on one format. A file exported by the app must restore, through
    //      stage and startup, into an empty app with the same configuration and none of the excluded keys.
    // proves: CLAIM(domain-replace), CLAIM(lossless-long)
    // catches: exporter and importer disagreeing on the catalog or the file shape; a long changed on the way;
    //          a restore into a fresh app that creates keys the source never had.
    @Test
    fun `a file exported by the app restores into an empty app with the same configuration`() {
        val exported = SettingsExporter.export(
            source,
            FakePreferencesStore(Faults(), sharedContents),
            FakePreferencesStore(Faults(), dataStoreContents),
            FakeRowsStore(Faults(), rows),
            SemanticValidator { null },
        ) as PortableSettingsResult.Ready
        val fileBytes = PortableSettingsCodec.encode(exported.settings)

        val faults = Faults()
        val emptyShared = FakePreferencesStore(faults, emptyMap())
        val emptyDataStore = FakePreferencesStore(faults, emptyMap())
        val emptyRows = FakeRowsStore(faults, ConfigurationRows())
        val journal = FakeJournalStorage(faults)
        val validator = SemanticValidator { null }

        assertThat(ImportCoordinator(journal, emptyShared, emptyDataStore, emptyRows, validator).stage(fileBytes))
            .isInstanceOf(StageResult.Staged::class.java)
        val restarted = ImportCoordinator(journal, emptyShared, emptyDataStore, emptyRows, validator).startup()

        assertThat(restarted).isInstanceOf(StartupResult.Applied::class.java)
        assertThat(emptyShared.contents).isEqualTo(expectedShared + ("notification_automation_enabled_v1" to Bool(false)))
        assertThat(emptyDataStore.contents).isEqualTo(dataStoreContents)
        assertThat(emptyRows.contents).isEqualTo(rows)
    }

    private fun queueDocument(length: Int): String = (1..150).joinToString(prefix = "{", postfix = "}", separator = ",") {
        """"com.example.app$it":{"order":"OLDEST_FIRST","rank":$it}"""
    }.also { require(it.length < length) }.padEnd(length, ' ')

    private fun exportQueue(queue: String, validator: SemanticValidator): PortableSettingsResult = SettingsExporter.export(
        source,
        FakePreferencesStore(Faults(), mapOf("notification_app_queue_v1" to Text(queue))),
        FakePreferencesStore(Faults(), emptyMap()),
        FakeRowsStore(Faults(), ConfigurationRows()),
        validator,
    )

    // why: valid per-app queue settings can exceed the original 10,000-character default (review F6).
    // proves: CLAIM(catalog-only), CLAIM(strict-input)
    // catches: keeping the old default, dropping the queue, skipping semantic validation, or unreadable export.
    @Test
    fun `a 12000 character valid queue exports and decodes under the default limits`() {
        val queue = queueDocument(12_000)
        val seen = mutableListOf<SettingsConfiguration>()
        val result = exportQueue(queue, SemanticValidator { seen += it; null })
        assertThat(result).isInstanceOf(PortableSettingsResult.Ready::class.java)
        val settings = (result as PortableSettingsResult.Ready).settings
        assertThat(settings.configuration.sharedPreferences).containsExactly("notification_app_queue_v1", Text(queue))
        assertThat(seen).containsExactly(settings.configuration) // validator positive control
        val decoded = PortableSettingsCodec.decode(PortableSettingsCodec.encode(settings))
        assertThat(decoded).isEqualTo(DecodeResult.Valid(settings))
    }

    // why: a saved export must be importable; an over-cap value must fail before the SAF document is written.
    // proves: CLAIM(strict-input)
    // catches: exporting unlimited strings or trusting an encoder without checking default decode limits.
    @Test
    fun `an export over the default string cap fails with a named error`() {
        val result = exportQueue(queueDocument(262_145), SemanticValidator { null })
        assertThat(result).isInstanceOf(PortableSettingsResult.Failed::class.java)
        assertThat((result as PortableSettingsResult.Failed).error.code).isEqualTo(PortableSettingsErrorCode.TOO_LARGE)
    }

    // why: structurally valid settings can still be unacceptable to the destination app's schedule or enums.
    // proves: CLAIM(no-silent-default)
    // catches: exporter ignoring the SemanticValidator while importer rejects the resulting file.
    @Test
    fun `export invokes semantic validation and reports rejection`() {
        val queue = queueDocument(12_000)
        val seen = mutableListOf<SettingsConfiguration>()
        val result = exportQueue(queue, SemanticValidator {
            seen += it
            SemanticProblem(SettingsStoreId.SHARED_PREFERENCES, "notification_app_queue_v1")
        })
        assertThat(seen).containsExactly(SettingsConfiguration(sharedPreferences = mapOf("notification_app_queue_v1" to Text(queue))))
        assertThat(result).isEqualTo(PortableSettingsResult.Failed(
            PortableSettingsError(PortableSettingsErrorCode.SEMANTIC_REJECTED, "sharedPreferences/notification_app_queue_v1"),
        ))
    }
}
