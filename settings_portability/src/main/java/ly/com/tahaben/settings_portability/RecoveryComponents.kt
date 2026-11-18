package ly.com.tahaben.settings_portability

/** PackageManager's raw overrides, including explicit WorkManager overrides, are preserved unchanged. */
object ComponentOverride {
    const val DEFAULT = 0
    const val ENABLED = 1
    const val DISABLED = 2
    const val DISABLED_USER = 3
    const val DISABLED_UNTIL_USED = 4
}

data class RecoveryComponent(val name: String, val manifestEnabled: Boolean)

/** Lists every current component, including disabled ones. Mutations return only after the platform accepts them. */
interface ComponentStates {
    fun list(): List<RecoveryComponent>
    fun get(name: String): Int
    fun set(name: String, state: Int)
}

/** Private durable map. Null means absent; malformed/unreadable snapshots must throw, never return null. */
interface ComponentStateSnapshot {
    fun read(): Map<String, Int>?
    fun write(states: Map<String, Int>)
    fun clear()
}

interface RecoveryControl {
    /** True only if this call changed normal components, so the caller must terminate their existing bindings. */
    fun block(): Boolean
    fun heal()
}

/**
 * CLAIM(component-snapshot-first): a complete raw snapshot is durable before the first normal disable
 * [RecoveryComponentsTest.snapshotPrecedesDisablesAndRepeatedBlockDoesNothing].
 * CLAIM(component-heal-exact): existing recorded components recover their raw overrides; removed components are
 * skipped and new unrecorded components are untouched [RecoveryComponentsTest.healingSkipsRemovedAndLeavesNewComponentsAlone].
 * CLAIM(component-marker-last): healing clears the snapshot before disabling the recovery marker
 * [RecoveryComponentsTest.crashesAtEveryDurableBoundaryConverge].
 */
class RecoveryComponentProtocol(
    private val marker: String,
    private val components: ComponentStates,
    private val snapshot: ComponentStateSnapshot,
) : RecoveryControl {
    override fun block(): Boolean {
        if (components.get(marker) != ComponentOverride.ENABLED) components.set(marker, ComponentOverride.ENABLED)
        val current = normalComponents()
        val states = snapshot.read()?.toMutableMap() ?: linkedMapOf()
        validate(states)
        var extended = false
        for (component in current) if (component.name !in states) {
            states[component.name] = checked(components.get(component.name))
            extended = true
        }
        if (extended) snapshot.write(states.toMap())
        var changed = false
        for (component in current) {
            val raw = checked(components.get(component.name))
            if (raw == ComponentOverride.ENABLED || raw == ComponentOverride.DEFAULT && component.manifestEnabled) {
                components.set(component.name, ComponentOverride.DISABLED)
                changed = true
            }
        }
        return changed
    }

    override fun heal() {
        if (components.get(marker) != ComponentOverride.ENABLED) return
        snapshot.read()?.let { states ->
            validate(states)
            val currentNames = normalComponents().map { it.name }.toSet()
            for ((name, expected) in states) if (name in currentNames && components.get(name) != expected)
                components.set(name, expected)
            snapshot.clear()
        }
        components.set(marker, ComponentOverride.DISABLED)
    }
    private fun normalComponents() = components.list().filter { it.name != marker }.distinctBy { it.name }
    private fun checked(state: Int): Int = state.also { require(it in ComponentOverride.DEFAULT..ComponentOverride.DISABLED_UNTIL_USED) }
    private fun validate(states: Map<String, Int>) {
        require(states.keys.none { it == marker || !it.contains('.') })
        states.values.forEach(::checked)
    }
}
