package ly.com.tahaben.farhan_rescue

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.farhan_rescue.convert.CopiedDataStoreReader
import ly.com.tahaben.farhan_rescue.convert.CopiedDatabaseReader
import ly.com.tahaben.farhan_rescue.convert.CopiedStoreException
import ly.com.tahaben.settings_portability.*
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

/** Instrumented because DataStore and SQLite run on the device. Not run by the author (no builds allowed). */
class CopiedStoresInstrumentedTest {
    private lateinit var directory: File

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "rescue-test-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    // why: decoding real DataStore bytes through the public API. The fixture is a hand-assembled protobuf from
    // preferences_pb.proto (PreferenceMap.preferences = 1; Value: boolean=1, long=4, string=5), NOT written by DataStore:
    //   entry "a" = true:      0a 07 | 0a 01 61 | 12 02 08 01
    //   entry "l" = 2^40:      0a 0c | 0a 01 6c | 12 07 20 80 80 80 80 80 20   (varint 2^40 = five 0x80 groups then 0x20)
    //   entry "s" = "hi":      0a 09 | 0a 01 73 | 12 04 2a 02 68 69
    // catches: a reader that reorders types, widens the long, or needs the file name to differ.
    @Test fun decodesALiteralPreferencesFile() {
        val bytes = hex("0a070a016112020801" + "0a0c0a016c120720808080808020" + "0a090a01731204" + "2a026869")
        val file = File(directory, "farhan_data_store.preferences_pb").also { it.writeBytes(bytes) }
        assertThat(CopiedDataStoreReader.read(file)).containsExactly(
            "a", SettingValue.Bool(true), "l", SettingValue.Int64(1_099_511_627_776L), "s", SettingValue.Text("hi"),
        )
    }

    // why: a damaged DataStore is an error, never "no settings"; and the extension rule is enforced.
    @Test fun damagedDataStoreIsRefused() {
        val damaged = File(directory, "x.preferences_pb").also { it.writeBytes(byteArrayOf(-1, -1, -1)) }
        assertThat(assertThrows(CopiedStoreException::class.java) { CopiedDataStoreReader.read(damaged) }.reason)
            .isEqualTo(CopiedStoreException.Reason.DATASTORE_CORRUPT)
        assertThrows(IllegalArgumentException::class.java) { CopiedDataStoreReader.read(File(directory, "wrong.pb").also { it.writeBytes(ByteArray(0)) }) }
    }

    private val createConfig = listOf(
        "CREATE TABLE IF NOT EXISTS `apps_db` (`app_name` TEXT NOT NULL, `package_name` TEXT NOT NULL, `activity_name` TEXT NOT NULL, `user_serial` INTEGER NOT NULL, PRIMARY KEY(`app_name`, `package_name`))",
        "CREATE TABLE IF NOT EXISTS `TimeLimitEntity` (`packageName` TEXT NOT NULL, `timeLimitInMilli` INTEGER NOT NULL, `timeAtAddingInMilli` INTEGER NOT NULL, PRIMARY KEY(`packageName`))",
        "CREATE TABLE IF NOT EXISTS `NotificationItemEntity` (`id` TEXT NOT NULL, `text` TEXT, PRIMARY KEY(`id`))",
    )

    private fun makeDatabase(file: File, wal: Boolean = false): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        if (wal) { db.enableWriteAheadLogging(); db.rawQuery("PRAGMA wal_autocheckpoint=0", null).close() }
        createConfig.forEach(db::execSQL)
        db.execSQL("INSERT INTO apps_db VALUES ('Mail', 'com.mail', 'com.mail.Main', 7)")
        db.execSQL("INSERT INTO TimeLimitEntity VALUES ('com.mail', 1099511627776, 5)")
        db.execSQL("INSERT INTO NotificationItemEntity VALUES ('n1', 'private message text')")
        return db
    }

    // why: CLAIM(config-tables-only) with a positive control: the history row exists in the copy and is not in the result.
    @Test fun readsOnlyTheConfigurationRows() {
        val file = File(directory, "farhan_db")
        makeDatabase(file).close()
        val rows = CopiedDatabaseReader.read(file)
        assertThat(rows.apps).containsExactly(AppActivityRow("Mail", "com.mail", "com.mail.Main", 7L))
        assertThat(rows.timeLimits).containsExactly(TimeLimitRow("com.mail", 1_099_511_627_776L, 5L))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM NotificationItemEntity", null).use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1) }
        }
    }

    // why: the copy of a database with a live -wal must show the WAL rows (opened read-write on the copy so SQLite can
    // recover). Control: the main file alone does not have them. catches: copying only the main file, or opening read-only.
    @Test fun walRowsAreRecoveredFromTheCopy() {
        val source = File(directory, "source/farhan_db").also { it.parentFile!!.mkdirs() }
        val db = makeDatabase(source, wal = true)
        try {
            val wal = File(source.path + "-wal")
            assertThat(wal.length()).isGreaterThan(0L) // precondition: rows are still in the WAL
            val withWal = File(directory, "withWal/farhan_db").also { it.parentFile!!.mkdirs() }
            source.copyTo(withWal); wal.copyTo(File(withWal.path + "-wal"))
            val mainOnly = File(directory, "mainOnly/farhan_db").also { it.parentFile!!.mkdirs() }
            source.copyTo(mainOnly)
            assertThat(CopiedDatabaseReader.read(withWal).apps).hasSize(1)
            assertThat(runCatching { CopiedDatabaseReader.read(mainOnly).apps.size }.getOrNull() ?: 0).isEqualTo(0)
        } finally { db.close() }
    }

    // why: CLAIM(db-strict). A database lacking the tables, and a file that is not a database, are errors, not empty results.
    @Test fun missingTablesAndGarbageAreErrors() {
        val bare = File(directory, "bare_db")
        SQLiteDatabase.openOrCreateDatabase(bare, null).also { it.execSQL("CREATE TABLE other (x INTEGER)") }.close()
        assertThat(assertThrows(CopiedStoreException::class.java) { CopiedDatabaseReader.read(bare) }.reason)
            .isEqualTo(CopiedStoreException.Reason.DATABASE_MISSING_TABLES)
        val garbage = File(directory, "garbage_db").also { it.writeBytes(ByteArray(4096) { 0x41 }) }
        assertThat(assertThrows(CopiedStoreException::class.java) { CopiedDatabaseReader.read(garbage) }.reason)
            .isAnyOf(CopiedStoreException.Reason.DATABASE_CORRUPT, CopiedStoreException.Reason.DATABASE_UNREADABLE)
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
