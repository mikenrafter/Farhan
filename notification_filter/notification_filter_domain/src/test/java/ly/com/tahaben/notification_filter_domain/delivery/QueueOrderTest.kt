package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.testsupport.item
import ly.com.tahaben.notification_filter_domain.testsupport.productionContext
import org.junit.Test

/**
 * Delivery order from the real batch policy (NotificationDeliveryPolicy.selectForBatchDelivery).
 * Order: priority, then app rank, then recency; newest first unless the app opts into oldest first.
 * All items are HIGH (2 points each) at 10:00 with the default every-hour cadence, so order is the only variable.
 * The window budget sets how many are delivered: budget 2 delivers 1, 4 delivers 2, 8 delivers 4.
 *
 * Existing control: NotificationDeliveryPolicyTest `batch delivery prioritizes by priority then timestamp`
 * (distinct priorities) is unchanged and must stay green.
 */
class QueueOrderTest {

    private fun deliver(
        candidates: List<NotificationItem>,
        windowBudget: Int,
        preferences: Map<String, AppQueuePreference> = emptyMap(),
    ): List<String> = NotificationDeliveryPolicy.selectForBatchDelivery(
        candidates = candidates,
        settings = PriorityDeliverySetting.DEFAULTS,
        schedule = listOf(NotificationSchedulePeriod(9, 0, 12, 0, windowBudget)),
        timeContext = productionContext(10),
        budget = DeliveryBudgetState(periodStartMinute = 9 * 60),
        queuePreferences = preferences,
    ).first.map { it.id }

    // why: owner decision: newest first is the default (today the policy is oldest first).
    // catches: leaving the ascending timestamp sort. Hand math: budget 4 = two items; newest two are a3, a2.
    @Test
    fun `an app with no preference delivers newest first`() {
        val items = listOf(item("a1", 100), item("a2", 200), item("a3", 300))

        assertThat(deliver(items, windowBudget = 4)).containsExactly("a3", "a2").inOrder()
    }

    // why: a higher rank is delivered earlier within one priority, whatever the recency.
    // catches: ranking ignored (newest-first only, or oldest-first only). Two scenarios with swapped
    //      timestamps so neither default order reaches the expected answer by accident. Budget 2 = one item.
    @Test
    fun `a higher app rank wins within one priority regardless of recency`() {
        val prefs = mapOf("com.y" to AppQueuePreference(rank = 1))

        val olderRanked = deliver(listOf(item("x", 500, "com.x"), item("y", 100, "com.y")), 2, prefs)
        val newerRanked = deliver(listOf(item("x", 100, "com.x"), item("y", 500, "com.y")), 2, prefs)

        assertThat(olderRanked).containsExactly("y")
        assertThat(newerRanked).containsExactly("y")
    }

    // why: each app queues by its own policy, and the apps' heads are compared by recency.
    // catches: global newest-first (ignores the FIFO app), or draining one app at a time.
    // Hand math: F (oldest first): f1@100, f2@300; N (newest first): n1@200, n2@250. Heads f1@100, n2@250 -> n2.
    //      Heads f1@100, n1@200 -> n1. Then f1, then f2. Global newest-first would give f2,n2,n1,f1.
    @Test
    fun `a FIFO app and a newest-first app interleave by head recency`() {
        val items = listOf(
            item("f1", 100, "com.f"), item("f2", 300, "com.f"),
            item("n1", 200, "com.n"), item("n2", 250, "com.n"),
        )
        val prefs = mapOf("com.f" to AppQueuePreference(QueueOrder.OLDEST_FIRST))

        assertThat(deliver(items, windowBudget = 8, prefs)).containsExactly("n2", "n1", "f1", "f2").inOrder()
    }

    // why: equal timestamps from different apps must give one answer whatever order the database returns them.
    // catches: a stable sort that leaves ties in input order. The order itself is not specified, only that it is fixed.
    @Test
    fun `ties between apps are broken the same way for any input order`() {
        val a = item("a", 100, "com.a")
        val b = item("b", 100, "com.b")

        val forward = deliver(listOf(a, b), windowBudget = 8)
        val reversed = deliver(listOf(b, a), windowBudget = 8)

        assertThat(forward).hasSize(2)
        assertThat(reversed).isEqualTo(forward)
    }
}
