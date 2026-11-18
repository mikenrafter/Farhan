package ly.com.tahaben.farhan_rescue.convert

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ly.com.tahaben.settings_portability.*
import java.io.File

/** A copied file the helper could not read as the format it should be. Reason only; no value. */
class CopiedStoreException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason { DATASTORE_CORRUPT, DATASTORE_UNSUPPORTED_TYPE, DATABASE_CORRUPT, DATABASE_MISSING_TABLES, DATABASE_UNREADABLE, FILE_TOO_LARGE, NOT_UTF8 }
}

/** Typed [SettingValue] for one stored preference value. Byte arrays and unknown types are refused, never dropped. */
internal fun settingValueOf(raw: Any?): SettingValue = when (raw) {
    is Boolean -> SettingValue.Bool(raw)
    is Int -> SettingValue.Int32(raw)
    is Long -> SettingValue.Int64(raw)
    is Float -> SettingValue.Float32(raw)
    is Double -> SettingValue.Float64(raw)
    is String -> SettingValue.Text(raw)
    is Set<*> -> SettingValue.TextSet(raw.map { it as? String ?: throw CopiedStoreException(CopiedStoreException.Reason.DATASTORE_UNSUPPORTED_TYPE) }.toSet())
    else -> throw CopiedStoreException(CopiedStoreException.Reason.DATASTORE_UNSUPPORTED_TYPE)
}

/**
 * Reads a copied `.preferences_pb` through DataStore's public API (`PreferenceDataStoreFactory`, `data.first()`,
 * `asMap()`); there is no protobuf code here.
 *
 * CLAIM(scope-joined): the DataStore's own coroutine scope is cancelled and joined before this returns, so no
 * background writer is still attached to the copy when the caller deletes it [CopiedStoresInstrumentedTest].
 * The file must end in `.preferences_pb` (DataStore refuses anything else) and is only ever the private copy.
 */
object CopiedDataStoreReader {
    fun read(copy: File): Map<String, SettingValue> {
        require(copy.name.endsWith(".preferences_pb")) { "DataStore copy needs the .preferences_pb extension" }
        if (copy.length() > 8L shl 20) throw CopiedStoreException(CopiedStoreException.Reason.FILE_TOO_LARGE)
        return runBlocking {
            val job = SupervisorJob()
            val scope = CoroutineScope(Dispatchers.IO + job)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { copy })
                val preferences = try {
                    store.data.first()
                } catch (failure: androidx.datastore.core.CorruptionException) {
                    throw CopiedStoreException(CopiedStoreException.Reason.DATASTORE_CORRUPT)
                } catch (failure: java.io.IOException) {
                    throw CopiedStoreException(CopiedStoreException.Reason.DATASTORE_CORRUPT)
                }
                preferences.asMap().entries.associate { it.key.name to settingValueOf(it.value) }
            } finally {
                job.cancelAndJoin()
            }
        }
    }
}

/**
 * Reads the two configuration tables from a private copy of the Room database, through the shared
 * [SqlConfigurationRowsStore] (the same SQL the importer uses), never touching the source.
 *
 * CLAIM(copy-only-rw): the copy is opened read-write because SQLite needs write access to replay a `-wal` or roll
 * back a hot `-journal` [SQLite docs: WAL recovery and hot journals]; only the copy is ever opened.
 * CLAIM(config-tables-only): the two SELECTs are `apps_db` and `TimeLimitEntity`; no history table is named.
 * CLAIM(db-strict): a database that exists but is corrupt or lacks a table is an error, not an empty result.
 */
object CopiedDatabaseReader {
    fun read(database: File): ConfigurationRows {
        val db = try {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        } catch (failure: android.database.SQLException) {
            throw CopiedStoreException(CopiedStoreException.Reason.DATABASE_UNREADABLE)
        }
        try {
            // CLAIM(quick-check-fail-closed): exactly one row reading "ok" passes; no row, extra rows or any other text is corrupt.
            val verdict = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            if (verdict != listOf("ok")) throw CopiedStoreException(CopiedStoreException.Reason.DATABASE_CORRUPT)
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('apps_db', 'TimeLimitEntity')", null)
                .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            if (tables != setOf("apps_db", "TimeLimitEntity")) throw CopiedStoreException(CopiedStoreException.Reason.DATABASE_MISSING_TABLES)
            return SqlConfigurationRowsStore(FrameworkTransactions(db)).read()
        } catch (failure: android.database.SQLException) {
            throw CopiedStoreException(CopiedStoreException.Reason.DATABASE_CORRUPT)
        } catch (failure: IllegalStateException) {
            // The store refuses a column that is not the expected text or integer type.
            throw CopiedStoreException(CopiedStoreException.Reason.DATABASE_CORRUPT)
        } finally {
            db.close()
        }
    }
}

private class FrameworkTransactions(private val database: SQLiteDatabase) : SqlTransactionRunner {
    override fun <T> inTransaction(block: (SqlSession) -> T): T {
        database.beginTransaction()
        try {
            val result = block(object : SqlSession {
                override fun query(sql: String, args: List<Any?>): List<List<Any?>> =
                    database.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                                when (cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> null
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                    android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                    else -> throw IllegalStateException("unexpected column type")
                                }
                            })
                        }
                    }

                // The helper never writes to even its own copy.
                override fun execute(sql: String, args: List<Any?>) = throw UnsupportedOperationException("read-only session")
            })
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }
}
