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
    ): Reconciliation {
        val owned = matchingRules(rules)
        val release = owned.takeIf { it.isNotEmpty() }
            ?.let { listOf(ReconcileAction.Release(it.map(ZenRule::id))) }.orEmpty()
        if (!intent.desiredOn || !prerequisites.flagOn) return Reconciliation(SoundControlState.Off, release)

        val reason = prerequisites.suspensionBeforeChannel()
            ?: SuspensionReason.CHANNEL_UNUSABLE.takeUnless { prerequisites.channel?.usable == true }
        if (reason != null) return Reconciliation(SoundControlState.Suspended(reason), release)

        val rule = preferredRule(intent, owned)
        val state = if (rule?.active == true) SoundControlState.Held(rule.id)
            else SoundControlState.Suspended(SuspensionReason.STOPPED_IN_SETTINGS)
        val actions = if (rule != null && rule.id != intent.ruleId) listOf(ReconcileAction.Adopt(rule.id)) else emptyList()
        return Reconciliation(state, actions)
    }

    fun matchingRules(rules: List<ZenRule>): List<ZenRule> =
        rules.filter { it.ownerPackage == ownerPackage && it.conditionId == conditionId }

    fun preferredRule(intent: SoundIntent, matching: List<ZenRule>): ZenRule? =
        matching.firstOrNull { it.id == intent.ruleId } ?: matching.firstOrNull()
}

fun SoundPrerequisites.suspensionBeforeChannel(): SuspensionReason? = when {
    !filterOn -> SuspensionReason.FILTER_OFF
    !appOn -> SuspensionReason.APP_OFF
    !grants.listener -> SuspensionReason.LISTENER_ACCESS
    !grants.policy -> SuspensionReason.NEEDS_ACCESS
    !grants.post -> SuspensionReason.POST_PERMISSION
    else -> null
}
