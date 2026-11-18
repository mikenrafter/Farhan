package ly.com.tahaben.farhan_rescue

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.farhan_rescue.convert.*
import ly.com.tahaben.settings_portability.*
import org.junit.Assert.assertThrows
import org.junit.Test

class RescueConversionTest {
    private val key = "imported_widget_profiles_v1"
    private val source = PortableSource("ly.com.tahaben.farhan_alpha", "0.2.6-alpha", 11, 0)

    private fun assemble(port: WidgetProfilePort, main: Map<String, SettingValue>, widgets: List<Map<String, SettingValue>> = emptyList()) =
        SnapshotAssembler(port, key).assemble(source, "<map />", main, widgets, ConfigurationRows())

    // why: CLAIM(widget-key-canonical): the port's string replaces a stale stored one; no profiles removes the key only
    // when the port says so. catches: copying the old string through, or leaving a stale key beside a new one.
    @Test fun assemblerInjectsThePortsCanonicalString() {
        val main = mapOf("app_main_switch" to SettingValue.Bool(true), key to SettingValue.Text("stale"))
        val snapshot = assemble({ _, _ -> "canonical" }, main)
        assertThat(snapshot.dataStore).containsExactly("app_main_switch", SettingValue.Bool(true), key, SettingValue.Text("canonical"))
        assertThat(assemble({ _, _ -> null }, main).dataStore).containsExactly("app_main_switch", SettingValue.Bool(true))
        assertThat(assemble({ _, _ -> null }, emptyMap()).dataStore).isEmpty()
    }

    // why: the port receives the widget maps and the existing value exactly. catches: dropping the existing profiles
    // (the merge input) or the per-widget states.
    @Test fun assemblerPassesWidgetStatesAndExistingProfilesToThePort() {
        val widgets = listOf(mapOf("text_size" to SettingValue.Int32(24)), mapOf("widget_style" to SettingValue.Text("HORIZONTAL")))
        var seenStates: List<Map<String, SettingValue>>? = null
        var seenExisting: String? = "unset"
        assemble({ states, existing -> seenStates = states; seenExisting = existing; "x" }, mapOf(key to SettingValue.Text("old")), widgets)
        assertThat(seenStates).isEqualTo(widgets)
        assertThat(seenExisting).isEqualTo("old")
    }

    // why: malformed widgets fail the whole export (parent brief). catches: swallowing a port failure or a non-text key.
    @Test fun malformedWidgetInputFailsTheAssembly() {
        assertThrows(SnapshotAssembler.Rejected::class.java) { assemble({ _, _ -> throw IllegalArgumentException() }, emptyMap()) }
        assertThrows(SnapshotAssembler.Rejected::class.java) { assemble({ _, _ -> "x" }, mapOf(key to SettingValue.Int32(1))) }
    }

    private val configuration = SettingsConfiguration(
        sharedPreferences = mapOf("notification_flood_threshold" to SettingValue.Int32(5), "grayscale_white_list" to SettingValue.TextSet(setOf("c.d", "a.b"))),
        dataStore = mapOf("app_main_switch" to SettingValue.Bool(true)),
        rows = ConfigurationRows(
            apps = listOf(AppActivityRow("A", "a.b", "a.b.Main", 3L)),
            // 2^40 = 1,099,511,627,776: beyond Int, so a float or int conversion would change it.
            timeLimits = listOf(TimeLimitRow("a.b", 1L shl 40, 5L)),
        ),
    )
    private fun ready(config: SettingsConfiguration = configuration, unsupported: List<UnsupportedKey> = emptyList()) = PortableSettingsResult.Ready(
        PortableSettings(source, config, unsupported),
        ConversionReport(setOf(OmittedSetting(SettingsStoreId.SHARED_PREFERENCES, "automation_credential", ExclusionReason.SECRET))),
    )
    private val acceptAll = SemanticValidator { null }

    // why: CLAIM(self-decode): the bytes decode back to the same settings, exact long kept, preview counts match.
    // catches: a seal that returns bytes it never decoded, a lossy long, wrong counts, omitted names lost.
    @Test fun sealerReturnsBytesThatDecodeToTheSameSettings() {
        val result = PortableFileSealer.seal(ready(unsupported = listOf(UnsupportedKey(SettingsStoreId.DATA_STORE, "mystery"))), acceptAll, widgetProfileCount = 2, versionKnown = true)
            as ConversionResult.Ready
        val decoded = (PortableSettingsCodec.decode(result.fileBytes) as DecodeResult.Valid).settings
        assertThat(decoded.configuration.rows.timeLimits.single().timeLimitInMilli).isEqualTo(1_099_511_627_776L)
        assertThat(decoded.configuration.sharedPreferences["grayscale_white_list"]).isEqualTo(SettingValue.TextSet(setOf("a.b", "c.d")))
        assertThat(result.preview.sharedPreferenceCount).isEqualTo(2)
        assertThat(result.preview.dataStoreCount).isEqualTo(1)
        assertThat(result.preview.appRowCount).isEqualTo(1)
        assertThat(result.preview.timeLimitCount).isEqualTo(1)
        assertThat(result.preview.widgetProfileCount).isEqualTo(2)
        assertThat(result.preview.unsupported).containsExactly(UnsupportedKey(SettingsStoreId.DATA_STORE, "mystery"))
        assertThat(result.preview.omitted.map { it.key }).containsExactly("automation_credential")
        assertThat(String(result.fileBytes)).doesNotContain("automation_credential")
    }

    // why: the semantic validator and the catalog are both gates. catches: skipping the validator, accepting a key or
    // type the catalog does not list as configuration (a secret or unknown key slipping into the file).
    @Test fun sealerRefusesWhatTheValidatorOrCatalogRefuses() {
        val rejecting = SemanticValidator { SemanticProblem(SettingsStoreId.SHARED_PREFERENCES, "notification_flood_threshold") }
        assertThat(PortableFileSealer.seal(ready(), rejecting, 0, true)).isInstanceOf(ConversionResult.Failed::class.java)

        val unknownKey = configuration.copy(sharedPreferences = configuration.sharedPreferences + ("zzz_unknown_key" to SettingValue.Text("x")))
        assertThat((PortableFileSealer.seal(ready(unknownKey), acceptAll, 0, true) as ConversionResult.Failed).failure).isEqualTo(ConversionFailure.SELF_CHECK_FAILED)

        val wrongType = configuration.copy(sharedPreferences = mapOf("notification_flood_threshold" to SettingValue.Text("5")))
        assertThat((PortableFileSealer.seal(ready(wrongType), acceptAll, 0, true) as ConversionResult.Failed).failure).isEqualTo(ConversionFailure.SELF_CHECK_FAILED)
    }

    // why: a file that exceeds the shared limits must not be offered (spec: export validates under default limits).
    // catches: sealing without decoding under limits.
    @Test fun sealerRefusesAFileOverTheSharedSizeLimit() {
        val big = configuration.copy(sharedPreferences = mapOf("grayscale_white_list" to SettingValue.TextSet((1..30_000).map { "package.number$it" }.toSet())))
        assertThat(PortableFileSealer.seal(ready(big), acceptAll, 0, true)).isInstanceOf(ConversionResult.Failed::class.java)
    }
}
