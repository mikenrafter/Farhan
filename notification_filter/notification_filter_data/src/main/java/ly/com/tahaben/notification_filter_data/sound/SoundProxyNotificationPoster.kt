package ly.com.tahaben.notification_filter_data.sound

import ly.com.tahaben.notification_filter_domain.delivery.BREAKTHROUGH_BYPASS_LOST
import ly.com.tahaben.notification_filter_domain.delivery.PostResult
import ly.com.tahaben.notification_filter_domain.delivery.ProxyNotificationPoster
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannelPort
import ly.com.tahaben.notification_filter_domain.sound.ProxyAlert

internal const val BREAKTHROUGH_CHANNEL_ID = "farhan_breakthrough_notifications"

internal class SoundProxyNotificationPoster(
    private val standardChannelId: String,
    private val breakthrough: BreakthroughChannelPort,
    private val bypassLost: () -> Unit = {},
    private val publish: (NotificationItem, String, Boolean) -> PostResult,
) : ProxyNotificationPoster {
    override suspend fun post(item: NotificationItem): PostResult = post(item, ProxyAlert.Normal)
    override suspend fun refresh(item: NotificationItem): PostResult = post(item, ProxyAlert.Silent)

    override suspend fun post(item: NotificationItem, alert: ProxyAlert): PostResult = when (alert) {
        ProxyAlert.Normal -> publish(item, standardChannelId, false)
        ProxyAlert.Silent -> publish(item, standardChannelId, true)
        ProxyAlert.Breakthrough -> {
            if (breakthrough.read()?.usable == true) publish(item, BREAKTHROUGH_CHANNEL_ID, false)
            else {
                bypassLost()
                PostResult.Failed(BREAKTHROUGH_BYPASS_LOST)
            }
        }
    }
}
