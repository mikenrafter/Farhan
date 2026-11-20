package ly.com.tahaben.notification_filter_domain.sound

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.*
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.testsupport.item
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test

class SoundDeliveryTest {
    private class Fixture(points: Int, soundControl: Boolean, immediate: Boolean = false) {
        val posts = mutableListOf<Pair<String, ProxyAlert>>()
        val budget = FakeBudgetStore()
        val rows = FakeRowStore()
        private val environment = FakeEnvironment(
            listOf(window(0, 540, 0), window(540, 720, points), window(720, 1440, 0)),
            if (immediate) PriorityDeliverySetting.DEFAULTS + (
                HIGH to PriorityDeliverySetting(DeliveryCadence.IMMEDIATELY, BudgetBehavior.RESPECTING)
            ) else PriorityDeliverySetting.DEFAULTS,
        )
        val coordinator = NotificationDeliveryCoordinator(
            rows, budget,
            object : ProxyNotificationPoster {
                override suspend fun post(item: NotificationItem) = post(item, ProxyAlert.Normal)
                override suspend fun post(item: NotificationItem, alert: ProxyAlert): PostResult {
                    posts += item.id to alert
                    return PostResult.Posted
                }
                override suspend fun refresh(item: NotificationItem) = post(item, ProxyAlert.Silent)
            },
            object : DeliveryEnvironment by environment {
                override fun soundElection(item: NotificationItem) = ElectionInput(configured = true).takeIf { soundControl }
            },
        )
    }

    // why: v2.10 limits a batch to one audible proxy and keeps the original delivery budget.
    // catches: using the legacy poster signature, electing per item without a tick budget, or skipping normal spend.
    @Test
    fun `batch posts three elected deliveries but threads one breakthrough and two silent alerts`() = runTest {
        val fixture = Fixture(points = 8, soundControl = true)
        for ((index, id) in listOf("A", "B", "C").withIndex()) {
            assertThat(fixture.coordinator.onArrival(item(id, timestamp = 100L + index), HIGH))
                .isEqualTo(DeliveryOutcome.Deferred(id))
        }
        assertThat(fixture.posts).isEmpty()

        val outcomes = fixture.coordinator.onBatchTick()
        assertThat(outcomes).containsExactly(
            DeliveryOutcome.Delivered("A"), DeliveryOutcome.Delivered("B"), DeliveryOutcome.Delivered("C"),
        )
        assertThat(fixture.posts.map { it.first }).containsExactly("A", "B", "C")
        assertThat(fixture.posts.map { it.second }).containsExactly(ProxyAlert.Breakthrough, ProxyAlert.Silent, ProxyAlert.Silent).inOrder()
        // Each HIGH costs 2 points, so three completed posts spend six.
        assertThat(fixture.budget.state.spentPoints).isEqualTo(6)
    }

    // why: v2.10 attaches election to authorized deliveries and the flagged-off path keeps its channel.
    // catches: posting an elected notification before the budget gate or making every normal delivery silent.
    @Test
    fun `elected delivery respects a zero budget and flag off preserves normal posting`() = runTest {
        val blocked = Fixture(points = 0, soundControl = true)
        assertThat(blocked.coordinator.onArrival(item("A", timestamp = 100), HIGH)).isEqualTo(DeliveryOutcome.Deferred("A"))
        assertThat(blocked.coordinator.onBatchTick()).containsExactly(DeliveryOutcome.Deferred("A"))
        assertThat(blocked.posts).isEmpty()
        assertThat(blocked.budget.state.spentPoints).isEqualTo(0)

        val flagOff = Fixture(points = 2, soundControl = false)
        flagOff.coordinator.onArrival(item("A", timestamp = 100), HIGH)
        assertThat(flagOff.coordinator.onBatchTick()).containsExactly(DeliveryOutcome.Delivered("A"))
        assertThat(flagOff.posts).containsExactly("A" to ProxyAlert.Normal)
        assertThat(flagOff.budget.state.spentPoints).isEqualTo(2)
    }

    // why: the v2 update disclosure depends on the real refresh entry point preserving silence.
    // catches: re-electing the same source identity when the existing proxy is refreshed and charging again.
    @Test
    fun `same key and post time refreshes silently without another spend`() = runTest {
        val fixture = Fixture(points = 8, soundControl = true, immediate = true)
        val message = item("K", timestamp = 100)
        assertThat(fixture.coordinator.onArrival(message, HIGH)).isEqualTo(DeliveryOutcome.Delivered("K"))
        assertThat(fixture.coordinator.onArrival(message.copy(text = "updated"), HIGH)).isEqualTo(DeliveryOutcome.Delivered("K"))
        assertThat(fixture.posts).containsExactly("K" to ProxyAlert.Breakthrough, "K" to ProxyAlert.Silent).inOrder()
        assertThat(fixture.budget.state.spentPoints).isEqualTo(2) // One HIGH delivery, no charge for refresh.
    }
}
