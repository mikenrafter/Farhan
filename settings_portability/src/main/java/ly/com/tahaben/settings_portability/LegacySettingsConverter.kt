package ly.com.tahaben.settings_portability

/**
 * Checks meaning the pure module cannot know: schedule JSON, enum names and similar. The Android side
 * implements it with the existing ScheduleCodec and domain enums. Returns the first problem, or null.
 */
fun interface SemanticValidator {
    fun firstProblem(configuration: SettingsConfiguration): SemanticProblem?
}

/** Names the setting only. Never carries its value. */
data class SemanticProblem(val store: SettingsStoreId, val key: String)

/** What the rescue helper reads out of the copied source: raw files plus the already-decoded typed stores. */
data class LegacySourceSnapshot(
    val source: PortableSource,
    /** Raw `shared_prefs/shared_pref.xml`. */
    val sharedPreferencesXml: String,
    /** DataStore decoded through the public DataStore API by an adapter; not protobuf bytes. */
    val dataStore: Map<String, SettingValue>,
    /** Result of selecting only the configuration rows from the copied database. */
    val rows: ConfigurationRows,
)

/**
 * Old app files -> portable settings. Parses legacy SharedPreferences XML strictly and applies [SettingsCatalog].
 *
 * Invariants:
 *  - CLAIM(catalog-only): only catalog Configuration keys reach the output. Excluded keys are named in the
 *    report; unknown keys are named in unsupportedKeys; neither value is carried.
 *  - CLAIM(types-kept): each value keeps its original type (long stays long, set stays set).
 *  - CLAIM(no-silent-default): XML defects, a duplicate name, a type that disagrees with the catalog, an
 *    unknown element, or a semantic problem yield Failed. Nothing is replaced with a default.
 *  - Schedule JSON and legacy schedule keys are kept verbatim. The app's own lazy migration (ScheduleCodec and
 *    DaySchedule.repairLegacy on first read) upgrades them; [SemanticValidator] rejects an undecodable one now.
 *
 * STUB: red phase. No behavior yet.
 */
object LegacySettingsConverter {
    fun convert(snapshot: LegacySourceSnapshot, validator: SemanticValidator): PortableSettingsResult =
        PortableSettingsResult.Ready(
            PortableSettings(snapshot.source, SettingsConfiguration()),
            ConversionReport(emptySet()),
        )
}
