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
 * Reads are ordered by primary key so the result does not depend on rowid order. A column that is not the
 * expected Long or String throws [IllegalStateException]: the store never coerces or defaults a value.
 */
class SqlConfigurationRowsStore(private val runner: SqlTransactionRunner) : ConfigurationRowsStore {
    override fun read(): ConfigurationRows = runner.inTransaction { session ->
        ConfigurationRows(
            apps = session.query(
                "SELECT `app_name`, `package_name`, `activity_name`, `user_serial` FROM `apps_db` ORDER BY `app_name`, `package_name`",
            ).map { AppActivityRow(text(it, 0), text(it, 1), text(it, 2), long(it, 3)) },
            timeLimits = session.query(
                "SELECT `packageName`, `timeLimitInMilli`, `timeAtAddingInMilli` FROM `TimeLimitEntity` ORDER BY `packageName`",
            ).map { TimeLimitRow(text(it, 0), long(it, 1), long(it, 2)) },
        )
    }

    override fun replace(rows: ConfigurationRows) = runner.inTransaction { session ->
        session.execute("DELETE FROM `apps_db`")
        session.execute("DELETE FROM `TimeLimitEntity`")
        for (row in rows.apps) {
            session.execute(
                "INSERT INTO `apps_db` (`app_name`, `package_name`, `activity_name`, `user_serial`) VALUES (?, ?, ?, ?)",
                listOf(row.appName, row.packageName, row.activityName, row.userSerial),
            )
        }
        for (row in rows.timeLimits) {
            session.execute(
                "INSERT INTO `TimeLimitEntity` (`packageName`, `timeLimitInMilli`, `timeAtAddingInMilli`) VALUES (?, ?, ?)",
                listOf(row.packageName, row.timeLimitInMilli, row.timeAtAddingInMilli),
            )
        }
    }

    private fun text(row: List<Any?>, column: Int): String =
        row[column] as? String ?: throw IllegalStateException("configuration column $column is not text")

    private fun long(row: List<Any?>, column: Int): Long =
        row[column] as? Long ?: throw IllegalStateException("configuration column $column is not an integer")
}
