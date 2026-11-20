package ly.com.tahaben.notification_filter_data.service

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.delivery.NotificationActionStore
import org.junit.Test

class ScopedProxyClearTest {
    // why: CLAIM(scoped-proxy-effects) removes all matching actions with IPC only for matching live proxies.
    // catches: cancelling every deleted ID, cancelling unrelated active notifications, or retaining inactive actions.
    @Test fun `clears retained actions and cancels only matching live proxies`() {
        val actions = NotificationActionStore<String>(now = { 0 })
        for (id in listOf("posted", "inactive", "other")) actions.put(id, id)
        val manager = FakeManager(
            listOf(PostedNotification("farhan:posted", 7), PostedNotification("farhan:other", 1), PostedNotification("inactive", 2)),
        )

        clearScopedProxies(listOf("posted", "inactive"), actions, manager::activeNotifications, manager::cancel)

        assertThat(manager.snapshots).isEqualTo(1)
        assertThat(manager.cancellations).containsExactly("farhan:posted" to 7)
        assertThat(actions.get("posted")).isNull()
        assertThat(actions.get("inactive")).isNull()
        assertThat(actions.get("other")).isEqualTo("other")
    }

    private class FakeManager(private val posted: List<PostedNotification>) {
        var snapshots = 0
            private set
        val cancellations = mutableListOf<Pair<String, Int>>()

        fun activeNotifications(): List<PostedNotification> {
            snapshots++
            return posted
        }

        fun cancel(tag: String, id: Int) {
            cancellations += tag to id
        }
    }
}
