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
    private class Fixture(
        points: Int,
        soundControl: Boolean,
        immediate: Boolean = false,
        singleHead: Boolean = false,
        private val breakthroughFailure: String? = null,
    ) {
        val posts = mutableListOf<Pair<String, ProxyAlert>>()
        val published = mutableListOf<Pair<NotificationItem, ProxyAlert>>()
        val budget = FakeBudgetStore()
        val rows = FakeRowStore()
        private val environment = FakeEnvironment(
            listOf(window(0, 540, 0), window(540, 720, points), window(720, 1440, 0)),
            if (immediate) PriorityDeliverySetting.DEFAULTS + (
                HIGH to PriorityDeliverySetting(DeliveryCadence.IMMEDIATELY, BudgetBehavior.RESPECTING)
            ) else PriorityDeliverySetting.DEFAULTS,
        )
        val coordinator = NotificationDeliveryCoordinator(
            if (singleHead) object : DeliveryRowStore by rows {
                override suspend fun pending(maxPriorityLevel: Int) = rows.pending(maxPriorityLevel).take(1)
                override suspend fun nextHead(after: NotificationItem) = rows.pending(HIGH.level).firstOrNull()
            } else rows,
            budget,
            object : ProxyNotificationPoster {
                override suspend fun post(item: NotificationItem) = post(item, ProxyAlert.Normal)
                override suspend fun post(item: NotificationItem, alert: ProxyAlert): PostResult {
                    posts += item.id to alert
                    if (alert == ProxyAlert.Breakthrough && breakthroughFailure != null) {
                        return PostResult.Failed(breakthroughFailure)
                    }
                    published += item to alert
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

    // why: F1 loses an Immediately item from the shade if intake cancels its source after a failed proxy.
    // catches: returning bypass loss to intake or retrying via a later tick that cannot deliver Immediately.
    @Test
    fun `bypass loss posts the same immediately delivered item once on the normal channel`() = runTest {
        val fixture = Fixture(2, soundControl = true, immediate = true, breakthroughFailure = "breakthrough_bypass_lost")
        val message = item("K", timestamp = 100, text = "Original message")

        assertThat(fixture.coordinator.onArrival(message, HIGH)).isEqualTo(DeliveryOutcome.Delivered("K"))
        assertThat(fixture.posts).containsExactly("K" to ProxyAlert.Breakthrough, "K" to ProxyAlert.Normal).inOrder()
        assertThat(fixture.published).containsExactly(message to ProxyAlert.Normal)
        assertThat(fixture.rows.find("K")).isEqualTo(message.copy(isDelivered = true))
        assertThat(fixture.coordinator.onBatchTick()).isEmpty()
        assertThat(fixture.budget.state.spentPoints).isEqualTo(2) // One completed HIGH post costs two points.
    }

    // why: F1 must drain a same-app queue head and spend the tick's one audible opportunity.
    // catches: holding the next head until another tick or counting the successful Normal fallback as silent.
    @Test
    fun `fallback drains the next app head silently in the same tick`() = runTest {
        val fixture = Fixture(4, soundControl = true, singleHead = true, breakthroughFailure = "breakthrough_bypass_lost")
        val head = item("head", timestamp = 100)
        val next = item("next", timestamp = 101)
        assertThat(fixture.coordinator.onArrival(head, HIGH)).isEqualTo(DeliveryOutcome.Deferred("head"))
        assertThat(fixture.coordinator.onArrival(next, HIGH)).isEqualTo(DeliveryOutcome.Deferred("next"))
        assertThat(fixture.rows.pendingIds()).containsExactly("head", "next")

        assertThat(fixture.coordinator.onBatchTick())
            .containsExactly(DeliveryOutcome.Delivered("head"), DeliveryOutcome.Delivered("next")).inOrder()
        assertThat(fixture.posts).containsExactly(
            "head" to ProxyAlert.Breakthrough, "head" to ProxyAlert.Normal, "next" to ProxyAlert.Silent,
        ).inOrder()
        assertThat(fixture.published).containsExactly(head to ProxyAlert.Normal, next to ProxyAlert.Silent).inOrder()
        assertThat(fixture.rows.pendingIds()).isEmpty()
        assertThat(fixture.budget.state.spentPoints).isEqualTo(4) // Two completed HIGH posts cost four points.
    }

    // why: F1 authorizes a second attempt only when the poster guarantees bypass loss happened before publishing.
    // catches: retrying every Breakthrough failure on the normal channel.
    @Test
    fun `other breakthrough failure returns unchanged without a second attempt`() = runTest {
        val fixture = Fixture(2, soundControl = true, immediate = true, breakthroughFailure = "proxy_capacity_reached")
        val message = item("K", timestamp = 100)

        assertThat(fixture.coordinator.onArrival(message, HIGH))
            .isEqualTo(DeliveryOutcome.PostFailed("K", "proxy_capacity_reached"))
        assertThat(fixture.posts).containsExactly("K" to ProxyAlert.Breakthrough)
        assertThat(fixture.published).isEmpty()
        assertThat(fixture.rows.pendingIds()).containsExactly("K")
        assertThat(fixture.budget.state.spentPoints).isEqualTo(0) // The failed post spends no points.
    }
}
