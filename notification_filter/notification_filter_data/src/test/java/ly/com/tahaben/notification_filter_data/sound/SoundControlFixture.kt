package ly.com.tahaben.notification_filter_data.sound

import ly.com.tahaben.notification_filter_domain.sound.*

internal const val OWNER = "ly.com.tahaben.farhan_alpha"
internal const val CONDITION = "condition://ly.com.tahaben.farhan_alpha/sound-control"
internal val OWNED = ZenRule("ours", OWNER, CONDITION, active = true)
internal val FOREIGN = ZenRule("theirs", "other.app", CONDITION, active = true)
internal val UNRELATED = ZenRule("other-mode", OWNER, "condition://ly.com.tahaben.farhan/unrelated", active = true)
internal val HELD_INTENT = SoundIntent(true, 7, "ours")
internal val GOOD_CHANNEL = BreakthroughChannel(bypassesDnd = true, importance = 4)
internal class ProcessDeath : Error()

internal class SoundControlFixture(var intent: SoundIntent = SoundIntent(), vararg existing: ZenRule) {
    val events = mutableListOf<String>()
    val rules = existing.associateByTo(linkedMapOf()) { it.id }
    val activations = mutableListOf<Long>()
    val mutations = mutableListOf<String>()
    var switches = SoundSwitches(flagOn = true, filterOn = true, appOn = true)
    var grants = SoundGrants(listener = true, policy = true, post = true)
    var channel: BreakthroughChannel? = GOOD_CHANNEL
    var loseAccessAtRemove = false
    var refuseRemoval = false
    var crashBeforeRemove = false
    var crashAfterAdd = false
    var refuseCommit = false
    var statusVisible = existing.any { it.active && it.ownerPackage == OWNER }
    var recoveryVisible = false
    var addedPolicy: MutePolicy? = null
    var readablePolicy: MutePolicy? = null

    private val zen = object : ZenRulePort {
        override fun add(conditionId: String, policy: MutePolicy): String {
            val id = "new-${mutations.size + 1}"
            events += "add:$id"
            mutations += "add:$id"
            addedPolicy = policy
            rules[id] = ZenRule(id, OWNER, conditionId, active = false)
            if (crashAfterAdd) throw ProcessDeath()
            return id
        }

        override fun update(id: String, policy: MutePolicy): Boolean {
            mutations += "update:$id"
            return id in rules
        }

        override fun remove(id: String): Boolean {
            events += "remove:$id"
            mutations += "remove:$id"
            if (crashBeforeRemove) throw ProcessDeath()
            if (loseAccessAtRemove) {
                grants = grants.copy(policy = false)
                throw AccessLost()
            }
            if (refuseRemoval) return false
            return rules.remove(id) != null
        }

        override fun ownedRules() = rules.values.toList()
        override fun ruleState(id: String) = rules[id]

        override fun setState(id: String, active: Boolean) {
            events += "state:$id:$active"
            mutations += "state:$id:$active"
            if (active) activations += intent.generation
            rules[id]?.let { rules[id] = it.copy(active = active) }
        }

        override fun consolidatedPolicy(): MutePolicy? {
            events += "policy:read"
            return readablePolicy
        }
        override fun currentFilter() = if (rules.values.any { it.active }) 2 else 1
        override fun policyAccess() = grants.policy
    }

    private val breakthrough = object : BreakthroughChannelPort {
        override fun create() {
            events += "channel:create"
            if (channel == null) channel = BreakthroughChannel(grants.policy, importance = 4)
        }

        override fun read(): BreakthroughChannel? {
            events += "channel:read"
            return channel
        }
    }

    private val store = object : SoundStateStore {
        override fun read() = intent
        override fun commit(intent: SoundIntent): Boolean {
            events += "commit:${intent.desiredOn}:${intent.generation}"
            if (refuseCommit) return false
            this@SoundControlFixture.intent = intent
            return true
        }
    }

    private val status = object : SoundStatusPort {
        override fun show(modesRecovery: Boolean) {
            statusVisible = true
            recoveryVisible = modesRecovery
        }

        override fun clear() {
            events += "status:clear"
            statusVisible = false
        }
    }

    fun controller() = SoundControlController(
        OWNER, zen, breakthrough, GrantsPort { grants }, store, SoundClock { SoundTime(500, "boot-1") },
        status, { switches },
    )
}
