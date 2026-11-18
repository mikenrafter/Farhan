package ly.com.tahaben.settings_portability

/** The two key-value stores that hold settings. Room configuration rows are separate ([ConfigurationRows]). */
enum class SettingsStoreId { SHARED_PREFERENCES, DATA_STORE }

/** Row of `apps_db`: a launcher activity. [userSerial] is only meaningful on the same device and user. */
data class AppActivityRow(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val userSerial: Long,
)

/** Row of `TimeLimitEntity`. */
data class TimeLimitRow(
    val packageName: String,
    val timeLimitInMilli: Long,
    val timeAtAddingInMilli: Long,
)

/** Every configuration row. These two tables are the only SQL tables that hold settings. */
data class ConfigurationRows(
    val apps: List<AppActivityRow> = emptyList(),
    val timeLimits: List<TimeLimitRow> = emptyList(),
)

/** The complete configuration across all three stores: what an export holds and what an import replaces. */
data class SettingsConfiguration(
    val sharedPreferences: Map<String, SettingValue> = emptyMap(),
    val dataStore: Map<String, SettingValue> = emptyMap(),
    val rows: ConfigurationRows = ConfigurationRows(),
)

/** Where the settings came from. Informational; never used to decide what to apply. */
data class PortableSource(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val userId: Int,
)

/** A key found in the source that the catalog does not know. Name only: its value is never carried. */
data class UnsupportedKey(val store: SettingsStoreId, val key: String)

data class PortableSettings(
    val source: PortableSource,
    val configuration: SettingsConfiguration,
    val unsupportedKeys: List<UnsupportedKey> = emptyList(),
)

enum class ExclusionReason { SECRET, INTERNAL, HISTORY }

/** A key the catalog deliberately leaves out. Name only, for the preview. */
data class OmittedSetting(val store: SettingsStoreId, val key: String, val reason: ExclusionReason)

data class ConversionReport(val omitted: Set<OmittedSetting>)

enum class PortableSettingsErrorCode {
    MALFORMED_JSON,
    MALFORMED_XML,
    TOO_LARGE,
    TOO_MANY_ENTRIES,
    TOO_DEEP,
    WRONG_FORMAT,
    UNSUPPORTED_VERSION,
    DUPLICATE_KEY,
    TYPE_MISMATCH,
    OUT_OF_RANGE,
    EXCLUDED_KEY,
    UNSUPPORTED_KEY,
    SEMANTIC_REJECTED,
}

/** [location] names a store and key (for example `sharedPreferences/notification_flood_threshold`). Never a value. */
data class PortableSettingsError(val code: PortableSettingsErrorCode, val location: String)

sealed interface PortableSettingsResult {
    data class Ready(val settings: PortableSettings, val report: ConversionReport) : PortableSettingsResult
    data class Failed(val error: PortableSettingsError) : PortableSettingsResult
}
