package ly.com.tahaben.farhan_rescue

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import ly.com.tahaben.farhan_rescue.adb.PackageVersion
import ly.com.tahaben.farhan_rescue.capture.CaptureFacts
import ly.com.tahaben.farhan_rescue.capture.Integrity
import ly.com.tahaben.farhan_rescue.convert.*
import ly.com.tahaben.farhan_rescue.input.JobId
import ly.com.tahaben.farhan_rescue.storage.RawJobStore
import ly.com.tahaben.settings_portability.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The whole conversion chain on device with the real shared libraries (LegacySettingsConverter, WidgetProfiles,
 * AndroidSettingsValidator, codec). Not run by the author (no builds allowed).
 */
class RescueConverterInstrumentedTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "rescue-convert-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun writeDataStore(file: File, block: androidx.datastore.preferences.core.MutablePreferences.() -> Unit) = runBlocking {
        val job = SupervisorJob()
        try {
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }).edit { it.block() }
        } finally { job.cancelAndJoin() }
    }

    private fun jobWith(widgetTextSize: Int = 24): Pair<ly.com.tahaben.farhan_rescue.storage.JobDirectory, CaptureFacts> {
        val job = RawJobStore(File(root, "raw")).create(JobId.random())
        val paths = mutableListOf<String>()
        fun put(path: String, write: (File) -> Unit) { job.sink(path).close(); write(File(job.directory, path)); paths += path }
        put("shared_prefs/shared_pref.xml") {
            it.writeText("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<int name=\"notification_flood_threshold\" value=\"7\" />\n" +
                "<string name=\"mystery_key\">top-secret-value</string>\n</map>\n")
        }
        put("datastore/farhan_data_store.preferences_pb") { writeDataStore(it) { this[booleanPreferencesKey("app_main_switch")] = true } }
        put("datastore/appWidget-5.preferences_pb") {
            writeDataStore(it) {
                this[stringPreferencesKey("widget_theme_colors")] = "Vibrant"
                this[intPreferencesKey("text_size")] = widgetTextSize
                this[intPreferencesKey("today_usage_derived")] = 99
            }
        }
        put("databases/farhan_db") {
            SQLiteDatabase.openOrCreateDatabase(it, null).use { db ->
                db.execSQL("CREATE TABLE `apps_db` (`app_name` TEXT NOT NULL, `package_name` TEXT NOT NULL, `activity_name` TEXT NOT NULL, `user_serial` INTEGER NOT NULL, PRIMARY KEY(`app_name`, `package_name`))")
                db.execSQL("CREATE TABLE `TimeLimitEntity` (`packageName` TEXT NOT NULL, `timeLimitInMilli` INTEGER NOT NULL, `timeAtAddingInMilli` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
                db.execSQL("INSERT INTO apps_db VALUES ('Mail', 'com.mail', 'com.mail.Main', 7)")
                db.execSQL("INSERT INTO TimeLimitEntity VALUES ('com.mail', 1099511627776, 5)")
            }
        }
        return job to CaptureFacts(10234, PackageVersion("0.2.6-alpha", 11), paths, false, Integrity.SHA256)
    }

    // why: end to end on real stores. Expected values are hand-written: the exact canonical widget JSON (field order from
    // WidgetProfiles.encode), the exact long, and "unsupported name listed, its value absent". catches: a wired-wrong
    // adapter, a widget profile built from derived usage state, or the unknown value leaking into the file.
    @Test fun convertsCopiedFilesIntoAValidPortableFile() {
        val (job, facts) = jobWith()
        val result = RescueConverter().convert(job, facts, target()) as ConversionResult.Ready
        val settings = (PortableSettingsCodec.decode(result.fileBytes) as DecodeResult.Valid).settings
        assertThat(settings.source).isEqualTo(PortableSource("ly.com.tahaben.farhan_alpha", "0.2.6-alpha", 11, 0))
        assertThat(settings.configuration.sharedPreferences).containsExactly("notification_flood_threshold", SettingValue.Int32(7))
        assertThat(settings.configuration.dataStore).containsExactly(
            "app_main_switch", SettingValue.Bool(true),
            "imported_widget_profiles_v1", SettingValue.Text(
                "[{\"themeColors\":\"Vibrant\",\"uiModeAppearance\":\"FOLLOW_SYSTEM\",\"style\":\"VERTICAL\",\"compareWithYesterday\":false,\"textSize\":24}]"),
        )
        assertThat(settings.configuration.rows.apps).containsExactly(AppActivityRow("Mail", "com.mail", "com.mail.Main", 7L))
        assertThat(settings.configuration.rows.timeLimits.single().timeLimitInMilli).isEqualTo(1_099_511_627_776L)
        assertThat(settings.unsupportedKeys).containsExactly(UnsupportedKey(SettingsStoreId.SHARED_PREFERENCES, "mystery_key"))
        assertThat(String(result.fileBytes)).doesNotContain("top-secret-value")
        assertThat(result.preview.widgetProfileCount).isEqualTo(1)
    }

    // why: malformed widget profiles fail the whole export (brief). text_size 7 is below the 8..48 range.
    @Test fun aMalformedWidgetFailsTheWholeConversion() {
        val (job, facts) = jobWith(widgetTextSize = 7)
        val result = RescueConverter().convert(job, facts, target())
        assertThat(result).isEqualTo(ConversionResult.Failed(ConversionFailure.WIDGET_PROFILE_INVALID))
    }

    // why: a file the bridge names must exist in the job directory. catches: converting with a silently missing copy.
    @Test fun aMissingCopyIsAnError() {
        val (job, facts) = jobWith()
        File(job.directory, "databases/farhan_db").delete()
        assertThat(RescueConverter().convert(job, facts, target())).isEqualTo(ConversionResult.Failed(ConversionFailure.FILES_MISSING))
    }
}
