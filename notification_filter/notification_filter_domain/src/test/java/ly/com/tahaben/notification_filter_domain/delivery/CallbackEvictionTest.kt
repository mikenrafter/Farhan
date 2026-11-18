package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.schedule.threeWindows
import ly.com.tahaben.notification_filter_domain.testsupport.item
import org.junit.Test

class CallbackEvictionTest {
    // why: bounded callback metadata may invalidate ambiguous work, but must not wedge unrelated historical rows.
    // catches: global uncertain-key deferral, losing invalidation at restart, silently dropping history, or charging evicted rows.
    @Test fun `two key capacity persists a terminal disposition while historical pending still delivers`() = runTest {
        val h = DeliveryHarness(threeWindows(8))
        h.rows.upsert(item("historical", 50).copy(sourceSession = "prior-process", sourceEventOrder = 1))
        val coordinator = NotificationDeliveryCoordinator(h.rows, h.budget, h.poster, h.environment, eventCapacity = 2)
        for ((id, timestamp) in listOf("A" to 100L, "B" to 200L, "C" to 300L)) {
            val order = coordinator.captureEvent(id)
            coordinator.onArrival(item(id, timestamp), HIGH, order)
        }
        coordinator.onBatchTick()
        assertThat(h.rows.rows.getValue("A").deliveryInvalidated).isTrue()
        assertThat(h.rows.rows.getValue("A").isCleared).isFalse()
        assertThat(h.rows.rows.getValue("A").isDelivered).isFalse()
        assertThat(h.poster.posted.map { it.id }).containsExactly("C", "B", "historical").inOrder()
        assertThat(h.budget.state.spentPoints).isEqualTo(6)
        assertThat(h.rows.rows).hasSize(4)
        val restarted = NotificationDeliveryCoordinator(h.rows, h.budget, h.poster, h.environment, eventCapacity = 2)
        restarted.onBatchTick()
        assertThat(h.poster.posted).hasSize(3)
        assertThat(h.budget.state.spentPoints).isEqualTo(6)
    }

    // why: clear-all's callback cutoff must not poison a later imported/historical pending row outside that session.
    // catches: reusing the global cutoff as an unknown-key delivery ban.
    @Test fun `clear all cutoff does not defer unrelated historical work inserted later`() = runTest {
        val h = DeliveryHarness(threeWindows(8))
        val coordinator = NotificationDeliveryCoordinator(h.rows, h.budget, h.poster, h.environment, eventCapacity = 2)
        coordinator.clearAll { h.rows.rows.clear() }
        h.rows.upsert(item("history", 100).copy(sourceSession = "prior-process", sourceEventOrder = 1))
        coordinator.onBatchTick()
        assertThat(h.poster.posted.map { it.id }).containsExactly("history")
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
    }
}
