package ly.com.tahaben.notification_filter_domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationPriorityTest {

    @Test
    fun `point costs match delivery tiers`() {
        assertThat(NotificationPriority.CRITICAL.pointCost).isEqualTo(0)
        assertThat(NotificationPriority.HIGH.pointCost).isEqualTo(2)
        assertThat(NotificationPriority.MEDIUM.pointCost).isEqualTo(4)
        assertThat(NotificationPriority.LOW.pointCost).isEqualTo(8)
    }

    @Test
    fun `degraded clamps at low`() {
        assertThat(NotificationPriority.LOW.degraded(1)).isEqualTo(NotificationPriority.LOW)
        assertThat(NotificationPriority.HIGH.degraded(1)).isEqualTo(NotificationPriority.MEDIUM)
        assertThat(NotificationPriority.CRITICAL.degraded(2)).isEqualTo(NotificationPriority.MEDIUM)
    }

    @Test
    fun `fromLevel returns medium for unknown level`() {
        assertThat(NotificationPriority.fromLevel(99)).isEqualTo(NotificationPriority.MEDIUM)
    }
}
