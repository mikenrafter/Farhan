package ly.com.tahaben.settings_portability

sealed interface SettingDisposition {
    /** A user setting. The stored value must have this type. */
    data class Configuration(val type: SettingType) : SettingDisposition

    /** Known and deliberately not transferred. */
    data class Excluded(val reason: ExclusionReason) : SettingDisposition

    /** Not in the catalog. Reported by name, never applied. */
    data object Unsupported : SettingDisposition
}

/**
 * The one list of which keys are settings. Export, rescue conversion, import validation and the
 * "replace only the configuration domain" rule all read it, so they cannot disagree.
 *
 * Required content (inventory in research/settings-portability-red-report.md): exact keys and key prefixes for
 * SharedPreferences `shared_pref` and the DataStore `farhan_data_store`.
 *
 * STUB: red phase. No behavior yet.
 */
object SettingsCatalog {
    fun dispositionOf(store: SettingsStoreId, key: String): SettingDisposition =
        SettingDisposition.Unsupported
}
