package ly.com.tahaben.notification_filter_domain.sound

sealed interface SoundControlState {
    data object Off : SoundControlState
    data object Creating : SoundControlState
    data class Held(val ruleId: String) : SoundControlState
    data class Suspended(val reason: SuspensionReason) : SoundControlState
}

enum class SuspensionReason {
    FILTER_OFF, APP_OFF, LISTENER_ACCESS, NEEDS_ACCESS, POST_PERMISSION,
    CHANNEL_UNUSABLE, STOPPED_IN_SETTINGS, REMOVAL_FAILED,
}

data class SoundIntent(
    val desiredOn: Boolean = false,
    val generation: Long = 0,
    val ruleId: String? = null,
)

data class SoundGrants(val listener: Boolean, val policy: Boolean, val post: Boolean)

data class SoundPrerequisites(
    val flagOn: Boolean,
    val filterOn: Boolean,
    val appOn: Boolean,
    val grants: SoundGrants,
    val channel: BreakthroughChannel?,
)

data class ZenRule(val id: String, val ownerPackage: String, val conditionId: String, val active: Boolean)

data class BreakthroughChannel(val bypassesDnd: Boolean, val importance: Int)
