package ly.com.tahaben.notification_filter_domain.sound

/** Policy-access loss during a rule operation, including races after checking the grant. */
class AccessLost : Exception()

interface ZenRulePort {
    @Throws(AccessLost::class)
    fun add(conditionId: String, policy: MutePolicy): String

    @Throws(AccessLost::class)
    fun update(id: String, policy: MutePolicy): Boolean

    @Throws(AccessLost::class)
    fun remove(id: String): Boolean

    /** Rules visible to this caller. The controller independently checks ownership and identity. */
    @Throws(AccessLost::class)
    fun ownedRules(): List<ZenRule>

    @Throws(AccessLost::class)
    fun ruleState(id: String): ZenRule?

    @Throws(AccessLost::class)
    fun setState(id: String, active: Boolean)

    fun consolidatedPolicy(): MutePolicy?
    fun currentFilter(): Int
    fun policyAccess(): Boolean
}

interface BreakthroughChannelPort {
    /** Requests the channel after policy access; consumers must verify [read] before posting. */
    fun create()
    fun read(): BreakthroughChannel?
}

fun interface GrantsPort {
    fun read(): SoundGrants
}

interface SoundStateStore {
    fun read(): SoundIntent

    /** Returns true after the entire intent is durable. False forbids the dependent rule effect. */
    fun commit(intent: SoundIntent): Boolean
}

data class SoundTime(val elapsedMillis: Long, val bootId: String)

fun interface SoundClock {
    fun now(): SoundTime
}

interface SoundStatusPort {
    fun show(modesRecovery: Boolean)
    fun clear()
}
