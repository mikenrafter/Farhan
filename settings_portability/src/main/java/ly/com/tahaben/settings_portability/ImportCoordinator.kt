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
 *  - CLAIM(stage-no-write): stage never changes a store; an invalid file changes nothing.
 *  - CLAIM(domain-replace): apply removes catalog Configuration keys absent from the desired set and leaves every
 *    other key (internal flags, usage counters) untouched. Destination automation is an import-reset domain,
 *    separate from transferable configuration: the same preferences commit removes its credential and receipts
 *    and writes enabled=false. Previous captures these raw values too, so rollback restores them exactly.
 *  - CLAIM(journal-first): the Applying record, which holds previous and desired, is durable before the first
 *    store changes.
 *  - CLAIM(rollback-on-doubt): a thrown store failure or an interrupted Applying journal selects rollback.
 *    Rollback restores every component from the journaled previous configuration.
 *  - CLAIM(all-or-previous): after startup returns a result that allows normal operation, every component holds
 *    the desired configuration or every component holds the previous one.
 *  - CLAIM(blocked-not-success): if rollback fails or the journal is unreadable the result is RecoveryRequired,
 *    the journal stays RollingBack (or unreadable) and acknowledge refuses; success is never reported.
 *  - Catches Exception from ports. An Error (including a simulated process death) propagates untouched.
 *
 * STUB: red phase. No behavior yet.
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
     */
    fun stage(fileBytes: ByteArray, limits: DecodeLimits = DecodeLimits()): StageResult =
        StageResult.NotStaged(NotStagedReason.JOURNAL_UNAVAILABLE)

    /** Drives any journal to a terminal phase. Idempotent: safe to call again after a crash or a RecoveryRequired. */
    fun startup(): StartupResult = StartupResult.NoImport

    /** True while an Applying or RollingBack journal exists, or the journal cannot be read. */
    fun blocksNormalOperation(): Boolean = false

    /** Clears an Applied or RolledBack journal. Throws IllegalStateException in any other state. */
    fun acknowledge() = Unit
}

/** Normal-app export. Reads the three stores and keeps catalog Configuration keys only. */
object SettingsExporter {
    fun export(
        source: PortableSource,
        sharedPreferences: PreferencesStore,
        dataStore: PreferencesStore,
        rows: ConfigurationRowsStore,
        validator: SemanticValidator,
    ): PortableSettingsResult = PortableSettingsResult.Ready(
        PortableSettings(source, SettingsConfiguration()),
        ConversionReport(emptySet()),
    )
}
