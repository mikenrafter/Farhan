package ly.com.tahaben.farhan.settings

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import ly.com.tahaben.settings_portability.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID

/** Run only on a disposable install. The precondition refuses existing Farhan settings before any mutation. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SettingsRecoveryIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.packageManager
    private val marker get() = RecoveryActivity::class.java.name

    @Before fun requireDisposableInstallation() {
        assertEquals("ly.com.tahaben.farhan_alpha", context.packageName)
        val data = File(context.applicationInfo.dataDir)
        for (file in listOf(File(data, "shared_prefs/shared_pref.xml"),
            context.getDatabasePath("farhan_db"),
            File(context.filesDir, "datastore/farhan_data_store.preferences_pb"),
            File(context.noBackupFilesDir, "settings_import_v1"))) {
            assertFalse("Existing settings found; use a fresh disposable test install: $file", file.exists())
        }
    }

    @Suppress("DEPRECATION")
    private fun components(): List<ComponentInfo> {
        val info = manager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.GET_DISABLED_COMPONENTS)
        return info.activities.orEmpty().toList() + info.services.orEmpty().toList() +
            info.receivers.orEmpty().toList() + info.providers.orEmpty().toList()
    }
    private fun raw(name: String) = manager.getComponentEnabledSetting(ComponentName(context.packageName, name))
    private fun set(name: String, state: Int) = manager.setComponentEnabledSetting(
        ComponentName(context.packageName, name), state, PackageManager.DONT_KILL_APP)

    // WHY: CLAIM(component-heal-exact) must hold against Android enumeration and raw overrides, not just a fake.
    // catches: omitting disabled components, treating DEFAULT as always enabled, or failing to restore raw states.
    @Test fun a_realComponentAdapterRestoresEveryNormalOverride() {
        val original = components().associate { it.name to raw(it.name) }
        val directory = File(context.cacheDir, "component-adapter-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val enabledDefault = "ly.com.tahaben.farhan.CrashDetailsActivity"
            val explicitEnabled = SettingsBackupActivity::class.java.name
            val explicitDisabled = "ly.com.tahaben.farhan.MainActivity"
            val disabledDefault = "androidx.work.impl.background.systemalarm.ConstraintProxy\$BatteryChargingProxy"
            for (name in listOf(enabledDefault, explicitEnabled, explicitDisabled, disabledDefault))
                assertTrue("Required manifest component missing: $name", name in original)
            set(enabledDefault, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            set(explicitEnabled, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            set(explicitDisabled, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            set(disabledDefault, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val listed = components().associateBy { it.name }
            assertTrue(listed.getValue(enabledDefault).enabled)
            assertFalse(listed.getValue(disabledDefault).enabled)
            val before = listed.keys.filter { it != marker }.associateWith(::raw)

            assertTrue(RecoveryComponents(isolated).block())
            assertEquals(listed.keys, components().map { it.name }.toSet())
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, raw(marker))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, raw(enabledDefault))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, raw(explicitEnabled))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, raw(explicitDisabled))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, raw(disabledDefault))
            for ((name, beforeState) in before) {
                val expected = if (beforeState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    beforeState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && listed.getValue(name).enabled)
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED else beforeState
                assertEquals("Blocked override for $name", expected, raw(name))
            }
            assertTrue(File(directory, "component_states_v1").exists())

            RecoveryComponents(isolated).heal()
            assertEquals(before, before.keys.associateWith(::raw))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, raw(marker))
            assertFalse(File(directory, "component_states_v1").exists())
            assertFalse(File(directory, "component_states_v1.bak").exists())
        } finally {
            // The marker deliberately heals to DISABLED; teardown restores its original raw override too.
            original.forEach { (name, state) -> set(name, state) }
            directory.deleteRecursively()
        }
    }

    // WHY: HiltTestRunner replaces FarhanApplication, so call the actual Android startup entry explicitly.
    // catches: staging without durable replay, a gate never opened by startup, or an unwired real store adapter.
    @Test fun b_stagedImportRunsThroughAndroidStartupAndAllThreeStores() {
        try { ProcessStores.sharedPreferences(); fail("Public stores opened before startup") }
        catch (_: RecoveryRequiredException) { }
        assertTrue(SettingsStartup.run(context))
        assertEquals(StartupResult.NoImport, ProcessStores.startupResult)
        val shared = ProcessStores.sharedPreferences()
        assertTrue(shared.edit().putInt("notification_flood_threshold", 7)
            .putInt("test_internal_counter", 42)
            .putString("notification_automation_credential_v1", "destination-test-token")
            .putBoolean("notification_automation_enabled_v1", true).commit())
        val data = AndroidDataStore { ProcessStores.dataStore() }
        data.apply(emptySet(), mapOf("app_main_switch" to SettingValue.Bool(false)))
        val rows = SqlConfigurationRowsStore(AndroidSqlTransactions { ProcessStores.database().openHelper.writableDatabase })
        rows.replace(ConfigurationRows(timeLimits = listOf(TimeLimitRow("old.fixture", 1_000, 0))))
        val desired = SettingsConfiguration(
            sharedPreferences = mapOf("notification_flood_threshold" to SettingValue.Int32(17)),
            dataStore = mapOf("app_main_switch" to SettingValue.Bool(true)),
            rows = ConfigurationRows(apps = listOf(AppActivityRow("Fixture", "test.fixture", "test.fixture.Main", 0)),
                timeLimits = listOf(TimeLimitRow("test.fixture", 60_000, 0))))
        val source = PortableSource("test.fixture", "1", 1, 0)
        val preview = ImportPreview(source, 1, 1, 1, 1, emptyList())
        val bytes = PortableSettingsCodec.encode(PortableSettings(source, desired))
        assertEquals(StageResult.Staged(preview), ProcessStores.stage(bytes))
        assertEquals(ImportJournal.Staged(desired, preview), AndroidImportJournal(context.noBackupFilesDir).read())
        assertEquals(7, shared.getInt("notification_flood_threshold", -1))
        assertEquals(SettingValue.Bool(false), data.readAll()["app_main_switch"])
        assertEquals(listOf(TimeLimitRow("old.fixture", 1_000, 0)), rows.read().timeLimits)

        assertTrue(SettingsStartup.run(context))
        assertEquals(StartupResult.Applied(preview), ProcessStores.startupResult)
        assertEquals(17, ProcessStores.sharedPreferences().getInt("notification_flood_threshold", -1))
        assertEquals(42, shared.getInt("test_internal_counter", -1))
        assertFalse(shared.contains("notification_automation_credential_v1"))
        assertFalse(shared.getBoolean("notification_automation_enabled_v1", true))
        assertEquals(SettingValue.Bool(true), data.readAll()["app_main_switch"])
        assertEquals(desired.rows, rows.read())
        assertEquals(ImportJournal.Applied(preview), ProcessStores.outcome())
        ProcessStores.acknowledge()
        assertNull(ProcessStores.outcome())
        assertTrue(SettingsStartup.run(context))
        assertEquals(StartupResult.NoImport, ProcessStores.startupResult)
        assertEquals(17, shared.getInt("notification_flood_threshold", -1))
    }
}
