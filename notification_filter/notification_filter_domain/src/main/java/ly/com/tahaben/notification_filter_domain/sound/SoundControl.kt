package ly.com.tahaben.notification_filter_domain.sound

import kotlinx.coroutines.flow.StateFlow
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory

/** The settings entry point. Reconciliation can update state, while enable represents explicit consent. */
interface SoundControl {
    val state: StateFlow<SoundControlState>
    fun grants(): SoundGrants
    fun choices(): SoundChoices
    fun exceptionPackages(): Set<String>
    fun effectiveFilter(): Int
    suspend fun refresh()
    suspend fun enable()
    suspend fun off()
    suspend fun setAppElected(packageName: String, elected: Boolean)
    suspend fun setCategoryElected(category: NotificationCategory, elected: Boolean)
    suspend fun setSystemSoundsAllowed(allowed: Boolean)
}

data class SoundChoices(
    val apps: Set<String>,
    val categories: Set<NotificationCategory>,
    val systemSoundsAllowed: Boolean,
)

object SoundElectionSettings {
    const val APP_PREFIX = "notification_sound_app_"
    const val CATEGORY_PREFIX = "notification_sound_category_"
    const val SYSTEM_SOUNDS = "notification_sound_system"
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun isPackageName(value: String): Boolean = value.length <= 255 && packageName.matches(value)
}
