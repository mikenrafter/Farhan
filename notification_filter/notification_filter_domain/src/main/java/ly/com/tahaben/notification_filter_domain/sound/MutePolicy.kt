package ly.com.tahaben.notification_filter_domain.sound

enum class CallAudience { ANY, CONTACTS, STARRED, NONE }

data class MutePolicy(
    val calls: CallAudience,
    val repeatCallers: Boolean,
    val alarms: Boolean,
    val media: Boolean,
    val systemSounds: Boolean,
    val priorityChannels: Boolean?,
    val messages: Boolean,
    val conversations: Boolean,
    val reminders: Boolean,
    val events: Boolean,
    val showFullScreenIntent: Boolean,
    val showLights: Boolean,
    val showPeek: Boolean,
    val showStatusBar: Boolean,
    val showBadge: Boolean,
    val showAmbient: Boolean,
    val showNotificationList: Boolean,
) {
    companion object {
        fun forPilot(media: Boolean = true, systemSounds: Boolean = true): MutePolicy = MutePolicy(
            calls = CallAudience.ANY,
            repeatCallers = true,
            alarms = true,
            media = media,
            systemSounds = systemSounds,
            priorityChannels = true,
            messages = false,
            conversations = false,
            reminders = false,
            events = false,
            showFullScreenIntent = true,
            showLights = true,
            showPeek = true,
            showStatusBar = true,
            showBadge = true,
            showAmbient = true,
            showNotificationList = true,
        )
    }
}
