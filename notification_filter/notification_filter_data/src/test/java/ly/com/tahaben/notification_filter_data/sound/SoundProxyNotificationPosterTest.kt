package ly.com.tahaben.notification_filter_data.sound

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.PostResult
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannel
import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannelPort
import ly.com.tahaben.notification_filter_domain.sound.ProxyAlert
import org.junit.Test

class SoundProxyNotificationPosterTest {
    private val item = NotificationItem("K", "Example", "Title", "Text", "10:00", "source.app", timestamp = 100)
    private data class Publish(val item: NotificationItem, val channel: String, val silent: Boolean)

    private class Fixture(var readBack: BreakthroughChannel?) : BreakthroughChannelPort {
        val calls = mutableListOf<String>()
        val posts = mutableListOf<Publish>()
        val poster = SoundProxyNotificationPoster("farhan_proxy_notifications", this) { item, channel, silent ->
            calls += "publish"
            posts += Publish(item, channel, silent)
            PostResult.Posted
        }

        override fun create() { calls += "create" }
        override fun read(): BreakthroughChannel? {
            calls += "read"
            return readBack
        }
    }

    // why: v2.10's alert type selects the platform channel; Normal and Silent retain the existing channel.
    // catches: dropping the alert argument before publish, keeping the standard channel, or creating it during post.
    @Test
    fun `breakthrough reads the bypass and threads the separate id through the existing post path`() = runTest {
        val fixture = Fixture(BreakthroughChannel(true, 4))

        assertThat(fixture.poster.post(item, ProxyAlert.Normal)).isEqualTo(PostResult.Posted)
        assertThat(fixture.poster.post(item, ProxyAlert.Silent)).isEqualTo(PostResult.Posted)
        assertThat(fixture.poster.post(item, ProxyAlert.Breakthrough)).isEqualTo(PostResult.Posted)

        assertThat(fixture.posts).containsExactly(
            Publish(item, "farhan_proxy_notifications", false),
            Publish(item, "farhan_proxy_notifications", true),
            Publish(item, "farhan_breakthrough_notifications", false),
        ).inOrder()
        assertThat(fixture.calls).containsExactly("publish", "publish", "read", "publish").inOrder()
    }

    // why: v2.5–6 treat Android's read-back as authoritative, including a pre-grant channel that lost bypass.
    // catches: relying on the requested channel settings, silently falling back, or recreating a changed channel.
    @Test
    fun `lost bypass fails before publishing and never recreates the channel`() = runTest {
        val fixture = Fixture(BreakthroughChannel(false, 4))
        assertThat(fixture.poster.post(item, ProxyAlert.Breakthrough)).isEqualTo(PostResult.Failed("breakthrough_bypass_lost"))
        assertThat(fixture.calls).containsExactly("read")
        assertThat(fixture.posts).isEmpty()

        assertThat(fixture.poster.post(item, ProxyAlert.Normal)).isEqualTo(PostResult.Posted)
        assertThat(fixture.posts).containsExactly(Publish(item, "farhan_proxy_notifications", false))
    }
}
