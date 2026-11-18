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
 *  - CLAIM(catalog-only): only catalog Configuration keys reach the output. Excluded and import-reset keys are
 *    named in the report; unknown keys are named in unsupportedKeys; neither value is carried.
 *  - CLAIM(types-kept): each value keeps its original type (long stays long, set stays set).
 *  - CLAIM(no-silent-default): XML defects, a duplicate name, a type that disagrees with the catalog, an
 *    unknown element, or a semantic problem yield Failed. Nothing is replaced with a default.
 *  - CLAIM(xml-no-doctype): a document type declaration is refused before any entity is read, whether or not the
 *    platform parser supports the external-entity feature flags (see [LegacyXml]).
 *  - The result is checked like an export ([ConfigurationChecks.saveProblem]), so a conversion never yields a
 *    file that stage would refuse.
 *  - Schedule JSON and legacy schedule keys are kept verbatim. The app's own lazy migration (ScheduleCodec and
 *    DaySchedule.repairLegacy on first read) upgrades them; [SemanticValidator] rejects an undecodable one now.
 */
object LegacySettingsConverter {
    fun convert(
        snapshot: LegacySourceSnapshot,
        validator: SemanticValidator,
        limits: DecodeLimits = DecodeLimits(),
    ): PortableSettingsResult {
        val sharedValues = when (val parsed = LegacyXml.parse(snapshot.sharedPreferencesXml, limits)) {
            is LegacyXml.Parsed.Values -> parsed.values
            is LegacyXml.Parsed.Failure -> return PortableSettingsResult.Failed(parsed.error)
        }
        val shared = when (val outgoing = ConfigurationChecks.classifyOutgoing(SettingsStoreId.SHARED_PREFERENCES, sharedValues)) {
            is ConfigurationChecks.Outgoing.Done -> outgoing.classified
            is ConfigurationChecks.Outgoing.Refused -> return PortableSettingsResult.Failed(outgoing.error)
        }
        val dataStore = when (val outgoing = ConfigurationChecks.classifyOutgoing(SettingsStoreId.DATA_STORE, snapshot.dataStore)) {
            is ConfigurationChecks.Outgoing.Done -> outgoing.classified
            is ConfigurationChecks.Outgoing.Refused -> return PortableSettingsResult.Failed(outgoing.error)
        }
        val configuration = SettingsConfiguration(shared.configuration, dataStore.configuration, ConfigurationChecks.canonical(snapshot.rows))
        val settings = PortableSettings(
            snapshot.source,
            configuration,
            ConfigurationChecks.canonical(shared.unsupported + dataStore.unsupported),
        )
        ConfigurationChecks.semanticProblem(validator, configuration)?.let { return PortableSettingsResult.Failed(it) }
        ConfigurationChecks.saveProblem(settings, limits)?.let { return PortableSettingsResult.Failed(it) }
        return PortableSettingsResult.Ready(settings, ConversionReport(shared.omitted + dataStore.omitted))
    }
}
