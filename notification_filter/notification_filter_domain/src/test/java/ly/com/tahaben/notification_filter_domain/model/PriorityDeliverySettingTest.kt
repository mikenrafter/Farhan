package ly.com.tahaben.notification_filter_domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PriorityDeliverySettingTest {

    @Test
    fun `defaults match product spec`() {
        assertThat(PriorityDeliverySetting.DEFAULTS[NotificationPriority.CRITICAL]).isEqualTo(
            PriorityDeliverySetting(DeliveryCadence.IMMEDIATELY, BudgetBehavior.IGNORING)
        )
        assertThat(PriorityDeliverySetting.DEFAULTS[NotificationPriority.HIGH]).isEqualTo(
            PriorityDeliverySetting(DeliveryCadence.EVERY_HOUR, BudgetBehavior.RESPECTING)
        )
        assertThat(PriorityDeliverySetting.DEFAULTS[NotificationPriority.MEDIUM]).isEqualTo(
            PriorityDeliverySetting(DeliveryCadence.EVERY_OTHER_HOUR, BudgetBehavior.RESPECTING)
        )
        assertThat(PriorityDeliverySetting.DEFAULTS[NotificationPriority.LOW]).isEqualTo(
            PriorityDeliverySetting(DeliveryCadence.ONCE_DAILY, BudgetBehavior.RESPECTING)
        )
    }
}
