package ly.com.tahaben.farhan.settings

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ly.com.tahaben.settings_portability.*

internal class UnsupportedSettingsStorageType : IllegalStateException("UNSUPPORTED_STORAGE_TYPE")

internal fun settingValue(raw: Any?): SettingValue = when (raw) {
    is Boolean -> SettingValue.Bool(raw)
    is Int -> SettingValue.Int32(raw)
    is Long -> SettingValue.Int64(raw)
    is Float -> SettingValue.Float32(raw)
    is Double -> SettingValue.Float64(raw)
    is String -> SettingValue.Text(raw)
    is Set<*> -> SettingValue.TextSet(raw.map { it as String }.toSet())
    else -> throw UnsupportedSettingsStorageType()
}

internal class AndroidSharedPreferences(private val open: () -> SharedPreferences) : PreferencesStore {
    override fun readAll() = open().all.mapValues { settingValue(it.value) }
    override fun apply(removeKeys: Set<String>, put: Map<String, SettingValue>) {
        val editor = open().edit()
        removeKeys.forEach(editor::remove)
        put.forEach { (key, value) ->
            when (value) {
                is SettingValue.Bool -> editor.putBoolean(key, value.value)
                is SettingValue.Int32 -> editor.putInt(key, value.value)
                is SettingValue.Int64 -> editor.putLong(key, value.value)
                is SettingValue.Float32 -> editor.putFloat(key, value.value)
                is SettingValue.Text -> editor.putString(key, value.value)
                is SettingValue.TextSet -> editor.putStringSet(key, value.values)
                is SettingValue.Float64 -> error("SharedPreferences cannot store double")
            }
        }
        check(editor.commit()) { "Settings commit failed" }
    }
}

internal class AndroidDataStore(private val open: () -> DataStore<Preferences>) : PreferencesStore {
    override fun readAll(): Map<String, SettingValue> = runBlocking(Dispatchers.IO) {
        open().data.first().asMap().entries.associate { it.key.name to settingValue(it.value) }
    }
    override fun apply(removeKeys: Set<String>, put: Map<String, SettingValue>) {
        runBlocking(Dispatchers.IO) {
            open().edit { preferences ->
                preferences.asMap().keys.filter { it.name in removeKeys || it.name in put }.forEach { preferences.remove(it) }
                put.forEach { (key, value) ->
                    when (value) {
                        is SettingValue.Bool -> preferences[booleanPreferencesKey(key)] = value.value
                        is SettingValue.Int32 -> preferences[intPreferencesKey(key)] = value.value
                        is SettingValue.Int64 -> preferences[longPreferencesKey(key)] = value.value
                        is SettingValue.Float32 -> preferences[floatPreferencesKey(key)] = value.value
                        is SettingValue.Float64 -> preferences[doublePreferencesKey(key)] = value.value
                        is SettingValue.Text -> preferences[stringPreferencesKey(key)] = value.value
                        is SettingValue.TextSet -> preferences[stringSetPreferencesKey(key)] = value.values
                    }
                }
            }
        }
    }
}

internal class AndroidSqlTransactions(private val open: () -> SupportSQLiteDatabase) : SqlTransactionRunner {
    override fun <T> inTransaction(block: (SqlSession) -> T): T {
        val database = open()
        database.beginTransaction()
        try {
            val result = block(object : SqlSession {
                override fun query(sql: String, args: List<Any?>): List<List<Any?>> = database.query(sql, args.toTypedArray()).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                            when (cursor.getType(index)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                else -> error("Unexpected configuration column type")
                            }
                        })
                    }
                }
                override fun execute(sql: String, args: List<Any?>) = database.execSQL(sql, args.toTypedArray())
            })
            database.setTransactionSuccessful()
            return result
        } finally { database.endTransaction() }
    }
}

/** Derived Glance state is excluded before adapting any value. */
internal fun widgetUserValues(preferences: Preferences): Map<String, SettingValue> = preferences.asMap().entries
    .filter { it.key.name in ly.com.tahaben.settings_android.WidgetProfiles.USER_KEYS }
    .associate { it.key.name to settingValue(it.value) }
