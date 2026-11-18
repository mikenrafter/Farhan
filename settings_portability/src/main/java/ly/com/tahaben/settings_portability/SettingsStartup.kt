package ly.com.tahaben.settings_portability

class RecoveryRequiredException : IllegalStateException("Settings recovery must finish before opening stores")

/** Default-deny gate for the same lazy store instances used privately by the importer. */
class SettingsStoreGate {
    @Volatile private var allowed = false
    fun close() { allowed = false }
    fun open() { allowed = true }
    fun requireOpen() { if (!allowed) throw RecoveryRequiredException() }
    fun <T> access(store: Lazy<T>): T { requireOpen(); return store.value }
}

/**
 * CLAIM(startup-heal-before-open): importer and component healing finish before any public store is accessible
 * [SettingsStartupTest.healRunsWithEveryStoreStillDenied].
 * CLAIM(startup-fail-closed): import/recovery exceptions keep the gate shut and attempt to stop normal components;
 * process-death Errors propagate [SettingsStartupTest.healFailureKeepsGateClosedAndBlocksComponents].
 */
class SettingsStartupCoordinator(
    private val gate: SettingsStoreGate,
    private val importer: () -> StartupResult,
    private val recovery: RecoveryControl,
    private val killProcess: () -> Unit,
) {
    fun run(): Boolean {
        gate.close()
        try {
            if (importer().allowsNormalOperation) {
                recovery.heal()
                gate.open()
                return true
            }
        } catch (_: Exception) {
            // A healthy journal alone cannot justify opening stores when component healing failed.
        }
        try { if (recovery.block()) killProcess() } catch (_: Exception) { }
        return false
    }
}
