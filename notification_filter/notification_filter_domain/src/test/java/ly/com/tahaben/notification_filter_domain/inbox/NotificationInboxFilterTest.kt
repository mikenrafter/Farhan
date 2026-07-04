package ly.com.tahaben.notification_filter_domain.inbox

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import org.junit.Test

class NotificationInboxFilterTest {

    private val now = 1_700_000_000_000L
    private val standingHours = 3

    private fun item(
        id: String,
        priority: NotificationPriority,
        timestamp: Long = now,
        isRead: Boolean = false,
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
        isRead = isRead,
    )

    @Test
    fun `default all filter includes critical notifications regression`() {
        val notifications = listOf(
            item("critical", NotificationPriority.CRITICAL),
            item("high", NotificationPriority.HIGH),
        )

        val result = NotificationInboxFilter.filter(
            notifications = notifications,
            priorityFilter = null,
            standingThresholdHours = standingHours,
            nowMillis = now,
        )

        assertThat(result.map { it.id }).containsExactly("critical", "high")
    }

    @Test
    fun `null filter returns all priorities including critical`() {
        val notifications = listOf(
            item("1", NotificationPriority.CRITICAL),
            item("2", NotificationPriority.HIGH),
            item("3", NotificationPriority.LOW),
        )

        val result = NotificationInboxFilter.filter(
            notifications = notifications,
            priorityFilter = null,
            standingThresholdHours = standingHours,
            nowMillis = now,
        )

        assertThat(result).hasSize(3)
    }

    @Test
    fun `critical filter shows only critical notifications`() {
        val notifications = listOf(
            item("1", NotificationPriority.CRITICAL),
            item("2", NotificationPriority.HIGH),
        )

        val result = NotificationInboxFilter.filter(
            notifications = notifications,
            priorityFilter = NotificationPriority.CRITICAL,
            standingThresholdHours = standingHours,
            nowMillis = now,
        )

        assertThat(result.map { it.id }).containsExactly("1")
    }

    @Test
    fun `high filter excludes critical notifications`() {
        val notifications = listOf(
            item("1", NotificationPriority.CRITICAL),
            item("2", NotificationPriority.HIGH),
        )

        val result = NotificationInboxFilter.filter(
            notifications = notifications,
            priorityFilter = NotificationPriority.HIGH,
            standingThresholdHours = standingHours,
            nowMillis = now,
        )

        assertThat(result.map { it.id }).containsExactly("2")
    }

    @Test
    fun `standing unread high degrades to medium for filtering`() {
        val oldTimestamp = now - (standingHours * 3_600_000L + 1)
        val notifications = listOf(
            item("1", NotificationPriority.HIGH, timestamp = oldTimestamp, isRead = false),
            item("2", NotificationPriority.MEDIUM),
        )

        val result = NotificationInboxFilter.filter(
            notifications = notifications,
            priorityFilter = NotificationPriority.MEDIUM,
            standingThresholdHours = standingHours,
            nowMillis = now,
        )

        assertThat(result.map { it.id }).containsExactly("1", "2")
    }

    @Test
    fun `standing threshold uses configured hours not hardcoded value`() {
        val fourHoursAgo = now - (4 * 3_600_000L + 1)
        val notification = item("1", NotificationPriority.HIGH, timestamp = fourHoursAgo)

        assertThat(
            NotificationInboxFilter.effectivePriority(notification, standingThresholdHours = 3, nowMillis = now)
        ).isEqualTo(NotificationPriority.MEDIUM)

        assertThat(
            NotificationInboxFilter.effectivePriority(notification, standingThresholdHours = 6, nowMillis = now)
        ).isEqualTo(NotificationPriority.HIGH)
    }

    @Test
    fun `read notifications are not degraded when standing`() {
        val oldTimestamp = now - (standingHours * 3_600_000L + 1)
        val notification = item("1", NotificationPriority.HIGH, timestamp = oldTimestamp, isRead = true)

        assertThat(
            NotificationInboxFilter.effectivePriority(notification, standingThresholdHours = standingHours, nowMillis = now)
        ).isEqualTo(NotificationPriority.HIGH)
    }
}
