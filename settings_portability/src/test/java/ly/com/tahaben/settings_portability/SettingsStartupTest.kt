package ly.com.tahaben.settings_portability

import org.junit.Assert.*
import org.junit.Test

class SettingsStartupTest {
    private class Stores(val gate: SettingsStoreGate = SettingsStoreGate()) {
        val opens = IntArray(3)
        val shared = lazy { opens[0]++; "shared" }
        val data = lazy { opens[1]++; "data" }
        val rows = lazy { opens[2]++; "rows" }
        fun assertDenied() {
            for (store in listOf(shared, data, rows)) assertThrows(RecoveryRequiredException::class.java) { gate.access(store) }
            assertArrayEquals(intArrayOf(0, 0, 0), opens)
        }
    }
    private class Control : RecoveryControl {
        var healAction: () -> Unit = {}
        var blockAction: () -> Boolean = { false }
        var heals = 0
        var blocks = 0
        override fun heal() { heals++; healAction() }
        override fun block(): Boolean { blocks++; return blockAction() }
    }
    private val blocked = StartupResult.RecoveryRequired(ImportFailure(FailureReason.JOURNAL_WRITE_FAILED))

    // WHY: denied getters must reject before evaluating each real lazy factory, including before startup was checked.
    // catches: opening a lazy store before checking the gate or giving any getter a bypass.
    @Test fun closedGateDeniesEveryStoreWithoutOpeningFactories() {
        val stores = Stores()
        stores.assertDenied()
        stores.gate.open()
        assertEquals("shared", stores.gate.access(stores.shared))
        assertEquals("shared", stores.gate.access(stores.shared))
        assertEquals("data", stores.gate.access(stores.data))
        assertEquals("rows", stores.gate.access(stores.rows))
        assertArrayEquals(intArrayOf(1, 1, 1), stores.opens)
        stores.gate.close()
        assertThrows(RecoveryRequiredException::class.java) { stores.gate.access(stores.shared) }
    }

    // WHY: CLAIM(startup-heal-before-open); no journal needs neither recovery store access nor normal-store factories.
    // catches: opening the gate before heal, omitting gate.close, or eagerly opening stores on NoImport.
    @Test fun healRunsWithEveryStoreStillDenied() {
        val stores = Stores()
        stores.gate.open() // A repeated attempt must close it before invoking either port.
        val control = Control().apply { healAction = { stores.assertDenied() } }
        var imports = 0
        var kills = 0
        fun preferences(store: Lazy<String>) = object : PreferencesStore {
            override fun readAll(): Map<String, SettingValue> { store.value; return emptyMap() }
            override fun apply(removeKeys: Set<String>, put: Map<String, SettingValue>) { store.value }
        }
        val journal = object : ImportJournalStorage {
            override fun read(): ImportJournal? = null
            override fun write(journal: ImportJournal) { fail("Absent journal must not be written") }
            override fun clear() { fail("Absent journal must not be cleared") }
        }
        val importer = ImportCoordinator(journal, preferences(stores.shared), preferences(stores.data),
            object : ConfigurationRowsStore {
                override fun read(): ConfigurationRows { stores.rows.value; return ConfigurationRows() }
                override fun replace(rows: ConfigurationRows) { stores.rows.value }
            }, SemanticValidator { null })
        val startup = SettingsStartupCoordinator(stores.gate, {
            imports++; stores.assertDenied(); importer.startup()
        }, control, { kills++ })
        assertTrue(startup.run())
        assertEquals(1, imports)
        assertEquals(1, control.heals)
        assertEquals(0, control.blocks)
        assertEquals(0, kills)
        assertArrayEquals(intArrayOf(0, 0, 0), stores.opens)
        assertEquals("data", stores.gate.access(stores.data))
    }

    // WHY: JOURNAL_WRITE_FAILED may leave Staged on disk, but its StartupResult still forbids normal operation.
    // catches: deriving the gate from journal phase or killing on every repeated blocked recovery launch.
    @Test fun blockingResultStopsComponentsAndKillsOnlyOnChange() {
        val stores = Stores()
        val control = Control().apply { blockAction = { true } }
        var kills = 0
        val startup = SettingsStartupCoordinator(stores.gate, { blocked }, control, { kills++ })
        assertFalse(startup.run())
        assertEquals(1, kills)
        assertEquals(0, control.heals)
        stores.assertDenied()
        control.blockAction = { false }
        assertFalse(startup.run())
        assertEquals(1, kills)
        assertEquals(2, control.blocks)
        stores.assertDenied()
    }

    // WHY: CLAIM(startup-fail-closed); a healthy journal cannot excuse failing component healing.
    // catches: opening before heal, swallowing heal failure as success, or omitting block on the catch path.
    @Test fun healFailureKeepsGateClosedAndBlocksComponents() {
        val stores = Stores()
        val control = Control().apply {
            healAction = { stores.assertDenied(); throw IllegalStateException("Heal failed") }
            blockAction = { stores.assertDenied(); true }
        }
        var kills = 0
        assertFalse(SettingsStartupCoordinator(stores.gate, { StartupResult.NoImport }, control, { kills++ }).run())
        assertEquals(1, control.heals)
        assertEquals(1, control.blocks)
        assertEquals(1, kills)
        stores.assertDenied()
    }

    // WHY: snapshot/toggle failures must leave all public factories denied even when stopping components fails too.
    // catches: a catch path opening the gate or reporting true after an exception from either port.
    @Test fun importerOrBlockExceptionCannotOpenGate() {
        for (importerFails in listOf(false, true)) {
            val stores = Stores()
            val control = Control().apply { blockAction = { throw IllegalStateException("Snapshot/toggle failed") } }
            var kills = 0
            val importer = { if (importerFails) throw IllegalStateException("Import failed") else blocked }
            assertFalse(SettingsStartupCoordinator(stores.gate, importer, control, { kills++ }).run())
            assertEquals(1, control.blocks)
            assertEquals(0, kills)
            stores.assertDenied()
        }
    }

    // WHY: a simulated death is an interruption, never a recoverable Exception or successful startup.
    // catches: catching Throwable in the coordinator and proceeding to recovery/gate-open in the same process.
    @Test fun processDeathPropagatesWithoutOpeningGate() {
        for (duringHeal in listOf(false, true)) {
            val stores = Stores()
            val control = Control().apply { if (duringHeal) healAction = { throw AssertionError("Process death") } }
            assertThrows(AssertionError::class.java) {
                SettingsStartupCoordinator(stores.gate,
                    { if (duringHeal) StartupResult.NoImport else throw AssertionError("Process death") }, control, {}).run()
            }
            assertEquals(0, control.blocks)
            stores.assertDenied()
        }
    }

    // WHY: startup must invoke the real protocol without PackageManager mutations on a healthy marker-off launch.
    // catches: startup unconditionally blocking components or a protocol that always writes marker state.
    @Test fun healthyStartupDoesNotWriteComponentStateOrOpenStores() {
        val stores = Stores()
        var writes = 0
        val components = object : ComponentStates {
            override fun list(): List<RecoveryComponent> = error("Healthy startup must not enumerate components")
            override fun get(name: String) = ComponentOverride.DEFAULT
            override fun set(name: String, state: Int) { writes++ }
        }
        val snapshot = object : ComponentStateSnapshot {
            override fun read(): Map<String, Int>? = error("Healthy startup must not read snapshot")
            override fun write(states: Map<String, Int>) { writes++ }
            override fun clear() { writes++ }
        }
        assertTrue(SettingsStartupCoordinator(stores.gate, { StartupResult.NoImport },
            RecoveryComponentProtocol("app.Recovery", components, snapshot), { fail("Healthy startup killed process") }).run())
        assertEquals(0, writes)
        assertArrayEquals(intArrayOf(0, 0, 0), stores.opens)
    }
}
