package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence.EVERY_HOUR
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence.EVERY_OTHER_HOUR
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence.EVERY_THIRD_HOUR
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence.ONCE_DAILY
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import org.junit.Test

class SnoozeTimingTest {
    // why: HIGH's hourly cadence uses the next hour even with less than one minute remaining.
    // proves: CLAIM(snooze-next-tick)
    // catches: copying the polling clock's minimum delay and arriving after the boundary.
    @Test
    fun `hourly cadence reaches the next hour even from its last millisecond`() =
        assertTick(EVERY_HOUR, "2026-11-20T08:59:59.999Z", "2026-11-20T09:00:00Z")

    // why: arrival at an eligible boundary must wait for a strictly future tick.
    // proves: CLAIM(snooze-next-tick)
    // catches: accepting the current tick or taking the very next hour for every-other-hour cadence.
    @Test
    fun `every other hour skips the current boundary and the intervening hour`() =
        assertTick(EVERY_OTHER_HOUR, "2026-11-20T08:00:00Z", "2026-11-20T10:00:00Z", priority = NotificationPriority.MEDIUM)

    // why: owners can configure a cadence other than the priority's default.
    // proves: CLAIM(snooze-next-tick)
    // catches: ignoring configured cadence or anchoring a three-hour timer to the arrival.
    @Test
    fun `configured third hour cadence uses the next aligned hour`() =
        assertTick(EVERY_THIRD_HOUR, "2026-11-20T10:05:00Z", "2026-11-20T12:00:00Z")

    // why: the daily cadence rolls over at the next local midnight.
    // proves: CLAIM(snooze-next-tick)
    // catches: using UTC midnight, the next local hour, or a fixed 24-hour delay.
    @Test
    fun `daily cadence crosses midnight in the clock zone`() =
        assertTick(ONCE_DAILY, "2026-11-21T04:17:00Z", "2026-11-21T07:00:00Z", denver, priority = NotificationPriority.LOW)

    // why: Denver jumps from 01:59 MST to 03:00 MDT on 2026-03-08.
    // proves: CLAIM(snooze-next-tick)
    // catches: counting missing wall-clock hours as elapsed hours or skipping the 03:00 tick.
    @Test
    fun `spring forward reaches the third hour tick after fifteen real minutes`() =
        assertTick(EVERY_THIRD_HOUR, "2026-03-08T08:45:00Z", "2026-03-08T09:00:00Z", denver)

    // why: both occurrences of 01:00 are hourly ticks at distinct instants on 2026-11-01.
    // proves: CLAIM(snooze-next-tick)
    // catches: comparing only local times and skipping the second 01:00 boundary.
    @Test
    fun `fall back reaches the repeated one o clock tick`() =
        assertTick(EVERY_HOUR, "2026-11-01T07:30:00Z", "2026-11-01T08:00:00Z", denver)

    // why: in testing mode 10:03 is boundary 201, so the next even boundary is 10:06.
    // proves: CLAIM(snooze-next-tick)
    // catches: using a calendar hour, disregarding boundary index, or applying the poller's five-second floor.
    @Test
    fun `testing mode finds the next eligible three minute boundary`() =
        assertTick(EVERY_OTHER_HOUR, "2026-11-20T10:05:59Z", "2026-11-20T10:06:00Z", testingMode = true)

    private fun assertTick(
        cadence: DeliveryCadence,
        from: String,
        expected: String,
        zone: ZoneId = ZoneOffset.UTC,
        testingMode: Boolean = false,
        priority: NotificationPriority = NotificationPriority.HIGH,
    ) {
        val now = Instant.parse(from)
        val expectedTick = Instant.parse(expected)
        val setting = PriorityDeliverySetting.defaultFor(priority).copy(cadence = cadence)
        val delay = SnoozeTiming.delayUntilNextMoment(priority, setting, now, zone, testingMode)

        assertThat(delay).isGreaterThan(0L)
        assertThat(delay).isEqualTo(Duration.between(now, expectedTick).toMillis())
        val tick = now.plusMillis(delay)
        val local = tick.atZone(zone)
        val context = DeliveryClock.current(testingMode, Clock.fixed(tick, zone))
        assertThat(local.second).isEqualTo(0)
        assertThat(local.nano).isEqualTo(0)
        assertThat(context.minuteOfDay % context.boundaryIntervalMinutes).isEqualTo(0)
        assertThat(NotificationDeliveryPolicy.isBatchTickForCadence(setting.cadence, context)).isTrue()
    }

    private val denver = ZoneId.of("America/Denver")
}
