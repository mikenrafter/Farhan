package ly.com.tahaben.farhan_rescue.convert

import ly.com.tahaben.settings_portability.*

enum class ConversionFailure {
    FILES_MISSING,
    COPY_UNREADABLE,
    NOT_UTF8,
    DATASTORE_CORRUPT,
    DATASTORE_UNSUPPORTED_TYPE,
    DATABASE_CORRUPT,
    DATABASE_MISSING_TABLES,
    DATABASE_UNREADABLE,
    FILE_TOO_LARGE,
    WIDGET_PROFILE_INVALID,
    SETTINGS_REJECTED,
    SELF_CHECK_FAILED,
}

/** Counts and names for the preview. No value of any setting, ever. */
data class ExportPreview(
    val source: PortableSource,
    val versionKnown: Boolean,
    val sharedPreferenceCount: Int,
    val dataStoreCount: Int,
    val appRowCount: Int,
    val timeLimitCount: Int,
    val widgetProfileCount: Int,
    /** Keys the catalog does not know: their names are listed, their values were not transferred. */
    val unsupported: List<UnsupportedKey>,
    /** Keys deliberately left out (secrets, internal state, history), by name and reason. */
    val omitted: List<OmittedSetting>,
)

sealed interface ConversionResult {
    data class Ready(val fileBytes: ByteArray, val preview: ExportPreview) : ConversionResult
    /** [location] is a store/key name such as `sharedPreferences/notification_flood_threshold`, or null. */
    data class Failed(val failure: ConversionFailure, val location: String? = null) : ConversionResult
}

/** The one place widget profiles (derived from per-widget state) are turned into a catalog string setting. */
fun interface WidgetProfilePort {
    /**
     * [widgetStates]: one map per copied `appWidget-*.preferences_pb`. [existingEncoded]: the imported-profiles string
     * already in the main DataStore, if any. Returns the canonical encoded string, or null when there are no profiles.
     * Throws [IllegalArgumentException] for a malformed profile (the export then fails whole).
     */
    fun encodedProfiles(widgetStates: List<Map<String, SettingValue>>, existingEncoded: String?): String?
}

/**
 * Builds the converter's input from decoded pieces.
 *
 * CLAIM(widget-key-canonical): the profiles key in the snapshot is always the port's canonical string; a
 * pre-existing value in the main DataStore is merged, never copied through as is [SnapshotAssemblerTest].
 */
class SnapshotAssembler(private val widgets: WidgetProfilePort, private val widgetKey: String) {
    class Rejected : RuntimeException("widget profiles are malformed")

    fun assemble(
        source: PortableSource,
        preferencesXml: String,
        mainDataStore: Map<String, SettingValue>,
        widgetStates: List<Map<String, SettingValue>>,
        rows: ConfigurationRows,
    ): LegacySourceSnapshot {
        val existing = when (val value = mainDataStore[widgetKey]) {
            null -> null
            is SettingValue.Text -> value.value
            else -> throw Rejected()
        }
        val encoded = try {
            widgets.encodedProfiles(widgetStates, existing)
        } catch (failure: Exception) {
            throw Rejected()
        }
        val dataStore = mainDataStore - widgetKey + (if (encoded != null) mapOf(widgetKey to SettingValue.Text(encoded)) else emptyMap())
        return LegacySourceSnapshot(source, preferencesXml, dataStore, rows)
    }
}

/**
 * Last gate before a file may be offered to the Storage Access Framework.
 *
 * CLAIM(self-decode): the bytes are decoded by the shared codec under default limits, re-encode to the same bytes,
 * pass the semantic validator, and contain only catalog Configuration keys of the catalog's type; anything else is
 * [ConversionFailure.SELF_CHECK_FAILED] and no file exists [PortableFileSealerTest].
 */
object PortableFileSealer {
    fun seal(
        ready: PortableSettingsResult.Ready,
        validator: SemanticValidator,
        widgetProfileCount: Int,
        versionKnown: Boolean,
        limits: DecodeLimits = DecodeLimits(),
    ): ConversionResult {
        val bytes = try {
            PortableSettingsCodec.encode(ready.settings)
        } catch (failure: IllegalArgumentException) {
            return ConversionResult.Failed(ConversionFailure.SELF_CHECK_FAILED)
        }
        val decoded = (PortableSettingsCodec.decode(bytes, limits) as? DecodeResult.Valid)?.settings
            ?: return ConversionResult.Failed(ConversionFailure.SELF_CHECK_FAILED)
        val configuration = decoded.configuration
        if (!PortableSettingsCodec.encode(decoded).contentEquals(bytes)) return ConversionResult.Failed(ConversionFailure.SELF_CHECK_FAILED)
        validator.firstProblem(configuration)?.let { return ConversionResult.Failed(ConversionFailure.SELF_CHECK_FAILED, "${it.store}/${it.key}") }
        for ((store, values) in listOf(SettingsStoreId.SHARED_PREFERENCES to configuration.sharedPreferences, SettingsStoreId.DATA_STORE to configuration.dataStore)) {
            for ((key, value) in values) {
                val disposition = SettingsCatalog.dispositionOf(store, key)
                if (disposition !is SettingDisposition.Configuration || disposition.type != value.type) {
                    return ConversionResult.Failed(ConversionFailure.SELF_CHECK_FAILED, "$store/$key")
                }
            }
        }
        val preview = ExportPreview(
            source = decoded.source,
            versionKnown = versionKnown,
            sharedPreferenceCount = configuration.sharedPreferences.size,
            dataStoreCount = configuration.dataStore.size,
            appRowCount = configuration.rows.apps.size,
            timeLimitCount = configuration.rows.timeLimits.size,
            widgetProfileCount = widgetProfileCount,
            unsupported = decoded.unsupportedKeys,
            omitted = ready.report.omitted.sortedWith(compareBy({ it.store }, { it.key })),
        )
        return ConversionResult.Ready(bytes, preview)
    }
}
