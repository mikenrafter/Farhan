package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.settings_portability.SettingValue.Int32
import org.junit.Test

class SnoozeSettingsPortabilityTest {
    private val key = "notification_snooze_minutes"
    private val source = PortableSource("ly.com.tahaben.farhan_alpha", "0.2.6-alpha", 11L, 0)
    private val faults = Faults()
    private val dataStore = FakePreferencesStore(faults, emptyMap())
    private val rows = FakeRowsStore(faults, ConfigurationRows())
    private val journal = FakeJournalStorage(faults)
    private val destination = FakePreferencesStore(faults, mapOf(key to Int32(15)))
    private val validator = SemanticValidator { null }

    private fun importer() = ImportCoordinator(journal, destination, dataStore, rows, validator)

    // why: backup and restore must preserve the owner's duration, including a choice other than the default.
    // catches: dropping the duration on export, encoding it with the wrong type, or silently defaulting on import.
    @Test
    fun `a portable backup restores the selected snooze minutes`() {
        val original = FakePreferencesStore(faults, mapOf(key to Int32(30)))
        val exported = SettingsExporter.export(source, original, dataStore, rows, validator)
        val expectedSettings = PortableSettings(
            source = source,
            configuration = SettingsConfiguration(sharedPreferences = mapOf(key to Int32(30))),
        )

        assertThat(exported).isEqualTo(PortableSettingsResult.Ready(expectedSettings, ConversionReport(emptySet())))
        val bytes = PortableSettingsCodec.encode((exported as PortableSettingsResult.Ready).settings)
        assertThat(PortableSettingsCodec.decode(bytes)).isEqualTo(DecodeResult.Valid(expectedSettings))
        val staged = importer().stage(bytes)
        assertThat(staged).isInstanceOf(StageResult.Staged::class.java)
        val preview = (staged as StageResult.Staged).preview
        assertThat(preview.sharedPreferenceCount).isEqualTo(1)
        assertThat(destination.contents).containsEntry(key, Int32(15))

        assertThat(importer().startup()).isEqualTo(StartupResult.Applied(preview))
        assertThat(destination.contents).containsEntry(key, Int32(30))
    }

    // why: an int may have the right wire type and still be outside the six allowed duration choices.
    // catches: validation that checks only the INT type, clamps 3 to 15, or journals the rejected import.
    @Test
    fun `a three minute value is rejected before staging`() {
        val file = Json.document(shared = Json.groups(ints = """"notification_snooze_minutes":3"""))

        assertThat(importer().stage(file.toByteArray())).isEqualTo(
            StageResult.Rejected(PortableSettingsError(PortableSettingsErrorCode.SEMANTIC_REJECTED, "sharedPreferences/$key")),
        )
        assertThat(journal.current).isNull()
        assertThat(destination.contents).containsExactly(key, Int32(15))
    }
}
