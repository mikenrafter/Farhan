package ly.com.tahaben.notification_filter_data.sound

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ly.com.tahaben.notification_filter_domain.sound.AccessLost
import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannelPort
import ly.com.tahaben.notification_filter_domain.sound.GrantsPort
import ly.com.tahaben.notification_filter_domain.sound.MutePolicy
import ly.com.tahaben.notification_filter_domain.sound.ReconcileAction
import ly.com.tahaben.notification_filter_domain.sound.Reconciler
import ly.com.tahaben.notification_filter_domain.sound.SoundClock
import ly.com.tahaben.notification_filter_domain.sound.SoundControlState
import ly.com.tahaben.notification_filter_domain.sound.SoundIntent
import ly.com.tahaben.notification_filter_domain.sound.SoundPrerequisites
import ly.com.tahaben.notification_filter_domain.sound.SoundStateStore
import ly.com.tahaben.notification_filter_domain.sound.SoundStatusPort
import ly.com.tahaben.notification_filter_domain.sound.SuspensionReason
import ly.com.tahaben.notification_filter_domain.sound.ZenRulePort
import ly.com.tahaben.notification_filter_domain.sound.suspensionBeforeChannel

class SoundControlController(
    ownerPackage: String,
    private val rules: ZenRulePort,
    private val channel: BreakthroughChannelPort,
    private val grants: GrantsPort,
    private val store: SoundStateStore,
    private val clock: SoundClock,
    private val status: SoundStatusPort,
    private val switches: suspend () -> SoundSwitches,
    private val mutePolicy: () -> MutePolicy = { MutePolicy.forPilot() },
    private val setPilotFlag: ((Boolean) -> Boolean)? = null,
) {
    private val mutex = Mutex()
    private val conditionId = soundControlConditionId(ownerPackage)
    private val reconciler = Reconciler(ownerPackage, conditionId)
    private val current = MutableStateFlow<SoundControlState>(SoundControlState.Off)
    val state = current.asStateFlow()
    private var removalBlockedByAccess = false
    private var hasRecoveryStatus = false

    /** An enable or resume tap authorizes writes for this new generation. */
    suspend fun enable(): SoundControlState = operation {
        if (setPilotFlag?.invoke(true) == false) return@operation suspended(SuspensionReason.STORAGE_FAILED)
        val prerequisites = prerequisites()
        if (!prerequisites.flagOn) return@operation reconcile()
        val reason = prerequisites.suspensionBeforeChannel()
        if (reason != null) {
            val reconciled = reconcile()
            return@operation if (reconciled == SoundControlState.Off) suspended(reason) else reconciled
        }
        if (prerequisites.channel == null) channel.create()
        if (channel.read()?.usable != true) {
            val reconciled = reconcile()
            return@operation if (reconciled == SoundControlState.Off) suspended(SuspensionReason.CHANNEL_UNUSABLE)
                else reconciled
        }

        val before = store.read()
        val owned = reconciler.matchingRules(rules.ownedRules())
        val existing = reconciler.preferredRule(before, owned)
        val intent = SoundIntent(true, before.generation + 1, existing?.id)
        if (!store.commit(intent)) return@operation suspended(SuspensionReason.STORAGE_FAILED)
        current.value = SoundControlState.Creating
        for (duplicate in owned) {
            if (duplicate.id != existing?.id && !removeConfirmed(duplicate.id)) {
                return@operation recovery(SuspensionReason.REMOVAL_FAILED)
            }
        }

        val policy = mutePolicy()
        val id = existing?.id ?: rules.add(conditionId, policy)
        if (id.isBlank()) return@operation suspended(SuspensionReason.PLATFORM_FAILED)
        if (id != intent.ruleId && !store.commit(intent.copy(ruleId = id))) {
            if (!removeConfirmed(id)) return@operation recovery(SuspensionReason.REMOVAL_FAILED)
            return@operation suspended(SuspensionReason.STORAGE_FAILED)
        }
        if (existing != null && !existing.active && !rules.update(id, policy)) {
            return@operation reconcile()
        }
        if (existing?.active != true) {
            val now = prerequisites()
            if (!now.flagOn || now.suspensionBeforeChannel() != null || now.channel?.usable != true) {
                return@operation reconcile()
            }
            showStatus()
            rules.setState(id, active = true)
        }
        reconcile()
    }

    suspend fun off(): SoundControlState = operation {
        val previous = store.read()
        val saved = try {
            store.commit(previous.copy(desiredOn = false, generation = previous.generation + 1))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!saved) {
            return@operation releaseAfterFailedOffSave(previous)
        }
        val released = reconcile()
        if (released == SoundControlState.Off && setPilotFlag?.invoke(false) == false) {
            return@operation suspended(SuspensionReason.STORAGE_FAILED)
        }
        released
    }

    private fun releaseAfterFailedOffSave(previous: SoundIntent): SoundControlState {
        // A stale On journal cannot reactivate a removed rule [pilot spec v2, release-only reconciler].
        val released = try {
            var allRemoved = true
            for (rule in reconciler.matchingRules(rules.ownedRules())) {
                if (!removeConfirmed(rule.id)) allRemoved = false
            }
            allRemoved
        } catch (_: AccessLost) {
            removalBlockedByAccess = true
            if (previous.ruleId != null || hasRecoveryStatus) return recovery(SuspensionReason.STORAGE_FAILED)
            return suspended(SuspensionReason.STORAGE_FAILED)
        }
        if (!released) return recovery(SuspensionReason.STORAGE_FAILED)
        clearStatus()
        return suspended(SuspensionReason.STORAGE_FAILED)
    }

    suspend fun onAppStart(): SoundControlState = operation { reconcile() }
    suspend fun onListenerConnected(): SoundControlState = operation { reconcile() }

    suspend fun onWorker(generation: Long): SoundControlState = operation {
        if (store.read().generation == generation) reconcile() else current.value
    }

    fun generation(): Long = store.read().generation

    private suspend fun prerequisites(): SoundPrerequisites {
        val settings = switches()
        return SoundPrerequisites(settings.flagOn, settings.filterOn, settings.appOn, grants.read(), channel.read())
    }

    private suspend fun reconcile(): SoundControlState {
        if (removalBlockedByAccess && !rules.policyAccess()) return accessLost()
        removalBlockedByAccess = false
        val intent = store.read()
        val plan = reconciler.plan(intent, prerequisites(), rules.ownedRules())
        for (action in plan.actions) when (action) {
            is ReconcileAction.Adopt -> {
                if (!store.commit(intent.copy(ruleId = action.ruleId))) {
                    return recovery(SuspensionReason.STORAGE_FAILED)
                }
            }
            is ReconcileAction.Release -> {
                for (id in action.ruleIds) {
                    if (!removeConfirmed(id)) return recovery(SuspensionReason.REMOVAL_FAILED)
                }
            }
        }
        if (plan.state is SoundControlState.Held) {
            showStatus()
        } else {
            if (plan.actions.any { it is ReconcileAction.Release } || !intent.desiredOn) {
                if (intent.ruleId != null && !store.commit(intent.copy(ruleId = null))) {
                    clearStatus()
                    return suspended(SuspensionReason.STORAGE_FAILED)
                }
            }
            clearStatus()
        }
        return plan.state
    }

    private fun removeConfirmed(id: String): Boolean {
        rules.remove(id)
        return rules.ruleState(id) == null
    }

    private suspend fun operation(block: suspend () -> SoundControlState): SoundControlState = mutex.withLock {
        val result = try {
            block()
        } catch (_: AccessLost) {
            removalBlockedByAccess = true
            accessLost()
        } catch (_: IllegalArgumentException) {
            if (store.read().ruleId != null || hasRecoveryStatus) recovery(SuspensionReason.PLATFORM_FAILED)
            else suspended(SuspensionReason.PLATFORM_FAILED)
        }
        current.value = result
        result
    }

    private suspend fun accessLost(): SoundControlState {
        val intent = store.read()
        if (intent.desiredOn && !store.commit(intent.copy(desiredOn = false))) {
            return recovery(SuspensionReason.STORAGE_FAILED)
        }
        if (intent.ruleId != null || hasRecoveryStatus) return recovery(SuspensionReason.NEEDS_ACCESS)
        return if (intent.desiredOn || switches().flagOn) suspended(SuspensionReason.NEEDS_ACCESS)
            else SoundControlState.Off
    }

    private fun showStatus(modesRecovery: Boolean = false) {
        status.show(modesRecovery)
        hasRecoveryStatus = true
    }

    private fun clearStatus() {
        status.clear()
        hasRecoveryStatus = false
    }

    private fun recovery(reason: SuspensionReason): SoundControlState {
        showStatus(modesRecovery = true)
        return suspended(reason)
    }

    private fun suspended(reason: SuspensionReason) = SoundControlState.Suspended(reason)
}

internal fun soundControlConditionId(packageName: String) = "condition://$packageName/sound-control"

data class SoundSwitches(val flagOn: Boolean, val filterOn: Boolean, val appOn: Boolean)
