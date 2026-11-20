package ly.com.tahaben.notification_filter_domain.sound

sealed interface ReconcileAction {
    data class Adopt(val ruleId: String) : ReconcileAction
    data class Release(val ruleIds: List<String>) : ReconcileAction
}

data class Reconciliation(val state: SoundControlState, val actions: List<ReconcileAction>)

/** Decides what to adopt or release from one platform snapshot. Mutations are performed by the controller. */
class Reconciler(private val ownerPackage: String, private val conditionId: String) {
    fun plan(
        intent: SoundIntent,
        prerequisites: SoundPrerequisites,
        rules: List<ZenRule>,
    ): Reconciliation = Reconciliation(SoundControlState.Off, emptyList())
}
