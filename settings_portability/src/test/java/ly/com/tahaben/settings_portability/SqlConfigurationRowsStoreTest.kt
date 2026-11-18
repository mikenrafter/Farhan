package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * [SqlConfigurationRowsStore] against a real SQLite engine, with the table definitions copied from Room's exported
 * schema (app/schemas/ly.com.tahaben.farhan.db.FarhanDatabase/7.json). A recording fake would only mirror the SQL
 * text; here a statement that touches history actually deletes history.
 *
 * History rows hold synthetic text only.
 */
class SqlConfigurationRowsStoreTest {

    private val schema = listOf(
        "CREATE TABLE IF NOT EXISTS `apps_db` (`app_name` TEXT NOT NULL, `package_name` TEXT NOT NULL, `activity_name` TEXT NOT NULL, `user_serial` INTEGER NOT NULL, PRIMARY KEY(`app_name`, `package_name`))",
        "CREATE TABLE IF NOT EXISTS `TimeLimitEntity` (`packageName` TEXT NOT NULL, `timeLimitInMilli` INTEGER NOT NULL, `timeAtAddingInMilli` INTEGER NOT NULL, PRIMARY KEY(`packageName`))",
        "CREATE TABLE IF NOT EXISTS `NotificationItemEntity` (`id` TEXT NOT NULL, `appName` TEXT, `title` TEXT, `text` TEXT, `time` TEXT NOT NULL, `creatorPackage` TEXT NOT NULL, `timestamp` INTEGER NOT NULL DEFAULT 0, `priority` INTEGER NOT NULL DEFAULT 3, `category` TEXT NOT NULL DEFAULT 'OTHER', `isRead` INTEGER NOT NULL DEFAULT 0, `channelId` TEXT DEFAULT NULL, `isCleared` INTEGER NOT NULL DEFAULT 0, `isDelivered` INTEGER NOT NULL DEFAULT 0, `priorityReason` TEXT DEFAULT NULL, `sourceSession` TEXT NOT NULL DEFAULT '', `sourceEventOrder` INTEGER NOT NULL DEFAULT 0, `deliveryInvalidated` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `LaunchAttemptEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `UsageDataItemEntity` (`app_name` TEXT NOT NULL, `package_name` TEXT NOT NULL, `usage_timestamp` INTEGER NOT NULL, `usage_type` TEXT NOT NULL, `app_category` TEXT, PRIMARY KEY(`usage_timestamp`))",
        "CREATE TABLE IF NOT EXISTS `DayLastUpdatedEntity` (`day` TEXT NOT NULL, `last_update_time` INTEGER NOT NULL, PRIMARY KEY(`day`))",
    )
    private val historyTables = listOf(
        "NotificationItemEntity", "LaunchAttemptEntity", "UsageDataItemEntity", "DayLastUpdatedEntity",
    )

    private val oldRows = ConfigurationRows(
        apps = listOf(AppActivityRow("Old", "com.old.app", "com.old.app.Main", 0L)),
        timeLimits = listOf(TimeLimitRow("com.old.app", 1_000L, 2_000L)),
    )

    private class JdbcSession(private val connection: Connection) : SqlSession {
        override fun query(sql: String, args: List<Any?>): List<List<Any?>> =
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { results ->
                    val width = results.metaData.columnCount
                    buildList {
                        while (results.next()) {
                            // Contract: Long, String or null. The driver may hand back Integer for small numbers.
                            add((1..width).map { column ->
                                when (val value = results.getObject(column)) {
                                    is Number -> value.toLong()
                                    else -> value
                                }
                            })
                        }
                    }
                }
            }

        override fun execute(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private class JdbcRunner(private val connection: Connection) : SqlTransactionRunner {
        override fun <T> inTransaction(block: (SqlSession) -> T): T {
            connection.autoCommit = false
            try {
                val value = block(JdbcSession(connection))
                connection.commit()
                return value
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun withDatabase(test: (Connection, SqlConfigurationRowsStore) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                schema.forEach { statement.execute(it) }
                statement.execute("INSERT INTO apps_db VALUES ('Old','com.old.app','com.old.app.Main',0)")
                statement.execute("INSERT INTO TimeLimitEntity VALUES ('com.old.app',1000,2000)")
                statement.execute("INSERT INTO NotificationItemEntity (id, appName, title, text, time, creatorPackage) VALUES ('n1','App','SYNTHETIC-TITLE','SYNTHETIC-TEXT','12:00','com.history.app')")
                statement.execute("INSERT INTO LaunchAttemptEntity (packageName, timestamp) VALUES ('com.history.app', 1700000000000)")
                statement.execute("INSERT INTO UsageDataItemEntity VALUES ('U','com.history.app',1700000000001,'SYNTHETIC',NULL)")
                statement.execute("INSERT INTO DayLastUpdatedEntity VALUES ('2026-11-17',1700000000002)")
            }
            test(connection, SqlConfigurationRowsStore(JdbcRunner(connection)))
        }
    }

    private fun Connection.dump(table: String): List<List<Any?>> = JdbcSession(this).query("SELECT * FROM `$table` ORDER BY 1")

    private fun Connection.historyDump(): Map<String, List<List<Any?>>> = historyTables.associateWith { dump(it) }

    // why: the owner's history must survive a settings restore, and the restore must set exactly the rows given,
    //      including values that overflow an int (user serial, epoch milliseconds).
    // proves: CLAIM(config-sql-only)
    // catches: DELETE without a table filter or a "clear all tables" helper; a read that returns the wrong column
    //          order; int truncation of epoch milliseconds; rows left from before the replace; unsorted reads.
    //          The raw SELECTs below do not go through the store under test.
    @Test
    fun `replace sets exactly the given configuration rows and leaves every history table untouched`() = withDatabase { connection, store ->
        val historyBefore = connection.historyDump()
        // positive control: the probe sees real history in all four tables
        assertThat(historyBefore.mapValues { it.value.size }).containsExactly(
            "NotificationItemEntity", 1, "LaunchAttemptEntity", 1, "UsageDataItemEntity", 1, "DayLastUpdatedEntity", 1,
        )
        assertThat(store.read()).isEqualTo(oldRows)

        val replacement = ConfigurationRows(
            apps = listOf(
                AppActivityRow("Zed", "com.example.zed", "com.example.zed.Main", 4_000_000_000L),
                AppActivityRow("Alpha", "com.example.alpha", "com.example.alpha.Main", 10L),
            ),
            timeLimits = listOf(
                TimeLimitRow("com.example.zed", 7_200_000L, 1_700_000_000_000L),
                TimeLimitRow("com.example.alpha", 60_000L, 1_700_000_000_001L),
            ),
        )

        store.replace(replacement)
        store.replace(replacement) // idempotent

        assertThat(connection.dump("apps_db")).containsExactly(
            listOf("Alpha", "com.example.alpha", "com.example.alpha.Main", 10L),
            listOf("Zed", "com.example.zed", "com.example.zed.Main", 4_000_000_000L),
        ).inOrder()
        assertThat(connection.dump("TimeLimitEntity")).containsExactly(
            listOf("com.example.alpha", 60_000L, 1_700_000_000_001L),
            listOf("com.example.zed", 7_200_000L, 1_700_000_000_000L),
        ).inOrder()
        assertThat(store.read()).isEqualTo(
            ConfigurationRows(
                apps = listOf(replacement.apps[1], replacement.apps[0]),
                timeLimits = listOf(replacement.timeLimits[1], replacement.timeLimits[0]),
            ),
        )
        assertThat(connection.historyDump()).isEqualTo(historyBefore)
    }

    // why: a restore that dies halfway through the rows must leave the old rows, not an empty or half table.
    // proves: CLAIM(rows-atomic)
    // catches: autocommit per statement; deleting outside the transaction; inserting apps and time limits in
    //          separate transactions. The second app repeats the first one's primary key, so SQLite itself fails.
    @Test
    fun `a failed replace leaves the previous rows and history in place`() = withDatabase { connection, store ->
        val historyBefore = connection.historyDump()
        val failing = ConfigurationRows(
            apps = listOf(
                AppActivityRow("Same", "com.example.same", "com.example.same.A", 0L),
                AppActivityRow("Same", "com.example.same", "com.example.same.B", 0L), // primary key repeats
            ),
            timeLimits = listOf(TimeLimitRow("com.example.same", 1L, 2L)),
        )

        assertThrows(Exception::class.java) { store.replace(failing) }

        assertThat(store.read()).isEqualTo(oldRows)
        assertThat(connection.historyDump()).isEqualTo(historyBefore)
    }
}
