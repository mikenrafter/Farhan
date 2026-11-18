package ly.com.tahaben.settings_portability

sealed interface StageResult {
    /** Validated and journaled. No store has changed. The app now asks the owner to close and reopen. */
    data class Staged(val preview: ImportPreview) : StageResult
    data class Rejected(val error: PortableSettingsError) : StageResult
    data class NotStaged(val reason: NotStagedReason) : StageResult
}

enum class NotStagedReason { IMPORT_IN_PROGRESS, JOURNAL_UNAVAILABLE }

sealed interface StartupResult {
    /** True only when the app may initialize stores, workers and screens. */
    val allowsNormalOperation: Boolean

    data object NoImport : StartupResult { override val allowsNormalOperation get() = true }
    data class Applied(val preview: ImportPreview) : StartupResult { override val allowsNormalOperation get() = true }
    data class RolledBack(val failure: ImportFailure) : StartupResult { override val allowsNormalOperation get() = true }

    /** Rollback or the journal itself failed. Stores may be mixed. Show recovery UI; call startup again to retry. */
    data class RecoveryRequired(val failure: ImportFailure) : StartupResult {
        override val allowsNormalOperation get() = false
    }
}

/**
 * Stages, applies and recovers a full settings replace across the three stores.
 *
 * Usage: [stage] while the app runs; [startup] first thing in the next process, before Hilt-created stores,
 * WorkManager or any screen reads settings.
 *
 * Invariants:
 *  - CLAIM(stage-no-write): stage never changes a store; an invalid file changes nothing, and the only durable
 *    write is the Staged journal record, after every check passed.
 *  - CLAIM(domain-replace): apply removes catalog Configuration keys absent from the desired set and leaves every
 *    other key (internal flags, usage counters, unknown keys) untouched. Destination automation is an import-reset
 *    domain, separate from transferable configuration: the same preferences commit removes its credential and
 *    receipts and writes enabled=false. Previous captures these raw values too, so rollback restores them exactly.
 *  - CLAIM(journal-first): the Applying record, which holds previous and desired, is durable before the first
 *    store changes.
 *  - CLAIM(rollback-on-doubt): a thrown store failure or an interrupted Applying journal selects rollback.
 *    Rollback restores every component from the journaled previous configuration, attempting all of them even
 *    when one fails.
 *  - CLAIM(all-or-previous): after startup returns a result that allows normal operation, every component holds
 *    the desired configuration or every component holds the previous one.
 *  - CLAIM(blocked-not-success): if rollback fails, the journal is unreadable or a journal write fails, the result
 *    is RecoveryRequired and success is never reported. The last durable phase stays on disk: Applying and
 *    RollingBack block normal operation and a later startup converges from them (Applying always rolls back).
 *  - CLAIM(previous-raw): previous is captured at startup, not at staging, from the raw store content of every
 *    import-domain key, with no type validation or coercion. Only incoming files are type-validated.
 *  - Catches Exception from ports. An Error (including a simulated process death) propagates untouched.
 *
 * Mating parts: [SettingsExporter] and the rescue converter produce the files [stage] consumes.
 */
class ImportCoordinator(
    private val journal: ImportJournalStorage,
    private val sharedPreferences: PreferencesStore,
    private val dataStore: PreferencesStore,
    private val rows: ConfigurationRowsStore,
    private val validator: SemanticValidator,
) {
    /** Decode, catalog-check and semantic-check [fileBytes], then journal it as Staged. Replaces an older Staged.
     * Refuses Applying, RollingBack and unacknowledged terminal outcomes without a durable mutation.
     * An unreadable journal, or a failed journal write, is NotStaged(JOURNAL_UNAVAILABLE).
     */
    fun stage(fileBytes: ByteArray, limits: DecodeLimits = DecodeLimits()): StageResult {
        val existing = try {
            journal.read()
        } catch (unreadable: Exception) {
            return StageResult.NotStaged(NotStagedReason.JOURNAL_UNAVAILABLE)
        }
        if (existing != null && existing !is ImportJournal.Staged) return StageResult.NotStaged(NotStagedReason.IMPORT_IN_PROGRESS)

        val settings = when (val decoded = PortableSettingsCodec.decode(fileBytes, limits)) {
            is DecodeResult.Valid -> decoded.settings
            is DecodeResult.Invalid -> return StageResult.Rejected(decoded.error)
        }
        ConfigurationChecks.incomingProblem(settings.configuration)?.let { return StageResult.Rejected(it) }
        ConfigurationChecks.semanticProblem(validator, settings.configuration)?.let { return StageResult.Rejected(it) }

        val preview = ImportPreview(
            source = settings.source,
            sharedPreferenceCount = settings.configuration.sharedPreferences.size,
            dataStoreCount = settings.configuration.dataStore.size,
            appActivityRowCount = settings.configuration.rows.apps.size,
            timeLimitRowCount = settings.configuration.rows.timeLimits.size,
            unsupportedKeys = settings.unsupportedKeys,
        )
        try {
            journal.write(ImportJournal.Staged(settings.configuration, preview))
        } catch (failure: Exception) {
            return StageResult.NotStaged(NotStagedReason.JOURNAL_UNAVAILABLE)
        }
        return StageResult.Staged(preview)
    }

    /** Drives any journal to a terminal phase. Idempotent: safe to call again after a crash or a RecoveryRequired. */
    fun startup(): StartupResult {
        val current = try {
            journal.read()
        } catch (unreadable: Exception) {
            return StartupResult.RecoveryRequired(ImportFailure(FailureReason.JOURNAL_UNREADABLE))
        }
        return when (current) {
            null -> StartupResult.NoImport
            is ImportJournal.Applied -> StartupResult.Applied(current.preview)
            is ImportJournal.RolledBack -> StartupResult.RolledBack(current.failure)
            is ImportJournal.Staged -> applyStaged(current)
            is ImportJournal.Applying -> rollBack(current.previous, ImportFailure(FailureReason.INTERRUPTED), alreadyRollingBack = false)
            is ImportJournal.RollingBack -> rollBack(current.previous, current.failure, alreadyRollingBack = true)
        }
    }

    /** True while an Applying or RollingBack journal exists, or the journal cannot be read. */
    fun blocksNormalOperation(): Boolean = try {
        when (journal.read()) {
            is ImportJournal.Applying, is ImportJournal.RollingBack -> true
            is ImportJournal.Staged, is ImportJournal.Applied, is ImportJournal.RolledBack, null -> false
        }
    } catch (unreadable: Exception) {
        true
    }

    /**
     * Clears an Applied or RolledBack journal. Throws IllegalStateException in any other state, including an
     * unreadable journal. A failed clear propagates and leaves the terminal journal, so acknowledging can be retried.
     */
    fun acknowledge() {
        val current = try {
            journal.read()
        } catch (unreadable: Exception) {
            throw IllegalStateException("the import journal is unreadable")
        }
        check(current is ImportJournal.Applied || current is ImportJournal.RolledBack) {
            "nothing to acknowledge: journal is ${current?.let { it::class.simpleName } ?: "absent"}"
        }
        journal.clear()
    }

    // ---- apply ------------------------------------------------------------------------------------------

    private fun applyStaged(staged: ImportJournal.Staged): StartupResult {
        val previous = try {
            SettingsConfiguration(
                sharedPreferences = domainOf(SettingsStoreId.SHARED_PREFERENCES, sharedPreferences.readAll()),
                dataStore = domainOf(SettingsStoreId.DATA_STORE, dataStore.readAll()),
                rows = rows.read(),
            )
        } catch (unreadable: Exception) {
            // Nothing to roll back to, so nothing is written: the import is declined.
            val failure = ImportFailure(FailureReason.PREVIOUS_UNREADABLE)
            return if (writeJournal(ImportJournal.RolledBack(failure))) StartupResult.RolledBack(failure) else journalWriteFailed()
        }
        // ↳ CLAIM(journal-first)
        if (!writeJournal(ImportJournal.Applying(previous, staged.desired, staged.preview))) return journalWriteFailed()

        for (component in SettingsComponent.entries) {
            try {
                write(component, staged.desired, withReset = true)
            } catch (failure: Exception) {
                return rollBack(previous, ImportFailure(FailureReason.STORE_FAILED, component), alreadyRollingBack = false)
            }
        }
        return if (writeJournal(ImportJournal.Applied(staged.preview))) StartupResult.Applied(staged.preview) else journalWriteFailed()
    }

    /**
     * Writes [target] to one component. [withReset] adds the import-reset puts to the shared preferences commit; it
     * is true for the forward apply only, because a rollback target already holds the raw reset values.
     * Removals are computed from the store's current import-domain keys, so a key the target lacks is deleted.
     */
    private fun write(component: SettingsComponent, target: SettingsConfiguration, withReset: Boolean) {
        when (component) {
            SettingsComponent.CONFIGURATION_ROWS -> rows.replace(target.rows)
            SettingsComponent.DATA_STORE -> replaceDomain(SettingsStoreId.DATA_STORE, dataStore, target.dataStore)
            SettingsComponent.SHARED_PREFERENCES -> replaceDomain(
                SettingsStoreId.SHARED_PREFERENCES,
                sharedPreferences,
                if (withReset) target.sharedPreferences + SettingsCatalog.importResetPuts else target.sharedPreferences,
            )
        }
    }

    private fun replaceDomain(store: SettingsStoreId, target: PreferencesStore, put: Map<String, SettingValue>) {
        val removeKeys = domainOf(store, target.readAll()).keys - put.keys
        target.apply(removeKeys, put)
    }

    /** Keys the import owns, with their raw values. No type check: an older build may have stored another type. */
    private fun domainOf(store: SettingsStoreId, all: Map<String, SettingValue>): Map<String, SettingValue> =
        all.filterKeys { SettingsCatalog.dispositionOf(store, it).isInImportDomain }

    // ---- rollback ---------------------------------------------------------------------------------------

    private fun rollBack(previous: SettingsConfiguration, failure: ImportFailure, alreadyRollingBack: Boolean): StartupResult {
        // ↳ CLAIM(rollback-on-doubt): RollingBack (previous + cause) is durable before the first restore.
        if (!alreadyRollingBack && !writeJournal(ImportJournal.RollingBack(previous, failure))) return journalWriteFailed()

        var restoreFailed = false
        for (component in SettingsComponent.entries.reversed()) {
            try {
                write(component, previous, withReset = false)
            } catch (unrestored: Exception) {
                restoreFailed = true // keep going: every component that can be restored should be
            }
        }
        // ↳ CLAIM(blocked-not-success)
        if (restoreFailed) return StartupResult.RecoveryRequired(failure)
        return if (writeJournal(ImportJournal.RolledBack(failure))) StartupResult.RolledBack(failure) else journalWriteFailed()
    }

    private fun writeJournal(record: ImportJournal): Boolean = try {
        journal.write(record)
        true
    } catch (failure: Exception) {
        false
    }

    private fun journalWriteFailed() = StartupResult.RecoveryRequired(ImportFailure(FailureReason.JOURNAL_WRITE_FAILED))
}

/**
 * Normal-app export. Reads the three stores and keeps catalog Configuration keys only.
 *
 * Invariants:
 *  - CLAIM(catalog-only): only Configuration keys are written; excluded and import-reset keys are named in the
 *    report with their reason, unknown keys are named in unsupportedKeys, and no value of either is read out.
 *  - CLAIM(self-decode): the file is decoded under [limits] and checked by [validator] before the result is Ready,
 *    so a saved export is one that [ImportCoordinator.stage] accepts.
 *  - A Configuration key whose stored type differs from the catalog fails with TYPE_MISMATCH. Skipping it would
 *    lose a setting silently.
 */
object SettingsExporter {
    fun export(
        source: PortableSource,
        sharedPreferences: PreferencesStore,
        dataStore: PreferencesStore,
        rows: ConfigurationRowsStore,
        validator: SemanticValidator,
        limits: DecodeLimits = DecodeLimits(),
    ): PortableSettingsResult {
        val shared = when (val outgoing = ConfigurationChecks.classifyOutgoing(SettingsStoreId.SHARED_PREFERENCES, sharedPreferences.readAll())) {
            is ConfigurationChecks.Outgoing.Done -> outgoing.classified
            is ConfigurationChecks.Outgoing.Refused -> return PortableSettingsResult.Failed(outgoing.error)
        }
        val store = when (val outgoing = ConfigurationChecks.classifyOutgoing(SettingsStoreId.DATA_STORE, dataStore.readAll())) {
            is ConfigurationChecks.Outgoing.Done -> outgoing.classified
            is ConfigurationChecks.Outgoing.Refused -> return PortableSettingsResult.Failed(outgoing.error)
        }
        val configuration = SettingsConfiguration(shared.configuration, store.configuration, ConfigurationChecks.canonical(rows.read()))
        val settings = PortableSettings(source, configuration, ConfigurationChecks.canonical(shared.unsupported + store.unsupported))
        ConfigurationChecks.saveProblem(settings, limits)?.let { return PortableSettingsResult.Failed(it) }
        ConfigurationChecks.semanticProblem(validator, configuration)?.let { return PortableSettingsResult.Failed(it) }
        return PortableSettingsResult.Ready(settings, ConversionReport(shared.omitted + store.omitted))
    }
}
