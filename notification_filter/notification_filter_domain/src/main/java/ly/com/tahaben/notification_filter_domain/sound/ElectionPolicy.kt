package ly.com.tahaben.notification_filter_domain.sound

import ly.com.tahaben.notification_filter_domain.model.NotificationItem

enum class ProxyAlert { Breakthrough, Normal, Silent }

data class AlertIdentity(val key: String, val postTime: Long)

data class ElectionInput(
    val configured: Boolean,
    val exception: Boolean = false,
    val workProfile: Boolean = false,
)

object ElectionPolicy {
    /** Classifies an authorized delivery. The coordinator owns history and batch accounting. */
    fun alert(
        item: NotificationItem,
        election: ElectionInput,
        alreadyAlerted: Set<AlertIdentity> = emptySet(),
        batchAlreadyAlerted: Boolean = false,
    ): ProxyAlert = ProxyAlert.Silent
}
