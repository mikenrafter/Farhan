package ly.com.tahaben.settings_portability

/**
 * The checks that stage (incoming), rescue conversion and export (outgoing) share, so the three paths cannot
 * disagree about what a valid configuration is. Error locations are `store/key`, never a value.
 */
internal object ConfigurationChecks {

    internal fun location(store: SettingsStoreId, key: String) = "${store.locationName}/$key"

    /**
     * Incoming file: every key must be a catalog Configuration key of the right type. An excluded or import-reset
     * key (a secret, a counter) is refused, not skipped, so a hostile file cannot smuggle one in.
     * Checked in a fixed order (shared preferences, then DataStore, keys sorted) so the reported problem is stable.
     */
    fun incomingProblem(configuration: SettingsConfiguration): PortableSettingsError? {
        val stores = listOf(
            SettingsStoreId.SHARED_PREFERENCES to configuration.sharedPreferences,
            SettingsStoreId.DATA_STORE to configuration.dataStore,
        )
        for ((store, values) in stores) {
            for (key in values.keys.sorted()) {
                val code = when (val disposition = SettingsCatalog.dispositionOf(store, key)) {
                    is SettingDisposition.Configuration ->
                        if (values.getValue(key).type == disposition.type) null else PortableSettingsErrorCode.TYPE_MISMATCH
                    is SettingDisposition.Excluded, is SettingDisposition.ImportReset -> PortableSettingsErrorCode.EXCLUDED_KEY
                    SettingDisposition.Unsupported -> PortableSettingsErrorCode.UNSUPPORTED_KEY
                }
                if (code != null) return PortableSettingsError(code, location(store, key))
            }
        }
        return null
    }

    /** What [classifyOutgoing] found in one store. */
    class Classified(
        val configuration: Map<String, SettingValue>,
        val omitted: Set<OmittedSetting>,
        val unsupported: List<UnsupportedKey>,
    )

    sealed interface Outgoing {
        data class Done(val classified: Classified) : Outgoing
        data class Refused(val error: PortableSettingsError) : Outgoing
    }

    /**
     * Source store -> what goes in the file. Catalog Configuration keys of the right type are kept; excluded and
     * import-reset keys are named in [Classified.omitted] (their values are never looked at); unknown keys are named
     * in [Classified.unsupported]. A Configuration key with the wrong type refuses the whole store: skipping it would
     * be a quiet default, and writing it would make a file the importer rejects.
     */
    fun classifyOutgoing(store: SettingsStoreId, values: Map<String, SettingValue>): Outgoing {
        val kept = LinkedHashMap<String, SettingValue>()
        val omitted = LinkedHashSet<OmittedSetting>()
        val unsupported = ArrayList<UnsupportedKey>()
        for (key in values.keys.sorted()) {
            val value = values.getValue(key)
            when (val disposition = SettingsCatalog.dispositionOf(store, key)) {
                is SettingDisposition.Configuration ->
                    if (value.type == disposition.type) kept[key] = value
                    else return Outgoing.Refused(PortableSettingsError(PortableSettingsErrorCode.TYPE_MISMATCH, location(store, key)))
                is SettingDisposition.Excluded, is SettingDisposition.ImportReset ->
                    omitted += OmittedSetting(store, key, checkNotNull(disposition.omissionReason))
                SettingDisposition.Unsupported -> unsupported += UnsupportedKey(store, key)
            }
        }
        return Outgoing.Done(Classified(kept, omitted, unsupported))
    }

    /** The port is untrusted Android code: a throw is a rejection, never a pass. */
    fun semanticProblem(validator: SemanticValidator, configuration: SettingsConfiguration): PortableSettingsError? {
        val problem = try {
            validator.firstProblem(configuration)
        } catch (failure: Exception) {
            return PortableSettingsError(PortableSettingsErrorCode.SEMANTIC_REJECTED, "document")
        }
        return problem?.let {
            PortableSettingsError(PortableSettingsErrorCode.SEMANTIC_REJECTED, location(it.store, it.key))
        }
    }

    /**
     * CLAIM(self-decode): a file this process is about to hand out is decoded again under [limits], so it cannot be
     * a backup that stage would refuse for size, depth or structure. Parsing at most `maxBytes` costs no UI time
     * because export runs off the main thread. [SettingsExporterTest]
     */
    fun saveProblem(settings: PortableSettings, limits: DecodeLimits): PortableSettingsError? {
        val bytes = try {
            PortableSettingsCodec.encode(settings)
        } catch (failure: IllegalArgumentException) {
            return PortableSettingsError(PortableSettingsErrorCode.MALFORMED_JSON, "document")
        }
        return (PortableSettingsCodec.decode(bytes, limits) as? DecodeResult.Invalid)?.error
    }

    fun canonical(rows: ConfigurationRows): ConfigurationRows = ConfigurationRows(
        apps = rows.apps.sortedWith(compareBy({ it.appName }, { it.packageName })),
        timeLimits = rows.timeLimits.sortedBy { it.packageName },
    )

    fun canonical(unsupported: List<UnsupportedKey>): List<UnsupportedKey> =
        unsupported.sortedWith(compareBy({ it.store.locationName }, { it.key }))
}
