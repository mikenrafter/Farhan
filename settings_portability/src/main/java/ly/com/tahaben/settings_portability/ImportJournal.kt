package ly.com.tahaben.settings_portability

enum class SettingsComponent {
    // Declaration order is the apply order: SQL first (transactional), the preference stores after.
    CONFIGURATION_ROWS,
    DATA_STORE,
    SHARED_PREFERENCES,
}

enum class FailureReason {
    /** A store threw while applying or restoring. */
    STORE_FAILED,

    /** The process died while the import was applying. Nothing says which stores were written. */
    INTERRUPTED,

    /** The current settings could not be read, so nothing was changed. */
    PREVIOUS_UNREADABLE,

    /** A journal exists but cannot be decoded. Only [StartupResult.RecoveryRequired] carries this. */
    JOURNAL_UNREADABLE,

    /**
     * A journal write or clear failed, so the next phase is not durable. The last durable phase stays on disk
     * and a later startup converges from it. Only [StartupResult.RecoveryRequired] carries this.
     */
    JOURNAL_WRITE_FAILED,
}

/** [component] is set for STORE_FAILED. Carries no exception text, which could include values. */
data class ImportFailure(val reason: FailureReason, val component: SettingsComponent? = null)

data class ImportPreview(
    val source: PortableSource,
    val sharedPreferenceCount: Int,
    val dataStoreCount: Int,
    val appActivityRowCount: Int,
    val timeLimitRowCount: Int,
    val unsupportedKeys: List<UnsupportedKey>,
)

/**
 * The durable record of one import. Each case holds only what its phase needs, so an Applying journal without a
 * previous configuration cannot exist.
 *
 * Staged -> Applying -> Applied
 *                   \-> RollingBack -> RolledBack
 * Staged -> RolledBack(PREVIOUS_UNREADABLE) when the current settings cannot be read, since no store was touched.
 * Applied and RolledBack stay until [ImportCoordinator.acknowledge], so a crash after the work still shows the
 * outcome. That is at-least-once reporting; there is no exactly-once promise.
 */
sealed interface ImportJournal {
    data class Staged(val desired: SettingsConfiguration, val preview: ImportPreview) : ImportJournal
    data class Applying(
        val previous: SettingsConfiguration,
        val desired: SettingsConfiguration,
        val preview: ImportPreview,
    ) : ImportJournal
    data class RollingBack(val previous: SettingsConfiguration, val failure: ImportFailure) : ImportJournal
    data class Applied(val preview: ImportPreview) : ImportJournal
    data class RolledBack(val failure: ImportFailure) : ImportJournal
}

/** Read found bytes it cannot decode. Must never be reported as "no journal". */
class JournalUnreadableException(cause: Throwable? = null) : Exception("Import journal is unreadable", cause)

/** Lives under no_backup. [write] returns only after the record is durable and replaces the old one atomically. */
interface ImportJournalStorage {
    /** Null only when no journal exists. Throws [JournalUnreadableException] when one exists but cannot be read. */
    fun read(): ImportJournal?
    fun write(journal: ImportJournal)
    fun clear()
}
