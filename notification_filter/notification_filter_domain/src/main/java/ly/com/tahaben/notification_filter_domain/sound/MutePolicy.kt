package ly.com.tahaben.notification_filter_domain.sound

enum class CallAudience { ANY, CONTACTS, STARRED, NONE }

data class MutePolicy(
    val calls: CallAudience,
    val repeatCallers: Boolean,
    val alarms: Boolean,
    val media: Boolean,
    val systemSounds: Boolean,
    val priorityChannels: Boolean,
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
            calls = CallAudience.NONE,
            repeatCallers = false,
            alarms = false,
            media = false,
            systemSounds = false,
            priorityChannels = false,
            messages = false,
            conversations = false,
            reminders = false,
            events = false,
            showFullScreenIntent = false,
            showLights = false,
            showPeek = false,
            showStatusBar = false,
            showBadge = false,
            showAmbient = false,
            showNotificationList = false,
        )
    }
}
