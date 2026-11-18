package ly.com.tahaben.settings_portability

/**
 * A key-value store (SharedPreferences or DataStore) as the importer sees it. Adapters stay dumb: the shared
 * code decides which keys belong to the configuration domain, via [SettingsCatalog].
 */
interface PreferencesStore {
    /** Every key in the store, settings and internals alike. */
    fun readAll(): Map<String, SettingValue>

    /**
     * Removes [removeKeys] and writes [put] as one atomic, durable commit. Idempotent. Throws on failure.
     * Must not touch any other key.
     */
    fun apply(removeKeys: Set<String>, put: Map<String, SettingValue>)
}

/** The configuration rows (`apps_db`, `TimeLimitEntity`). Never the history tables. */
interface ConfigurationRowsStore {
    fun read(): ConfigurationRows

    /** Replaces both tables in one transaction. Idempotent. Throws on failure, leaving the old rows. */
    fun replace(rows: ConfigurationRows)
}

/** The smallest SQL surface the shared code needs. Android adapters wrap SQLiteDatabase; tests wrap JDBC. */
interface SqlSession {
    /** Each row is a list of Long, String or null in select-list order. */
    fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
    fun execute(sql: String, args: List<Any?> = emptyList())
}

interface SqlTransactionRunner {
    /** Commits when [block] returns, rolls back when it throws. */
    fun <T> inTransaction(block: (SqlSession) -> T): T
}

/**
 * [ConfigurationRowsStore] over SQL. Touches only `apps_db` and `TimeLimitEntity`.
 *
 * CLAIM(config-sql-only): no statement names any other table, so history survives a replace [SqlConfigurationRowsStoreTest].
 * CLAIM(rows-atomic): delete and insert commit together or not at all [SqlConfigurationRowsStoreTest].
 *
 * STUB: red phase. No behavior yet.
 */
class SqlConfigurationRowsStore(private val runner: SqlTransactionRunner) : ConfigurationRowsStore {
    override fun read(): ConfigurationRows = ConfigurationRows()
    override fun replace(rows: ConfigurationRows) = Unit
}
