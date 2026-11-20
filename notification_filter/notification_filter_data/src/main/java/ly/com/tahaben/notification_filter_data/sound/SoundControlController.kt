package ly.com.tahaben.notification_filter_data.sound

import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannelPort
import ly.com.tahaben.notification_filter_domain.sound.GrantsPort
import ly.com.tahaben.notification_filter_domain.sound.SoundClock
import ly.com.tahaben.notification_filter_domain.sound.SoundControlState
import ly.com.tahaben.notification_filter_domain.sound.SoundStateStore
import ly.com.tahaben.notification_filter_domain.sound.SoundStatusPort
import ly.com.tahaben.notification_filter_domain.sound.ZenRulePort

class SoundControlController(
    private val ownerPackage: String,
    private val rules: ZenRulePort,
    private val channel: BreakthroughChannelPort,
    private val grants: GrantsPort,
    private val store: SoundStateStore,
    private val clock: SoundClock,
    private val status: SoundStatusPort,
    private val switches: () -> SoundSwitches,
) {
    suspend fun enable(): SoundControlState = SoundControlState.Off
    suspend fun off(): SoundControlState = SoundControlState.Off
    suspend fun onAppStart(): SoundControlState = SoundControlState.Off
    suspend fun onListenerConnected(): SoundControlState = SoundControlState.Off
    suspend fun onWorker(generation: Long): SoundControlState = SoundControlState.Off
}

data class SoundSwitches(val flagOn: Boolean, val filterOn: Boolean, val appOn: Boolean)
