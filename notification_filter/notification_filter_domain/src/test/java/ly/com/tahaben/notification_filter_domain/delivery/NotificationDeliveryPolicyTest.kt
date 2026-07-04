package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import org.junit.Test

class NotificationDeliveryPolicyTest {

    private val activeSchedule = listOf(
        NotificationSchedulePeriod(9, 0, 12, 0, 8),
    )

    private val silentSchedule = listOf(
        NotificationSchedulePeriod(0, 0, 9, 0, 0),
        NotificationSchedulePeriod(9, 0, 12, 0, 8),
    )

    private val defaults = PriorityDeliverySetting.DEFAULTS

    private fun item(
        id: String,
        priority: NotificationPriority,
        timestamp: Long = 100L,
    ) = NotificationItem(
        id = id,
        appName = "App",
        title = "Title",
        text = "Text",
        time = "12:00",
        packageName = "com.test",
        timestamp = timestamp,
        priority = priority,
        category = NotificationCategory.OTHER,
    )

    private fun productionContext(hour: Int, minute: Int = 0) = DeliveryTimeContext.from(
        calendarHour = hour,
        minuteOfDay = hour * 60 + minute,
        dayOfYear = 100,
        testingMode = false,
    )

    private fun testingContext(minuteOfDay: Int) = DeliveryTimeContext.from(
        calendarHour = minuteOfDay / 60,
        minuteOfDay = minuteOfDay,
        dayOfYear = 100,
        testingMode = true,
    )

    @Test
    fun `critical default delivers immediately ignoring budget`() {
        val setting = defaults.getValue(NotificationPriority.CRITICAL)
        val decision = NotificationDeliveryPolicy.canDeliverNow(
            priority = NotificationPriority.CRITICAL,
            setting = setting,
            schedule = silentSchedule,
            timeContext = productionContext(2),
            budget = DeliveryBudgetState(),
        )

        assertThat(decision.canDeliver).isTrue()
    }

    @Test
    fun `high default respects budget and blocks outside schedule`() {
        val setting = defaults.getValue(NotificationPriority.HIGH)
        val decision = NotificationDeliveryPolicy.canDeliverNow(
            priority = NotificationPriority.HIGH,
            setting = setting,
            schedule = activeSchedule,
            timeContext = productionContext(8),
            budget = DeliveryBudgetState(),
        )

        assertThat(decision.canDeliver).isFalse()
    }

    @Test
    fun `high allowed inside schedule with sufficient budget`() {
        val setting = defaults.getValue(NotificationPriority.HIGH)
        val decision = NotificationDeliveryPolicy.canDeliverNow(
            priority = NotificationPriority.HIGH,
            setting = setting,
            schedule = activeSchedule,
            timeContext = productionContext(10),
            budget = DeliveryBudgetState(),
        )

        assertThat(decision.canDeliver).isTrue()
    }

    @Test
    fun `medium batch only on even hour boundaries`() {
        val setting = defaults.getValue(NotificationPriority.MEDIUM)
        assertThat(
            NotificationDeliveryPolicy.shouldDeliverOnBatchTick(
                NotificationPriority.MEDIUM,
                defaults,
                productionContext(8),
            )
        ).isTrue()
        assertThat(
            NotificationDeliveryPolicy.shouldDeliverOnBatchTick(
                NotificationPriority.MEDIUM,
                defaults,
                productionContext(9),
            )
        ).isFalse()
    }

    @Test
    fun `low batch only on first boundary of day`() {
        assertThat(
            NotificationDeliveryPolicy.shouldDeliverOnBatchTick(
                NotificationPriority.LOW,
                defaults,
                productionContext(0),
            )
        ).isTrue()
        assertThat(
            NotificationDeliveryPolicy.shouldDeliverOnBatchTick(
                NotificationPriority.LOW,
                defaults,
                productionContext(1),
            )
        ).isFalse()
    }

    @Test
    fun `testing mode uses 3 minute boundaries`() {
        val ctx = testingContext(minuteOfDay = 7)
        assertThat(ctx.boundaryIntervalMinutes).isEqualTo(3)
        assertThat(ctx.boundaryIndex).isEqualTo(2)
    }

    @Test
    fun `testing mode every hour cadence fires every boundary`() {
        val setting = PriorityDeliverySetting(DeliveryCadence.EVERY_HOUR, BudgetBehavior.RESPECTING)
        val settings = mapOf(NotificationPriority.HIGH to setting)

        assertThat(
            NotificationDeliveryPolicy.shouldDeliverOnBatchTick(
                NotificationPriority.HIGH,
                settings,
                testingContext(3),
            )
        ).isTrue()
        assertThat(
            NotificationDeliveryPolicy.shouldDeliverOnBatchTick(
                NotificationPriority.HIGH,
                settings,
                testingContext(4),
            )
        ).isTrue()
    }

    @Test
    fun `testing mode every other hour cadence fires every second boundary`() {
        val setting = PriorityDeliverySetting(DeliveryCadence.EVERY_OTHER_HOUR, BudgetBehavior.RESPECTING)
        val settings = mapOf(NotificationPriority.MEDIUM to setting)

        assertThat(
            NotificationDeliveryPolicy.shouldDeliverOnBatchTick(
                NotificationPriority.MEDIUM,
                settings,
                testingContext(6),
            )
        ).isTrue()
        assertThat(
            NotificationDeliveryPolicy.shouldDeliverOnBatchTick(
                NotificationPriority.MEDIUM,
                settings,
                testingContext(9),
            )
        ).isFalse()
    }

    @Test
    fun `testing mode batch delivery uses scaled schedule minutes`() {
        val activeSchedule = listOf(
            NotificationSchedulePeriod(9, 0, 12, 0, 8),
        )
        val setting = PriorityDeliverySetting(DeliveryCadence.EVERY_HOUR, BudgetBehavior.RESPECTING)
        val settings = mapOf(NotificationPriority.HIGH to setting)
        val candidates = listOf(item("high", NotificationPriority.HIGH))

        val (delivered, _) = NotificationDeliveryPolicy.selectForBatchDelivery(
            candidates = candidates,
            settings = settings,
            schedule = activeSchedule,
            timeContext = testingContext(minuteOfDay = 30),
            budget = DeliveryBudgetState(periodStartMinute = 9 * 60),
        )

        assertThat(delivered.map { it.id }).containsExactly("high")
    }

    @Test
    fun `batch delivery prioritizes by priority then timestamp`() {
        val candidates = listOf(
            item("medium-old", NotificationPriority.MEDIUM, timestamp = 1),
            item("high", NotificationPriority.HIGH, timestamp = 2),
        )

        val (delivered, _) = NotificationDeliveryPolicy.selectForBatchDelivery(
            candidates = candidates,
            settings = defaults,
            schedule = activeSchedule,
            timeContext = productionContext(10),
            budget = DeliveryBudgetState(periodStartMinute = 9 * 60),
        )

        assertThat(delivered.map { it.id }).containsExactly("high", "medium-old").inOrder()
    }

    @Test
    fun `batch skips priorities not scheduled for current boundary`() {
        val candidates = listOf(
            item("high", NotificationPriority.HIGH, timestamp = 1),
            item("medium", NotificationPriority.MEDIUM, timestamp = 2),
        )

        val (delivered, _) = NotificationDeliveryPolicy.selectForBatchDelivery(
            candidates = candidates,
            settings = defaults,
            schedule = activeSchedule,
            timeContext = productionContext(9),
            budget = DeliveryBudgetState(periodStartMinute = 9 * 60),
        )

        assertThat(delivered.map { it.id }).containsExactly("high")
    }

    @Test
    fun `ignoring budget delivers even when schedule budget exhausted`() {
        val settings = mapOf(
            NotificationPriority.HIGH to PriorityDeliverySetting(
                DeliveryCadence.EVERY_HOUR,
                BudgetBehavior.IGNORING,
            )
        )
        val candidates = listOf(item("high", NotificationPriority.HIGH))

        val (delivered, _) = NotificationDeliveryPolicy.selectForBatchDelivery(
            candidates = candidates,
            settings = settings,
            schedule = silentSchedule,
            timeContext = productionContext(2),
            budget = DeliveryBudgetState(spentPoints = 999, periodStartMinute = 0),
        )

        assertThat(delivered).hasSize(1)
    }
}
