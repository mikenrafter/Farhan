package ly.com.tahaben.farhan.settings

import ly.com.tahaben.settings_android.AndroidSettingsValidator
import ly.com.tahaben.settings_android.WidgetProfiles
import ly.com.tahaben.settings_android.WidgetProfile
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import ly.com.tahaben.usage_overview_presentation.widget.UsageWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import ly.com.tahaben.farhan.BuildConfig
import ly.com.tahaben.farhan.db.FarhanDatabase
import ly.com.tahaben.settings_portability.*

class RecoveryRequiredException : IllegalStateException("Settings recovery must finish before opening stores")

/** The startup importer and Hilt share these exact instances. Public reads default to denied. */
object ProcessStores {
    private lateinit var context: Context
    @Volatile private var normalOperationAllowed = false
    @Volatile var startupResult: StartupResult? = null
        private set
    private val rawSharedPreferences by lazy { context.getSharedPreferences("shared_pref", Context.MODE_PRIVATE) }
    private val rawDataStore by lazy { PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("farhan_data_store") }) }
    private val rawDatabase by lazy { Room.databaseBuilder(context, FarhanDatabase::class.java, "farhan_db").allowMainThreadQueries().build() }
    private val journal by lazy { AndroidImportJournal(context.noBackupFilesDir) }
    private val sharedPort = AndroidSharedPreferences { rawSharedPreferences }
    private val dataPort = AndroidDataStore { rawDataStore }
    private val rowsPort = SqlConfigurationRowsStore(AndroidSqlTransactions { rawDatabase.openHelper.writableDatabase })
    private fun importer(storage: ImportJournalStorage = journal) = ImportCoordinator(storage, sharedPort, dataPort, rowsPort, AndroidSettingsValidator)
    private fun requireReady() { if (!normalOperationAllowed) throw RecoveryRequiredException() }
    fun sharedPreferences(): SharedPreferences { requireReady(); return rawSharedPreferences }
    fun dataStore(): DataStore<Preferences> { requireReady(); return rawDataStore }
    fun database(): FarhanDatabase { requireReady(); return rawDatabase }

    internal fun startup(application: Context): StartupResult {
        normalOperationAllowed = false
        context = application.applicationContext
        return importer().startup().also { startupResult = it }
    }
    internal fun allowNormalOperation() { normalOperationAllowed = true }
    // Retry never opens the public gate: this process did not initialize Hilt or WorkManager.
    internal fun retry(): StartupResult = importer().startup().also { startupResult = it }
    internal fun outcome(): ImportJournal? { requireReady(); return journal.read() }
    internal fun acknowledge() { requireReady(); importer().acknowledge() }
    internal fun export(): PortableSettingsResult {
        requireReady()
        val current = dataPort.readAll()
        val imported = current[WidgetProfiles.KEY]?.let { WidgetProfiles.decode((it as SettingValue.Text).value) }.orEmpty()
        val installed = runBlocking(Dispatchers.IO) {
            val states = GlanceAppWidgetManager(context).getGlanceIds(UsageWidget::class.java).map { id ->
                val preferences: Preferences = UsageWidget.getAppWidgetState(context, id)
                widgetUserValues(preferences)
            }
            WidgetProfiles.fromLegacy(states)
        }
        val snapshot = current + (WidgetProfiles.KEY to SettingValue.Text(WidgetProfiles.encode(WidgetProfiles.merge(imported, installed))))
        val exportData = object : PreferencesStore {
            override fun readAll() = snapshot
            override fun apply(removeKeys: Set<String>, put: Map<String, SettingValue>): Unit = error("Export snapshot is read only")
        }
        return SettingsExporter.export(PortableSource(context.packageName, BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toLong(), android.os.Process.myUid() / 100_000), sharedPort, exportData, rowsPort, AndroidSettingsValidator)
    }
    internal fun importedWidgetProfiles(): List<WidgetProfile> {
        requireReady()
        return dataPort.readAll()[WidgetProfiles.KEY]?.let { WidgetProfiles.decode((it as SettingValue.Text).value) }.orEmpty()
    }
    internal fun preview(bytes: ByteArray): StageResult {
        requireReady()
        val memory = object : ImportJournalStorage {
            var record: ImportJournal? = null
            override fun read() = record
            override fun write(journal: ImportJournal) { record = journal }
            override fun clear() { record = null }
        }
        return importer(memory).stage(bytes)
    }
    internal fun stage(bytes: ByteArray): StageResult {
        requireReady()
        // Open/migrate the real Room schema while the normal process is healthy, before any durable draft.
        rawDatabase.openHelper.writableDatabase
        return importer().stage(bytes)
    }
}
